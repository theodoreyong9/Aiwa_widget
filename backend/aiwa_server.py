#!/usr/bin/env python3
"""Local bridge between the Aiwa Android app and Claude Code CLOUD sessions.

Binds to 127.0.0.1 only — this is a same-device bridge, never a network
service, and has no authentication of its own on that basis.

Every Aiwa conversation is a Claude Code cloud session, created and
continued through the official CLI (`claude --cloud`), so it is visible in
the Claude app's Code tab and needs no API key — just the claude.ai login
(Pro or above). Aiwa used to run its own `claude` process on the phone;
that was removed on request (no more phone/CLI sessions).

HONEST LIMIT (documented by Anthropic): the CLI only QUEUES a message and
returns. There is no way to read a cloud session's reply back from a
program, so Aiwa sends and the answer is read in the Claude app.

GitHub (aiwa_github.py): Aiwa can start the cloud session on one of the
account's repositories and tell Claude to push straight to it (or to a
work branch).
"""
import fcntl
import hashlib
import json
import os
import pty
import re
import select
import shlex
import secrets
import shutil
import signal
import struct
import subprocess
import termios
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import aiwa_github as github


def _ts():
    return time.strftime("%H:%M:%S")


HOST = "127.0.0.1"
PORT = 8787
# Bumped whenever the app starts depending on a new backend feature; the
# app compares it (via /api/status) with the version it expects.
BACKEND_VERSION = 12
# Passed to `claude --model` when a new cloud session is created, and to
# `/model` in an existing one. Kept restrictive: it ends up as a
# command-line argument / slash-command argument.
MODEL_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}")
EXTRA_MAX = 600  # the user's own instruction text
# The effort levels `/effort` and `claude --effort` accept (None = automatic).
EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")
# A public relay Claude pings when it waits for an answer (see _compose).
NTFY_SERVER = "https://ntfy.sh"

# A cloud session needs a git repository to start from. Documented: a
# local repo with at least one commit is uploaded as a bundle, no GitHub
# needed. Same directory the manual test used, so a one-time "trust this
# folder" answer given there still applies.
CLOUD_DIR = Path.home() / "chat-cloud"
CLOUD_STORE = Path.home() / ".aiwa_cloud_sessions.json"
STATE_FILE = Path.home() / ".aiwa_state.json"
# The full output of the last `claude --cloud` run, for diagnosing a
# failure (`cat` it from Termux) — the error shown in the app is only the tail.
CLOUD_LOG = Path.home() / "aiwa_cloud_last.log"
CLOUD_ID_RE = re.compile(r"(?:session|cse)_[A-Za-z0-9]+")
CLOUD_URL_RE = re.compile(r"https://claude\.ai/code/[^\s\"')>\]]+")

lock = threading.Lock()  # guards the state below
# None = the CLI's own default model.
current_model = None
# The cloud session messages go to; None = the next message creates one.
current_cloud = None
# The session most recently in use: kept when the next message is to start
# a NEW one (a repository was chosen, "new session"), so the app can still
# open the conversation you were in.
last_cloud = None
cloud_busy = False
# Instructions integrated into the conversation (see _compose). Claude
# Code does the work itself; these only tell it what the user wants:
# current_repo: the repository new sessions start on ("owner/name"; None =
# the plain chat); push_main: push straight to the main branch (otherwise
# to a work branch); autodeploy: publish with GitHub Pages through GitHub
# Actions; extra: free text. The alert instruction (ping the relay when you
# wait for an answer) is always there — it is mandatory, not a switch.
current_repo = None
push_main = True
autodeploy = False
extra = ""
# None = the CLI's own default effort.
current_effort = None
# The relay topic (a random secret) and whether Claude has pinged it since
# the user last sent a message or opened the session.
waiting_topic = None
waiting_lock = threading.Lock()
waiting = {"since": None, "last_ping": None}
github_error = None  # the last GitHub problem worth showing in the app
# Whether the Pages address of current_repo answers, probed in the background.
site_lock = threading.Lock()
site_cache = {"url": None, "state": "off", "at": 0.0, "busy": False}
ci_cache = {"repo": None, "info": None, "at": 0.0, "busy": False}
# repo -> id of the last Actions run the user was told about (persisted): a
# green run with another id is news — "you can go and look".
ci_seen = {}
REPOS_STORE = Path.home() / ".aiwa_repos.json"
store_lock = threading.Lock()
# Two `claude -p --cloud <id>` runs never overlap (a send and the /rename
# that follows a creation, for instance).
followup_lock = threading.Lock()


def _load_state():
    global current_model, current_cloud, current_repo, push_main, autodeploy, extra
    global current_effort, waiting_topic, last_cloud
    try:
        data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = None
    if not isinstance(data, dict):
        data = {}
    model, cloud, repo = data.get("model"), data.get("cloud"), data.get("repo")
    current_model = model if isinstance(model, str) and MODEL_RE.fullmatch(model) else None
    current_cloud = cloud if isinstance(cloud, str) and CLOUD_ID_RE.fullmatch(cloud) else None
    last = data.get("last_cloud")
    last_cloud = last if isinstance(last, str) and CLOUD_ID_RE.fullmatch(last) else current_cloud
    current_repo = repo if isinstance(repo, str) and github.REPO_RE.fullmatch(repo) else None
    push_main = data.get("push_main") is not False
    autodeploy = data.get("autodeploy") is True
    text = data.get("extra")
    extra = text.strip()[:EXTRA_MAX] if isinstance(text, str) else ""
    effort = data.get("effort")
    current_effort = effort if effort in EFFORT_LEVELS else None
    seen = data.get("ci_seen")
    ci_seen.clear()
    if isinstance(seen, dict):
        ci_seen.update({k: v for k, v in seen.items() if isinstance(k, str) and isinstance(v, int)})
    topic = data.get("topic")
    valid = isinstance(topic, str) and re.fullmatch(r"aiwa-[a-f0-9]{24}", topic)
    waiting_topic = topic if valid else "aiwa-" + secrets.token_hex(12)
    if not valid:
        _save_state()  # the topic must survive a restart: sessions were told it


def _save_state():
    """Survives a backend restart (the app restarts it when it updates):
    without this, the next message after a restart would silently start a
    brand-new cloud session instead of continuing the current one."""
    try:
        STATE_FILE.write_text(json.dumps({
            "model": current_model, "cloud": current_cloud, "repo": current_repo,
            "push_main": push_main, "autodeploy": autodeploy, "extra": extra,
            "effort": current_effort, "topic": waiting_topic, "last_cloud": last_cloud, "ci_seen": ci_seen,
        }), encoding="utf-8")
    except OSError as err:
        print(f"[{_ts()}] could not save state: {err}", flush=True)


_clean = github.clean


def _ensure_cloud_repo():
    CLOUD_DIR.mkdir(parents=True, exist_ok=True)
    if not (CLOUD_DIR / ".git").exists():
        subprocess.run(["git", "init", "-q"], cwd=CLOUD_DIR, check=True)
    has_commit = subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD"], cwd=CLOUD_DIR, capture_output=True).returncode == 0
    if not has_commit:
        (CLOUD_DIR / "README.md").write_text("chat\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=CLOUD_DIR, check=True)
        subprocess.run(["git", "-c", "user.name=aiwa", "-c", "user.email=aiwa@example.com", "commit", "-qm", "init"], cwd=CLOUD_DIR, check=True)


def _signal_group(proc, sig):
    try:
        os.killpg(proc.pid, sig)
    except OSError:
        pass


def _run_with_pty(command, cwd, timeout, stop_after_session_id=False):
    """Runs a command as if in a real terminal (creating a cloud session
    shows a live progress display, and that is how the manual test that
    worked ran it). Returns (exit code, cleaned output, reason it ended,
    timeline).

    stop_after_session_id: `claude --cloud "task"` most likely stays open
    like a normal Claude after creating the session (Anthropic's wording:
    "the task runs in the cloud while you continue working locally"), so
    waiting for it to exit meant waiting out the whole timeout — reported
    live as the session taking far too long to appear compared with the
    manual test. Once a session id has appeared and the output has gone
    quiet for a few seconds, the session exists: stop the CLI."""
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 200, 0, 0))
    env = dict(os.environ)
    if env.get("TERM", "dumb") in ("", "dumb"):
        env["TERM"] = "xterm-256color"
    start = time.time()
    timeline = []

    def mark(what):
        timeline.append(f"{time.time() - start:5.1f}s {what}")

    proc = subprocess.Popen(command, cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave, close_fds=True, start_new_session=True)
    os.close(slave)
    mark("started")
    chunks = []
    reason = "exited"
    found_at = None
    last_data = start
    try:
        while True:
            now = time.time()
            left = start + timeout - now
            if left <= 0:
                reason = "timeout"
                break
            if stop_after_session_id and found_at is not None and (now - last_data >= 4 or now - found_at >= 25):
                reason = "stopped after the session id appeared"
                break
            ready, _, _ = select.select([master], [], [], min(left, 1.0))
            if ready:
                try:
                    data = os.read(master, 4096)
                except OSError:
                    break
                if not data:
                    break
                if not chunks:
                    mark("first output")
                chunks.append(data)
                last_data = time.time()
                if stop_after_session_id and found_at is None:
                    if CLOUD_ID_RE.search(_clean(b"".join(chunks).decode("utf-8", "replace"))):
                        found_at = last_data
                        mark("session id seen")
            elif proc.poll() is not None:
                break
    finally:
        if reason != "exited" and proc.poll() is None:
            _signal_group(proc, signal.SIGTERM)
        os.close(master)
    try:
        code = proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()
        code = proc.wait()
    if reason != "exited":
        # `script` may leave the CLI it started running. It was started in
        # its own session, so its whole group can be killed without
        # touching any other `claude` process (a follow-up running at the
        # same time, for instance).
        _signal_group(proc, signal.SIGKILL)
    mark(f"ended: {reason} (exit {code})")
    return code, _clean(b"".join(chunks).decode("utf-8", "replace")), reason, timeline


def _log_cloud(kind, command, code, output, timeline=()):
    try:
        CLOUD_LOG.write_text(
            f"[{_ts()}] {kind} exit={code}\n$ {shlex.join(command)}\n" + "\n".join(timeline) + f"\n\n{output}\n",
            encoding="utf-8",
        )
    except OSError:
        pass


def _load_cloud_sessions():
    try:
        data = json.loads(CLOUD_STORE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return data if isinstance(data, list) else []


def _save_cloud_session(session_id, title, url, repo=None, work=None, base=None, direct=None, instr=None, model=None, effort=None):
    """The CLI has no non-interactive way to LIST cloud sessions, so the
    ones Aiwa created or was given a link to are remembered here, with the
    repository (and branch) a session was started on and a fingerprint of
    the instructions it was last given."""
    with store_lock:
        entries = _load_cloud_sessions()
        existing = next((e for e in entries if e.get("id") == session_id), None) or {}
        entries = [e for e in entries if e.get("id") != session_id]
        # Everything already known is kept, whatever this call is about;
        # only what is given changes. work: the session's own branch; base:
        # the repository's default branch; direct: integrate into base.
        entry = dict(existing)
        entry.update({
            "id": session_id,
            "title": existing.get("title") or title,
            "url": url or existing.get("url") or f"https://claude.ai/code/{session_id}",
        })
        # model / effort: "" = automatic, absent = unknown.
        for key, value in (("repo", repo), ("work", work), ("base", base), ("direct", direct), ("instr", instr), ("model", model), ("effort", effort)):
            if value is not None:
                entry[key] = value
        entries.insert(0, entry)
        CLOUD_STORE.write_text(json.dumps(entries[:30]), encoding="utf-8")


def _update_session(session_id, **fields):
    """Changes fields of a remembered session in place (its rank is kept)."""
    with store_lock:
        entries = _load_cloud_sessions()
        for entry in entries:
            if entry.get("id") == session_id:
                entry.update(fields)
                CLOUD_STORE.write_text(json.dumps(entries), encoding="utf-8")
                return


def _session_entry(session_id):
    return next((e for e in _load_cloud_sessions() if e.get("id") == session_id), {})


def _remember_repo(repo):
    """The repositories offered in the picker: the ones used or added."""
    with store_lock:
        try:
            known = json.loads(REPOS_STORE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            known = []
        known = [repo] + [r for r in known if isinstance(r, str) and r != repo]
        try:
            REPOS_STORE.write_text(json.dumps(known[:30]), encoding="utf-8")
        except OSError:
            pass


def _known_repos():
    try:
        known = json.loads(REPOS_STORE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        known = []
    return [r for r in known if isinstance(r, str) and github.REPO_RE.fullmatch(r)]


_INSTRUCTION_LABELS = {"repo": "dépôt", "push": "push", "deploy": "déploiement", "verify": "vérification", "alert": "alerte", "extra": "consigne perso"}


def _instruction_lines(repo, work, base, direct):
    """What the user's switches ask of Claude Code, in words, as (key, text)
    pairs. Claude Code does all of it itself, with its own GitHub access."""
    with lock:
        deploy, own, topic = autodeploy, extra, waiting_topic
    lines = []
    if repo:
        lines.append(("repo", f"Dépôt : {repo}. Ton répertoire de travail doit être ce dépôt GitHub (vérifie `git remote -v`) ; si ce n'est pas le cas, dis-le-moi et arrête-toi."))
        if direct:
            lines.append((
                "push",
                f"Push : travaille sur ta propre branche ({work}) : commits-y et pousse-la. Quand un changement est terminé, intègre TOUTE ta branche "
                f"dans {base} (y compris les commits faits quand l'intégration était désactivée) et pousse {base}, sans pull request à relire : "
                "par push direct si ta session le permet, sinon en ouvrant une pull request que tu fusionnes aussitôt. "
                f"Le push se fait dans {base}, qui est la branche que GitHub Pages déploie ; seule exception : si le déploiement Pages de ce dépôt "
                "est configuré pour publier depuis ta propre branche (regarde le workflow Pages et ses déclencheurs), pousse alors sur cette branche-là. "
                f"Avant d'intégrer : récupère {base} et vérifie qu'il n'a pas reçu de modification parallèle qui entre en conflit avec les tiennes. "
                "S'il y a un conflit, ou le moindre doute, n'intègre rien : explique-moi le problème et pose-moi la question. "
                "Jamais de force-push, jamais d'écrasement du travail de quelqu'un d'autre.",
            ))
        else:
            lines.append((
                "push",
                f"Push : travaille sur ta propre branche ({work}) (crée-la depuis {base} si elle n'existe pas) : commits-y et pousse-la. "
                f"N'intègre rien dans {base} et ne pousse pas dessus : tes changements s'accumulent sur ta branche et seront intégrés en entier "
                "au prochain passage en intégration directe.",
            ))
        if deploy:
            lines.append((
                "deploy",
                f"Déploiement : le site est publié par GitHub Pages via GitHub Actions, à l'adresse {github.pages_url(repo)}. "
                "S'il n'y a pas encore de workflow Pages (actions/configure-pages, upload-pages-artifact, deploy-pages, déclenché à chaque push sur la branche principale), ajoute-le ; pas de branche gh-pages. "
                f"Le déploiement ne se déclenche que par un push sur {base} : tant que ton travail n'y est pas intégré, rien n'est publié. "
                "Si activer Pages avec la source « GitHub Actions » est hors de ta portée, dis-moi précisément le réglage à faire. Après un changement, vérifie que le déploiement a réussi.",
            ))
        lines.append((
            "verify",
            "Vérification : avant de dire que c'est fini, contrôle ton travail toi-même. Lance les tests, le lint et le build s'il y en a. "
            "Après un push, regarde le résultat des GitHub Actions et lis leurs logs (jobs en échec compris) avec les outils dont tu disposes, et corrige avant de conclure. "
            "Si le projet a une interface (page web, site), contrôle l'UX avec Playwright — Chromium est déjà installé dans ta session, ne lance pas `playwright install` : "
            "ouvre la page (en local, puis à l'adresse publiée après un déploiement) en mobile (~390 px) puis en bureau, fais des captures, et vérifie qu'il n'y a ni erreur dans la console, "
            "ni requête cassée, ni débordement, et que les parcours principaux fonctionnent. Dis-moi ce que tu as vérifié et ce qui reste douteux.",
        ))
    # Mandatory, not a switch: it is how the widget learns that Claude is
    # waiting (the CLI can't read a cloud reply back). A public relay, a
    # random topic; the command is harmless when the network blocks it.
    lines.append((
        "alert",
        "Alerte (obligatoire) : à la fin de CHAQUE réponse — quand tu as terminé, ou que tu attends une réponse, "
        f"une décision ou une validation de ma part — exécute cette commande shell pour me prévenir : `curl -s -m 5 -d attend {NTFY_SERVER}/{topic}` "
        "(si le réseau la bloque, ignore l'erreur et continue). Termine ensuite ta réponse par ta question s'il y en a une.",
    ))
    if own:
        lines.append(("extra", f"Consigne perso : {own}"))
    return lines


def _compose(entry, repo, work, base, direct):
    """The instructions added to a message, and what was told (a hash per
    instruction, kept with the session). Everything goes with a session's
    FIRST message; after that only what changed since — a new or altered
    instruction, or a note that one was withdrawn — and nothing at all when
    nothing changed, so the conversation isn't buried in repeats."""
    lines = _instruction_lines(repo, work, base, direct)
    told = {key: hashlib.sha1(text.encode()).hexdigest()[:8] for key, text in lines}
    previous = (entry or {}).get("instr")
    if not isinstance(previous, dict) or not previous:
        return "\n\n[Aiwa — consignes de cette conversation]\n" + "\n".join(f"- {text}" for _, text in lines), told
    parts = [f"- {text}" for key, text in lines if previous.get(key) != told[key]]
    parts += [f"- Consigne retirée : {_INSTRUCTION_LABELS.get(key, key)}." for key in previous if key not in told]
    if not parts:
        return "", told
    return "\n\n[Aiwa — consignes mises à jour]\n" + "\n".join(parts), told


def _preview():
    """The full block the current settings would add, for the app to show."""
    with lock:
        repo, direct, session = current_repo, push_main, current_cloud
    entry = _session_entry(session) if session else {}
    text, _ = _compose({}, repo, entry.get("work") or "aiwa/<date>", entry.get("base") or "main", direct)
    return text.strip()


def _last_json_object(output):
    for line in reversed(output.strip().splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def _session_targets(session_id, entry):
    """(work, base) of a repository session. Sessions remembered by older
    versions get them filled in, once."""
    work, base = entry.get("work"), entry.get("base")
    if not work or not base:
        legacy = entry.get("branch")
        base = base or entry.get("home") or (legacy if entry.get("direct") else None) or "main"
        work = work or (legacy if entry.get("direct") is False and legacy else None) or "aiwa/" + time.strftime("%Y%m%d-%H%M%S")
        _update_session(session_id, work=work, base=base)
    return work, base


def _branch_candidates():
    """Repositories to look a branch up in: the current one, the ones of
    remembered sessions, then the discovered list (see _repo_choices)."""
    with lock:
        current = current_repo
    names = [current] + [e.get("repo") for e in _load_cloud_sessions()] + [c["name"] for c in _repo_choices()]
    seen, out = set(), []
    for name in names:
        if name and name not in seen:
            seen.add(name)
            out.append(name)
    return out[:10]


def cloud_add(text):
    """Adds an EXISTING cloud session and selects it. `text` is its link or
    id (copied from the Claude app / claude.ai/code) — or the name of its
    branch (claude/…), which is looked up in the repositories Aiwa knows:
    a session's commits carry its link. Returns (session id, None), or
    (None, why not)."""
    global current_cloud, current_repo, last_cloud
    match = CLOUD_ID_RE.search(text)
    repo = branch = None
    if match:
        session_id = match.group(0)
        title = "Session " + session_id[:16]
    else:
        branch_match = re.search(r"claude/[A-Za-z0-9._/-]+", text)
        if branch_match is None:
            return None, "Ce n'est ni le lien d'une session (claude.ai/code/session_…) ni le nom d'une branche claude/…"
        branch = branch_match.group(0).rstrip("/.")
        found, tried = github.find_branch_session(_branch_candidates(), branch)
        print(f"[{_ts()}] import by branch {branch}: tried {tried}", flush=True)
        if found is None:
            wording = {"missing": "branche absente", "unreachable": "privé ou inaccessible", "trop lent": "trop lent"}
            detail = ", ".join(f"{name} ({wording.get(state, state)})" for name, state in tried) or "aucun dépôt candidat"
            return None, (
                f"Branche {branch} introuvable. Dépôts essayés : {detail}. "
                "Un dépôt privé ne peut pas être lu par Aiwa : copie plutôt le lien de la session "
                "(claude.ai/code/session_…) — ou la commande « claude --teleport session_… » si le menu de la session la propose."
            )
        repo, session_id = found
        if session_id is None:
            return None, (
                f"Branche trouvée dans {repo}, mais aucun de ses derniers commits ne mentionne la session. "
                "Copie plutôt le lien de la session (claude.ai/code/session_…)."
            )
        title = branch
    url_match = CLOUD_URL_RE.search(text)
    # An imported session works on its own branch: that is the only one Claude can push to.
    # An imported session keeps its own branch as its work branch.
    _save_cloud_session(
        session_id, title, url_match.group(0) if url_match else None,
        repo=repo, work=branch, base=github.default_branch(repo) if branch else None, direct=True if branch else None,
    )
    with lock:
        current_cloud = last_cloud = session_id
        current_repo = _session_entry(session_id).get("repo")
        _save_state()
    return session_id, None


def _queue_followup(session_id, text):
    """Queues one message into an existing cloud session
    (`claude -p --cloud <id>`). Returns {"ok", "url", "error"}."""
    command = ["claude", "-p", "--cloud", session_id, "--output-format", "json"]
    with followup_lock:
        done = subprocess.run(command, input=text, capture_output=True, text=True, timeout=90)
    output = _clean((done.stdout or "") + (done.stderr or ""))
    _log_cloud("follow-up", command, done.returncode, output)
    data = _last_json_object(output)
    if data is not None:
        ok, url, error = data.get("ok") is True, data.get("url"), data.get("error")
    else:
        ok, url, error = done.returncode == 0, None, None
    if not ok:
        error = error or output.strip()[-600:] or f"code {done.returncode}"
    return {"ok": ok, "url": url, "error": error}


def _rename_session(session_id, title):
    """Claude names a cloud session itself, and the CLI can't read that name
    back — so Aiwa imposes its own: the name Aiwa shows is sent to the
    session with `/rename`, which cloud sessions document as taking its
    value as an argument. Best effort, in the background."""
    try:
        result = _queue_followup(session_id, f"/rename {title}")
        print(f"[{_ts()}] rename {session_id[:16]}: ok={result['ok']} {result['error'] or ''}", flush=True)
    except (OSError, subprocess.SubprocessError) as err:
        print(f"[{_ts()}] rename failed: {err}", flush=True)


def cloud_send(text, command=False):
    """Sends one message to the current cloud session — creating a new
    one when none is selected. Synchronous: creating a session can take a
    while, the cloud machine has to start.

    command=True: `text` is a slash command for the CURRENT session (e.g.
    `/model opus`); it never creates a session and leaves the session's
    name and rank in the list alone."""
    global current_cloud, cloud_busy, github_error, last_cloud
    with lock:
        if cloud_busy:
            return {"ok": False, "error": "busy"}
        session_id = current_cloud
        model = current_model
        repo, direct_now = current_repo, push_main
        if command and not session_id:
            return {"ok": False, "error": "aucune session en cours"}
        cloud_busy = True
    title = " ".join(text.split())[:50]
    if not command:
        _clear_waiting()  # the user answered: whatever Claude was waiting for is over
    try:
        if session_id:
            sent, fingerprint = text, None
            if not command:
                entry = _session_entry(session_id)
                work = base = None
                if entry.get("repo"):
                    work, base = _session_targets(session_id, entry)
                    entry = _session_entry(session_id)
                extra_text, fingerprint = _compose(entry, entry.get("repo"), work, base, entry.get("direct", True))
                sent += extra_text
            result = _queue_followup(session_id, sent)
            if not result["ok"]:
                return {"ok": False, "error": result["error"]}
            if not command:
                _save_cloud_session(session_id, title, result["url"], instr=fingerprint)
            return {"ok": True, "session_id": session_id, "url": result["url"]}
        directory, work, base = CLOUD_DIR, None, None
        if repo:
            # The session starts on the chosen repository: the cloud clones
            # the GitHub remote of this directory itself, with Claude's own
            # access (the user grants it at claude.ai/connect-github).
            try:
                directory, work, base = github.prepare_repo_dir(repo)
            except github.GithubError as err:
                github_error = f"dépôt {repo} : {err}"
                return {"ok": False, "error": github_error}
            github_error = None
        else:
            _ensure_cloud_repo()
        extra_text, fingerprint = _compose({}, repo, work, base, direct_now)
        task = text + extra_text
        if task.lstrip().startswith("-"):
            task = "Message : " + task
        with lock:
            effort = current_effort
        command_line = ["claude"] + (["--model", model] if model else []) + (["--effort", effort] if effort else []) + ["--cloud", task]
        # Under `script` the CLI gets a full terminal including a
        # controlling one (a bare pty has none, and a program that opens
        # /dev/tty then fails); without `script` it just gets the pty.
        run = ["script", "-q", "-e", "-c", shlex.join(command_line), "/dev/null"] if shutil.which("script") else command_line
        code, output, reason, timeline = _run_with_pty(run, directory, 180, stop_after_session_id=True)
        timed_out = reason == "timeout"
        _log_cloud("create", run, code, output, timeline)
        ids = CLOUD_ID_RE.findall(output)
        found = next((i for i in ids if i.startswith("session_")), ids[0] if ids else None)
        url_match = CLOUD_URL_RE.search(output)
        url = url_match.group(0) if url_match else None
        if found is None and url:
            from_url = CLOUD_ID_RE.search(url)
            found = from_url.group(0) if from_url else None
        print(f"[{_ts()}] cloud_send: created={found!r} code={code} {timeline[-1]}", flush=True)
        if repo and found and re.search(r"bundl", output, re.I):
            # Documented: without access to the GitHub remote, Claude Code
            # uploads the local directory instead of cloning — here an empty
            # stub or a stale clone. Unverified wording, hence the hedge.
            github_error = (
                f"La session semble avoir reçu une copie locale au lieu de cloner {repo} : "
                "Claude n'a peut-être pas accès à ce dépôt (autorise-le sur claude.ai/connect-github)."
            )
        if found is None:
            reason = "délai dépassé" if timed_out else f"aucun identifiant de session trouvé (code {code})"
            return {"ok": False, "error": reason + " — sortie : " + output.strip()[-600:]}
        with lock:
            current_cloud = last_cloud = found
            _save_state()
        _save_cloud_session(found, title, url, repo=repo, work=work, base=base, direct=direct_now if repo else None, instr=fingerprint, model=model or "", effort=effort or "")
        threading.Thread(target=_rename_session, args=(found, title), daemon=True).start()
        return {"ok": True, "session_id": found, "url": url}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "délai dépassé"}
    except (OSError, subprocess.CalledProcessError) as err:
        return {"ok": False, "error": str(err)}
    finally:
        with lock:
            cloud_busy = False


def _site_probe(url):
    answers = github.site_answers(url)
    with site_lock:
        site_cache.update(url=url, state="live" if answers else "waiting", at=time.time(), busy=False)


def _site_snapshot():
    """The GitHub Pages address of the current repository — known in
    advance (https://<owner>.github.io/<repo>/) — and whether it answers:
    off (no repository), waiting or live. Probed in the background; this is
    called on every /api/status."""
    with lock:
        repo = current_repo
    if not repo:
        return {"url": None, "state": "off"}
    url = github.pages_url(repo)
    with site_lock:
        fresh = site_cache["url"] == url
        state = site_cache["state"] if fresh else "waiting"
        ttl = 120 if state == "live" else 30
        if (not fresh or time.time() - site_cache["at"] > ttl) and not site_cache["busy"]:
            site_cache["busy"] = True
            threading.Thread(target=_site_probe, args=(url,), daemon=True).start()
    return {"url": url, "state": state}


def _ci_probe(repo):
    info = github.latest_run(repo)
    with site_lock:
        # A failed lookup (rate limit, private repository) keeps what was known.
        ci_cache.update(repo=repo, info=info if info is not None else (ci_cache["info"] if ci_cache["repo"] == repo else None), at=time.time(), busy=False)


def _ci_snapshot():
    """The latest GitHub Actions run of the current repository ({"state",
    "url", "fresh"}, or None when unknown). Public data, looked up every
    150 s (40 s while a run is going) — the unauthenticated API allows only
    60 requests an hour. fresh: the run is green and is a new one the user
    has not been told about yet, i.e. there is something to go and look at
    (the first run ever seen is taken as already known)."""
    with lock:
        repo = current_repo
    if not repo:
        return None
    with site_lock:
        known = ci_cache["repo"] == repo
        info = ci_cache["info"] if known else None
        ttl = 40 if info and info.get("state") == "running" else 150
        if (not known or time.time() - ci_cache["at"] > ttl) and not ci_cache["busy"]:
            ci_cache["busy"] = True
            threading.Thread(target=_ci_probe, args=(repo,), daemon=True).start()
    if not info:
        return None
    result = dict(info, fresh=False)
    if info.get("state") == "success" and info.get("id") is not None:
        with lock:
            seen = ci_seen.get(repo)
            if seen is None:
                ci_seen[repo] = info["id"]
                _save_state()
            else:
                result["fresh"] = seen != info["id"]
    return result


def _ci_acknowledge():
    """The user went to look: this run is no longer news."""
    with lock:
        repo = current_repo
    with site_lock:
        info = ci_cache["info"] if ci_cache["repo"] == repo else None
    if repo and info and info.get("id") is not None:
        with lock:
            ci_seen[repo] = info["id"]
            _save_state()


def _repo_choices():
    """Known repositories first (most recently used), then the ones a
    connected `gh` knows about, if there is one."""
    seen, choices = set(), []

    def offer(item):
        if item["name"] not in seen:
            seen.add(item["name"])
            choices.append(item)

    known = _known_repos()
    for name in known:
        offer({"name": name, "private": False})
    # Discovered on their own: the public repositories of the owner of the
    # checkout Aiwa came from, and of the owners of repositories already used.
    owners = []
    for owner in [github.checkout_owner()] + [name.split("/")[0] for name in known]:
        if owner and owner not in owners:
            owners.append(owner)
    for owner in owners[:3]:
        for item in github.owner_repos(owner):
            offer(item)
    for item in github.gh_repos():
        offer(item)
    return choices


def _ping_seen(text):
    with waiting_lock:
        waiting["since"] = waiting["last_ping"] = time.time()
    print(f"[{_ts()}] alert received from the relay: {text[:40]!r}", flush=True)


def _relay_listener():
    """Listens to the relay topic Claude pings when it waits for an answer.
    Forever, reconnecting with a growing pause: an unreachable relay just
    means no alert."""
    backoff, since = 5, str(int(time.time()))
    while True:
        try:
            request = urllib.request.Request(f"{NTFY_SERVER}/{waiting_topic}/json?since={since}", headers={"User-Agent": "aiwa"})
            with urllib.request.urlopen(request, timeout=90) as reply:
                backoff = 5
                for raw in reply:
                    try:
                        event = json.loads(raw)
                    except ValueError:
                        continue
                    if event.get("id"):
                        since = event["id"]
                    if event.get("event") == "message":
                        _ping_seen(str(event.get("message", "")))
        except (OSError, ValueError):
            pass
        time.sleep(backoff)
        backoff = min(backoff * 2, 60)


def _clear_waiting():
    with waiting_lock:
        waiting["since"] = None


def _relay_test():
    """A ping sent by Aiwa itself: proves the phone side (relay reachable
    and listened to) — not that Claude's session may reach the relay."""
    request = urllib.request.Request(f"{NTFY_SERVER}/{waiting_topic}", data=b"test", method="POST", headers={"User-Agent": "aiwa"})
    with urllib.request.urlopen(request, timeout=10):
        pass


class Handler(BaseHTTPRequestHandler):
    def reply_json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/status":
            with lock:
                model, cloud_session = current_model, current_cloud
                repo, direct, problem = current_repo, push_main, github_error
                deploy, own, effort = autodeploy, extra, current_effort
            with waiting_lock:
                is_waiting, last_ping = waiting["since"] is not None, waiting["last_ping"]
            self.reply_json({
                "version": BACKEND_VERSION, "model": model, "effort": effort, "cloud_session": cloud_session,
                "last_session": last_cloud,
                "repo": repo, "push_main": direct, "autodeploy": deploy, "extra": own,
                "waiting": is_waiting, "alert_last": last_ping,
                "site": _site_snapshot(), "ci": _ci_snapshot(), "github_error": problem,
            })
        elif self.path == "/api/cloud/sessions":
            self.reply_json(_load_cloud_sessions())
        elif self.path == "/api/github/repos":
            self.reply_json({"ok": True, "repos": _repo_choices()})
        elif self.path == "/api/instructions":
            self.reply_json({"text": _preview()})
        else:
            self.send_error(404)

    def do_POST(self):
        global current_model, current_cloud, current_repo, push_main, autodeploy, extra, current_effort, last_cloud
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "invalid Content-Length")
            return
        body = self.rfile.read(length).decode()
        if self.path == "/api/cloud/message":
            self.reply_json(cloud_send(body))
        elif self.path == "/api/cloud/command":
            self.reply_json(cloud_send(body, command=True))
        elif self.path == "/api/cloud/select":
            # "new" = the next message creates a session; otherwise the id
            # of one of /api/cloud/sessions.
            target = body.strip()
            if target != "new" and not CLOUD_ID_RE.fullmatch(target):
                self.reply_json({"accepted": False, "reason": "invalid cloud session"})
                return
            _clear_waiting()
            with lock:
                current_cloud = None if target == "new" else target
                if target != "new":
                    last_cloud = target
                    # A session belongs to the repository it was started on,
                    # and keeps the model / effort last asked for it.
                    entry = _session_entry(target)
                    current_repo = entry.get("repo")
                    if "model" in entry:
                        current_model = entry["model"] or None
                    if "effort" in entry:
                        current_effort = entry["effort"] or None
                _save_state()
            self.reply_json({"accepted": True, "cloud_session": current_cloud})
        elif self.path == "/api/cloud/add":
            added, why_not = cloud_add(body)
            if added is None:
                self.reply_json({"accepted": False, "reason": why_not})
            else:
                self.reply_json({"accepted": True, "cloud_session": added})
        elif self.path == "/api/model":
            requested = body.strip()
            if requested and not MODEL_RE.fullmatch(requested):
                self.reply_json({"accepted": False, "reason": "invalid model"})
                return
            with lock:
                current_model = requested or None
                session = current_cloud
                _save_state()
            if session:
                _update_session(session, model=requested)
            self.reply_json({"accepted": True, "model": current_model})
        elif self.path == "/api/effort":
            # "" = automatic. Passed to `claude --effort` when a new cloud
            # session is created; the app also sends /effort to the open one.
            requested = body.strip()
            if requested and requested not in EFFORT_LEVELS:
                self.reply_json({"accepted": False, "reason": "invalid effort"})
                return
            with lock:
                current_effort = requested or None
                session = current_cloud
                _save_state()
            if session:
                _update_session(session, effort=requested)
            self.reply_json({"accepted": True, "effort": current_effort})
        elif self.path == "/api/ci/seen":
            _ci_acknowledge()
            self.reply_json({"accepted": True})
        elif self.path == "/api/waiting/clear":
            _clear_waiting()
            self.reply_json({"accepted": True})
        elif self.path == "/api/waiting/test":
            try:
                _relay_test()
                self.reply_json({"accepted": True})
            except OSError as err:
                self.reply_json({"accepted": False, "reason": f"relais injoignable : {err}"})
        elif self.path == "/api/repo":
            # "" = the plain chat. Changing repository means the next
            # message starts a NEW session: a session's repository is fixed
            # when it starts.
            requested = body.strip()
            if requested and not github.REPO_RE.fullmatch(requested):
                self.reply_json({"accepted": False, "reason": "invalid repository"})
                return
            with lock:
                current_repo = requested or None
                current_cloud = None
                _save_state()
            if requested:
                _remember_repo(requested)
            self.reply_json({"accepted": True, "repo": current_repo})
        elif self.path == "/api/github/add":
            # A repository given as a GitHub link or owner/name (copied from
            # the browser or the Claude app): remembered, and selected.
            added = github.parse_repo(body)
            if added is None:
                self.reply_json({"accepted": False, "reason": "no GitHub repository in that text"})
                return
            _remember_repo(added)
            with lock:
                current_repo = added
                current_cloud = None
                _save_state()
            self.reply_json({"accepted": True, "repo": added})
        elif self.path == "/api/options":
            try:
                options = json.loads(body or "{}")
            except ValueError:
                options = None
            if not isinstance(options, dict):
                self.reply_json({"accepted": False, "reason": "invalid options"})
                return
            retarget = None
            with lock:
                if isinstance(options.get("push_main"), bool):
                    if options["push_main"] != push_main and current_cloud:
                        retarget = current_cloud
                    push_main = options["push_main"]
                if isinstance(options.get("autodeploy"), bool):
                    autodeploy = options["autodeploy"]
                if isinstance(options.get("extra"), str):
                    extra = options["extra"].strip()[:EXTRA_MAX]
                _save_state()
            if retarget:
                # The session in progress follows the switch from its next message.
                _update_session(retarget, direct=push_main)
            self.reply_json({"accepted": True})
        else:
            self.send_error(404)


if __name__ == "__main__":
    _load_state()
    threading.Thread(target=_relay_listener, daemon=True).start()
    print(f"Aiwa backend listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
