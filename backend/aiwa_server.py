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
BACKEND_VERSION = 16
# Passed to `claude --model` when a new cloud session is created, and to
# `/model` in an existing one. Kept restrictive: it ends up as a
# command-line argument / slash-command argument.
MODEL_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}")
EXTRA_MAX = 600  # the user's own instruction text
# The effort levels `/effort` and `claude --effort` accept (None = automatic).
EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")
# A public relay Claude pings when it waits for an answer (see _compose). It is
# also how a sphere and the relay test come back to the phone: Claude's replies
# can't be read by a program, but a command it runs in the cloud can reach the relay.
NTFY_SERVER = "https://ntfy.sh"
# Where the spheres received from Claude are kept (the last ten), and the limits of one.
SPHERE_DIR = Path.home() / ".aiwa_spheres"
SPHERE_MAX = 2 * 1024 * 1024
SPHERE_KEEP = 10
# YourMine's own file naming: name.sphere.js, and the key in window.YM_S[...] is the same.
SPHERE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,40}\.sphere\.js")
SPHERE_KEY_RE = re.compile(r"YM_S\[\s*['\"]([^'\"]+\.sphere\.js)['\"]\s*\]")
SPHERE_README = "https://raw.githubusercontent.com/theodoreyong9/YourMinedApp/main/README.md"
# The Aiwa counterpart: what Claude writes in the "aiwa" mode is a contract, one self-contained
# index.html, named name.aiwa.html here (the wallet page asks for a name, a version and the code).
AIWA_FILE_RE = re.compile(r"[a-z0-9][a-z0-9-]{0,40}\.aiwa\.html")
AIWA_PROJECT = "https://raw.githubusercontent.com/theodoreyong9/aiwa_project/main"
HTML_TITLE_RE = re.compile(r"<title[^>]*>([^<]{1,200})</title>", re.I)
# The page the CLI prints to log in with a Claude account (inside terminal escape codes).
LOGIN_URL_RE = re.compile(r"https://claude\.(?:com|ai)/[^\s\x07\x1b]*oauth/authorize[^\s\x07\x1b]*")
# What the CLI says when it is not (or no longer) logged in.
LOGIN_NEEDED_RE = re.compile(
    r"not logged in|run /login|login expired|unable to get organization uuid|api key authentication|not authenticated|authentication (?:failed|required|error)",
    re.I,
)
# How long Claude gets to answer the relay test before the widget says it is missing.
CLOUD_CHECK_WAIT = 240

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
# to a work branch); deploy_mode: none, pages (publish with GitHub Pages
# through GitHub Actions), android (build the APK with GitHub Actions and
# publish it as a GitHub release), sphere (write a YourMine sphere and send it
# to the phone, no GitHub) or aiwa (write an Aiwa contract — one self-contained
# index.html — and send it to the phone, to be published from the Aiwa wallet page);
# extra: free text. The alert instruction (ping the relay when you
# wait for an answer) is always there — it is mandatory, not a switch.
current_repo = None
push_main = True
DEPLOY_MODES = ("none", "pages", "android", "sphere", "aiwa")
deploy_mode = "none"
# Other repositories Claude may ALSO work on (checked in the widget's picker):
# told to it in the instructions; the platform decides whether it can reach them.
extra_repos = []
EXTRA_REPOS_MAX = 8
extra = ""
# None = the CLI's own default effort.
current_effort = None
# The relay topic (a random secret) and whether Claude has pinged it since
# the user last sent a message or opened the session.
waiting_topic = None
waiting_lock = threading.Lock()
waiting = {"since": None, "last_ping": None}
github_error = None  # the last GitHub problem worth showing in the app
# The account the CLI is logged in to: state is unknown / ok / needed, looked up in
# the background (see _login_state). login_flow is the `claude auth login` run the
# app drives from its "Connecter Claude" window: phase idle / starting / url /
# checking / done / failed.
login_lock = threading.Lock()
claude_login = {"state": "unknown", "at": 0.0, "busy": False}
login_flow = {"phase": "idle", "url": None, "message": "", "proc": None, "master": None, "text": "", "mark": 0}
# Whether a command Claude runs in the cloud reaches the relay (the environment's
# network access must allow ntfy.sh): when the test was asked, and when its answer came.
# Persisted: a relay seen working stays confirmed.
relay_cloud = {"asked": None, "ok": None}
# The last sphere Claude sent: {"name", "size", "ts", "seen", "event"}; its source is
# a file of SPHERE_DIR. And the last relay event handled, to resume after a restart.
sphere = None
relay_seen = {"id": None, "time": None}
# Whether the Pages address of current_repo answers, probed in the background.
site_lock = threading.Lock()
site_cache = {"url": None, "state": "off", "at": 0.0, "busy": False}
ci_cache = {"repo": None, "info": None, "at": 0.0, "busy": False}
# repo -> the commit whose Actions runs the user was last told about (persisted): a
# green commit that is not this one is news — "you can go and look".
ci_seen = {}
# repo -> the commit of the last "Prêt" written to the log (once each).
ci_announced = {}
# When the user last sent a message through Aiwa (epoch seconds, persisted): a green
# commit only counts as news if its runs finished after it.
last_message_at = 0
REPOS_STORE = Path.home() / ".aiwa_repos.json"
store_lock = threading.Lock()
# Two `claude -p --cloud <id>` runs never overlap (a send and the /rename
# that follows a creation, for instance).
followup_lock = threading.Lock()


def _load_state():
    global current_model, current_cloud, current_repo, push_main, deploy_mode, extra
    global current_effort, waiting_topic, last_cloud, sphere, last_message_at
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
    mode = data.get("deploy")
    # Older state files only knew a yes/no: yes was GitHub Pages.
    deploy_mode = mode if mode in DEPLOY_MODES else ("pages" if data.get("autodeploy") is True else "none")
    listed = data.get("extra_repos")
    extra_repos[:] = [r for r in listed if isinstance(r, str) and github.REPO_RE.fullmatch(r) and r != current_repo][:EXTRA_REPOS_MAX] if isinstance(listed, list) else []
    text = data.get("extra")
    extra = text.strip()[:EXTRA_MAX] if isinstance(text, str) else ""
    effort = data.get("effort")
    current_effort = effort if effort in EFFORT_LEVELS else None
    seen = data.get("ci_seen")
    ci_seen.clear()
    if isinstance(seen, dict):
        # Commit ids. The run ids an older version kept are dropped: the first look at a
        # repository then takes what is there as already known, instead of one false "Prêt".
        ci_seen.update({k: v for k, v in seen.items() if isinstance(k, str) and isinstance(v, str)})
    sent = data.get("last_message")
    last_message_at = sent if isinstance(sent, int) else 0
    checked = data.get("relay_cloud")
    if isinstance(checked, dict):
        relay_cloud.update({k: checked.get(k) if isinstance(checked.get(k), (int, float)) else None for k in ("asked", "ok")})
    last = data.get("sphere")
    sphere = None
    if isinstance(last, dict) and isinstance(last.get("name"), str) and (SPHERE_NAME_RE.fullmatch(last["name"]) or AIWA_FILE_RE.fullmatch(last["name"])):
        sphere = {"name": last["name"], "size": int(last.get("size") or 0), "ts": int(last.get("ts") or 0),
                  "seen": last.get("seen") is True, "event": last.get("event") if isinstance(last.get("event"), str) else None,
                  "kind": "aiwa" if last["name"].endswith(".aiwa.html") else "sphere"}
    handled = data.get("relay_seen")
    if isinstance(handled, dict):
        relay_seen.update(id=handled.get("id") if isinstance(handled.get("id"), str) else None,
                          time=handled.get("time") if isinstance(handled.get("time"), int) else None)
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
            "push_main": push_main, "deploy": deploy_mode, "extra": extra, "extra_repos": extra_repos,
            "effort": current_effort, "topic": waiting_topic, "last_cloud": last_cloud, "ci_seen": ci_seen,
            "relay_cloud": relay_cloud, "sphere": sphere, "relay_seen": relay_seen, "last_message": last_message_at,
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
        deploy, topic, more = deploy_mode, waiting_topic, list(extra_repos)
    lines = []
    if repo:
        text = f"Dépôt : {repo}. Ton répertoire de travail doit être ce dépôt GitHub (vérifie `git remote -v`) ; si ce n'est pas le cas, dis-le-moi et arrête-toi."
        if more:
            text += (
                " Dépôts supplémentaires sur lesquels tu peux aussi intervenir : " + ", ".join(more) + ". "
                "Ils ne sont pas forcément attachés à ta session : quand tu dois en lire ou en modifier un, rattache-le avec l'outil `add_repo` "
                "(accès `push` si tu dois y pousser) ; sans cet outil, ou si l'accès est refusé, dis-le-moi et n'insiste pas. "
                "N'interviens sur aucun autre dépôt que ceux-là et celui de ta session. "
                "Les consignes de push et de vérification ci-dessous valent pour chacun d'eux (sur chacun, ta propre branche, jamais de force-push)."
            )
        lines.append(("repo", text))
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
        if deploy == "pages":
            lines.append((
                "deploy",
                f"Déploiement : le site est publié par GitHub Pages via GitHub Actions, à l'adresse {github.pages_url(repo)}. "
                "S'il n'y a pas encore de workflow Pages (actions/configure-pages, upload-pages-artifact, deploy-pages, déclenché à chaque push sur la branche principale), ajoute-le ; pas de branche gh-pages. "
                f"Le déploiement ne se déclenche que par un push sur {base} : tant que ton travail n'y est pas intégré, rien n'est publié. "
                "Si activer Pages avec la source « GitHub Actions » est hors de ta portée, dis-moi précisément le réglage à faire. Après un changement, vérifie que le déploiement a réussi.",
            ))
        elif deploy == "android":
            name = repo.split("/", 1)[1]
            lines.append((
                "deploy",
                "Déploiement (Android) : ce projet est une application Android, dont les APK se téléchargent depuis une release GitHub. "
                f"Ajoute ou maintiens un workflow GitHub Actions qui, à chaque push sur {base}, compile l'APK de debug, le signe avec une clé de debug fixe "
                "commitée dans le dépôt (sinon chaque APK est signé autrement et ne s'installe pas par-dessus le précédent), puis le publie comme fichier "
                f"`{name}.apk` de la release GitHub de tag `{github.APK_TAG}` : une seule release, créée si elle n'existe pas et mise à jour à chaque build "
                "(le fichier est remplacé, le tag déplacé sur le commit construit ; permissions `contents: write`). Ne commite pas l'APK dans le dépôt. "
                f"Il doit être téléchargeable à l'adresse {github.apk_url(repo)}. "
                f"Le déploiement ne se déclenche que par un push sur {base} : tant que ton travail n'y est pas intégré, rien n'est publié. "
                "Si ce dépôt n'est pas un projet Android, dis-le-moi et ne fais rien. Après un changement, vérifie que le build a réussi et lis ses logs.",
            ))
        lines.append((
            "verify",
            "Vérification : avant de dire que c'est fini, contrôle ton travail toi-même et lis TOUS les logs auxquels tu as accès, pas seulement ceux des GitHub Actions : "
            "la sortie des tests, du lint et du build ; les logs du serveur ou du script que tu lances ; après un push, les logs des GitHub Actions et des déploiements (jobs en échec compris) ; "
            "la console du navigateur (erreurs, avertissements, exceptions non gérées), les requêtes réseau en échec ou en erreur HTTP et les erreurs de page, que tu récupères avec Playwright "
            "(page.on('console'), page.on('pageerror'), page.on('requestfailed'), réponses avec un statut >= 400) — Chromium est déjà installé dans ta session, ne lance pas `playwright install` ; "
            "et tout autre journal disponible dans ton environnement. Ne te contente pas de « ça démarre » : cherche les erreurs et les avertissements, et corrige-les avant de conclure. "
            "Si le projet a une interface, contrôle aussi l'UX avec Playwright : en local puis, après un déploiement, à l'adresse publiée ; en mobile (~390 px) puis en bureau ; avec des captures ; "
            "sans débordement ni élément cassé, et les parcours principaux fonctionnels. "
            "Dis-moi ce que tu as lu et vérifié, ce que tu n'as pas pu consulter (logs inaccessibles) et ce qui reste douteux.",
        ))
    if deploy == "sphere":
        # Not tied to a repository: a sphere is written, checked and sent to the phone,
        # where the user reads it in YourMine's publish form and submits it themself.
        lines.append((
            "deploy",
            "Déploiement (sphère YourMine) : le livrable est UNE sphère YourMine, un fichier `nom.sphere.js` "
            "(nom court en minuscules : lettres, chiffres, `_` ou `-`). Ce n'est pas une copie d'un code de départ : c'est une réécriture "
            "au format YourMine (une IIFE qui s'enregistre dans `window.YM_S['nom.sphere.js']`, avec `activate`, `deactivate` et `renderPanel`, "
            f"et tout passe par `ctx`). Ce format est décrit dans {SPHERE_README} : lis-y les sections « Building a Sphere » (dont « Context API ») "
            "et « Profile as Infrastructure » avant d'écrire, même si tu crois connaître le format, et inspire-toi d'une sphère existante listée "
            "dans files.json du même dépôt. Côté profil : implémente `profileSection(container)` (ma fiche : stats, historique, réglages de la sphère) "
            "et `peerSection(container, peerCtx)` (la fiche d'un pair) quand la sphère a quelque chose à y montrer, et `broadcastData()` "
            "(moins de 500 octets) quand elle a un état de présence à partager ; si tu n'en mets pas, dis en une phrase pourquoi. "
            "Ne la pousse sur AUCUN dépôt et n'ouvre aucune pull request : je la relis moi-même dans le formulaire de publication de YourMine, "
            "sur mon téléphone, et c'est moi qui la soumets. Quand elle est prête, vérifie-la (`node --check nom.sphere.js` ; "
            "charge-la dans un navigateur si tu peux), puis envoie-la sur mon téléphone avec cette commande, telle quelle : "
            f"`curl -s -m 60 -T nom.sphere.js -H 'Filename: nom.sphere.js' -H 'Title: aiwa-sphere' {NTFY_SERVER}/{topic}` "
            "(un seul fichier par envoi ; si je te demande une correction, renvoie le fichier complet de la même façon ; "
            "si le réseau bloque la commande, dis-le-moi et colle le code dans ta réponse). Dis-moi ensuite en une phrase ce que fait la sphère et ce qu'elle montre sur les profils.",
        ))
    if deploy == "aiwa":
        lines.append((
            "deploy",
            "Déploiement (Aiwa) : le livrable est UNE app Aiwa, un contrat : un fichier `nom.aiwa.html` (nom en minuscules, chiffres et tirets), "
            "un `index.html` complet et autonome, publié par le wallet Aiwa avec mon identité (il devient alors immuable : signé, identifié par son hash). Il doit aussi tourner comme sphère YourMine : "
            "une fois publié, la page Aiwa génère elle-même une sphère qui ne contient AUCUNE copie du code et n'exécute que ce contrat publié, vérifié. Tu n'écris donc PAS de `.sphere.js` — "
            "mais tu écris le contrat pour qu'il tourne aux deux endroits. La forme, illustrée par un exemple qui marche "
            "(lis-le en entier avant d'écrire) : https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/docs/aiwa-contract-example.html : "
            "(1) une import map pour les dépendances ; tout import de la logique utilise un nom de cette carte ou une URL ABSOLUE, jamais un chemin relatif ; "
            "(2) un `<style>` dont CHAQUE règle commence par la classe racine `.aiwa-` + le nom du fichier (aucune règle sur body, html, * ou :root) ; "
            "(3) dans le corps, seulement `<div id=\"app\" class=\"aiwa-nom\">` et UN `<script type=\"module\" id=\"aiwa-logic\">` en ligne qui construit toute l'interface dans le conteneur reçu ; "
            "(4) `export function mount(container, host)` (ou async) : uniquement `container.querySelector`, jamais `document.getElementById` ni d'identifiant global, aucun état sur `window`, "
            "et elle RETOURNE une fonction qui défait ce qu'elle a fait (minuteurs, écouteurs) ; `host.kind` vaut \"aiwa\" sur la page du wallet et \"yourmine\" dans la sphère "
            "(alors `host.ctx` est le contexte de sphère de YourMine — stockage, p2p, profil — à utiliser avec `?.`, absent sur la page Aiwa) ; "
            "(5) dernière ligne : `if (!globalThis.__AIWA_HOST__) mount(document.getElementById('app'), { kind: 'aiwa' });` ; "
            "(6) un `<title>`, un `<meta name=\"description\">` (moins de 120 caractères, sans accolades ni `$`) et un `<meta name=\"aiwa-icon\">` (un emoji) : ils deviennent le nom, la description et l'icône de la sphère. "
            f"Lis aussi, pour ce que le contrat peut faire : le README {AIWA_PROJECT}/README.md (section « Publish a contract »), l'exemple {AIWA_PROJECT}/examples/channel-contract.html "
            f"et le yellow paper {AIWA_PROJECT}/YELLOWPAPER.md (le protocole : identité, journal d'événements, contrats, délégation, bons au porteur) ; "
            "ce que le contrat fait avec aiwa-lib (`defineContract`, `Contract`, `signedAction`…) doit correspondre à ce que ces documents décrivent, pas à ce que tu supposes. "
            "Les profils de YourMine (`profileSection`, `peerSection`, `broadcastData`) ne sont pas dans ce contrat : si je les demande, dis-moi qu'il faut une vraie sphère. "
            "Ne le pousse sur AUCUN dépôt : je le publie moi-même, depuis la page du wallet Aiwa, qui me donne ensuite la sphère pour YourMine. Quand il est prêt, vérifie-le (charge-le dans un navigateur headless, "
            "console sans erreur ; les modules de github.io peuvent être inaccessibles depuis ta session : dis-le-moi alors, et ce que tu as pu vérifier quand même), puis envoie-le sur mon téléphone "
            f"avec cette commande, telle quelle : `curl -s -m 60 -T nom.aiwa.html -H 'Filename: nom.aiwa.html' -H 'Title: aiwa-app' {NTFY_SERVER}/{topic}` "
            "(un seul fichier par envoi ; si je te demande une correction, renvoie le fichier complet de la même façon ; "
            "si le réseau bloque la commande, dis-le-moi et colle le code dans ta réponse). Dis-moi ensuite en une phrase ce que fait l'app.",
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
    # No free-text line any more: its only editor was in the app's settings card,
    # removed when the app became a bare text field. A leftover text there would
    # go on being sent with nothing left to show or clear it; a note to Claude is
    # now simply written as a message.
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
                _note_login_problem(result["error"])
                return {"ok": False, "error": result["error"]}
            _set_login_state("ok")
            if not command:
                _note_message_sent()
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
        with lock:
            asked_before = relay_cloud["asked"]
        check_text = _relay_check_text()
        task = text + extra_text + check_text
        # Claude's own title for the session is made from this first message: the
        # name Aiwa shows is written in it, besides the /rename queued after the
        # creation (which the CLI accepts but whose execution can't be checked).
        task += f"\n\n[Aiwa] Nom de cette session, tel qu'Aiwa l'affiche : « {title} ». Utilise exactement ce nom comme titre de la session."
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
            _note_login_problem(output)
            if check_text:
                with lock:  # no session was created: the relay test was not asked after all
                    relay_cloud["asked"] = asked_before
                    _save_state()
            return {"ok": False, "error": reason + " — sortie : " + output.strip()[-600:]}
        _set_login_state("ok")
        _note_message_sent()
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


def _site_probe(url, ranged=False):
    answers = github.site_answers(url, ranged=ranged)
    with site_lock:
        site_cache.update(url=url, state="live" if answers else "waiting", at=time.time(), busy=False)


def _site_snapshot():
    """The address the user can open once a repository is chosen — known in
    advance, and whether it answers: off (no repository), waiting or live.
    kind "site": the GitHub Pages address (https://<owner>.github.io/<repo>/);
    kind "apk": with the Android mode, the download address of the APK in the
    rolling release; kind "sphere" / "aiwa": no address, "live" once what Claude
    sent for that mode has not been opened yet. Probed in the background; this is called
    on every /api/status."""
    with lock:
        repo, mode, last = current_repo, deploy_mode, sphere
    if mode in ("sphere", "aiwa"):
        # What was sent must be what this mode asked for (a sphere is no answer to "aiwa").
        return {"url": None, "state": "live" if last and not last["seen"] and last["kind"] == mode else "waiting", "kind": mode}
    if not repo:
        return {"url": None, "state": "off", "kind": "site"}
    kind = "apk" if mode == "android" else "site"
    url = github.apk_url(repo) if kind == "apk" else github.pages_url(repo)
    with site_lock:
        fresh = site_cache["url"] == url
        state = site_cache["state"] if fresh else "waiting"
        ttl = 120 if state == "live" else 30
        if (not fresh or time.time() - site_cache["at"] > ttl) and not site_cache["busy"]:
            site_cache["busy"] = True
            threading.Thread(target=_site_probe, args=(url, kind == "apk"), daemon=True).start()
    return {"url": url, "state": state, "kind": kind}


def _ci_probe(repo):
    info = github.latest_run(repo)
    with site_lock:
        # A failed lookup (rate limit, private repository) keeps what was known.
        ci_cache.update(repo=repo, info=info if info is not None else (ci_cache["info"] if ci_cache["repo"] == repo else None), at=time.time(), busy=False)


def _ci_snapshot():
    """The verdict on the latest commit of the current repository's GitHub Actions ({"state",
    "url", "detail", "fresh"}, or None when unknown): see aiwa_github.latest_run. Public
    data, looked up every 150 s (40 s while runs are going) — the unauthenticated API allows
    only 60 requests an hour.

    fresh: there is something to go and look at — the commit's runs have all finished green,
    it is not the one the user was last told about, and they finished after the user's last
    message through Aiwa (work nobody asked for, like a collaborator's push, is no news). The
    first commit ever seen is taken as already known."""
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
    sha = info.get("sha")
    if info.get("state") == "success" and sha:
        with lock:
            seen = ci_seen.get(repo)
            if seen is None:
                ci_seen[repo] = sha
                _save_state()
            elif seen != sha:
                result["fresh"] = (info.get("done") or 0) >= last_message_at
        if result["fresh"] and ci_announced.get(repo) != sha:
            ci_announced[repo] = sha
            print(f"[{_ts()}] CI news for {repo}: {info.get('detail')} ({sha[:7]})", flush=True)
    return result


def _ci_acknowledge():
    """The user went to look: this commit is no longer news."""
    with lock:
        repo = current_repo
    with site_lock:
        info = ci_cache["info"] if ci_cache["repo"] == repo else None
    if repo and info and info.get("sha"):
        with lock:
            ci_seen[repo] = info["sha"]
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


# ---- The Claude account the CLI is logged in to ------------------------------

def _claude_auth_status():
    """Whether the CLI is logged in (True / False), None when it can't be told."""
    try:
        done = subprocess.run(["claude", "auth", "status", "--json"], capture_output=True, text=True, timeout=25)
    except (OSError, subprocess.SubprocessError):
        return None
    text = (done.stdout or "").strip()
    try:
        data = json.loads(text[text.index("{"):text.rindex("}") + 1])
    except ValueError:
        return None
    return data.get("loggedIn") is True if isinstance(data, dict) else None


def _set_login_state(state):
    with login_lock:
        claude_login.update(state=state, at=time.time())


def _note_message_sent():
    global last_message_at
    with lock:
        last_message_at = int(time.time())
        _save_state()


def _note_login_problem(output):
    """A failed send whose text says "log in again" makes the widget ask for it."""
    if isinstance(output, str) and LOGIN_NEEDED_RE.search(output):
        _set_login_state("needed")


def _login_probe():
    logged = _claude_auth_status()
    with login_lock:
        claude_login["busy"] = False
        claude_login.update(at=time.time(), **({"state": "ok" if logged else "needed"} if logged is not None else {}))


def _login_state():
    """ok / needed / unknown. Looked up in the background — starting `claude` is not
    free on a phone — every ten minutes, every twenty seconds while it is 'needed'
    (so the widget clears soon after a login made somewhere else)."""
    with login_lock:
        state, age = claude_login["state"], time.time() - claude_login["at"]
        if not claude_login["busy"] and age > (20 if state == "needed" else 15 if state == "unknown" else 600):
            claude_login["busy"] = True
            threading.Thread(target=_login_probe, daemon=True).start()
        return state


def _login_snapshot():
    with login_lock:
        return {"phase": login_flow["phase"], "url": login_flow["url"], "message": login_flow["message"]}


_OSC_RE = re.compile(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")


def _login_lines(text):
    """The readable lines of the CLI's screen (no escape codes, no login address)."""
    lines = [re.sub(r"^Paste code here if prompted\s*>\s*", "", l.strip()) for l in github.clean(_OSC_RE.sub("", text)).splitlines()]
    return [l for l in lines if l and "oauth/authorize" not in l]


def _login_reader(proc, master):
    """Reads what `claude auth login` prints: the address to open, then the outcome.
    A refused code makes the CLI print "Login failed" and exit (seen with the real
    CLI): a new login is then needed. Should it stay and ask again, the user may paste
    another code after a few seconds."""
    chunks = []
    refused_at = None
    while True:
        try:
            ready, _, _ = select.select([master], [], [], 1.0)
        except (OSError, ValueError):
            break
        if ready:
            try:
                data = os.read(master, 4096)
            except OSError:
                break
            if not data:
                break
            chunks.append(data)
            text = b"".join(chunks).decode("utf-8", "replace")
            with login_lock:
                if login_flow["proc"] is not proc:
                    return
                login_flow["text"] = text
                if login_flow["url"] is None:
                    found = LOGIN_URL_RE.search(text)
                    if found:
                        login_flow.update(url=found.group(0), phase="url")
                fresh = text[login_flow["mark"]:] if login_flow["phase"] == "checking" else ""
                if fresh and re.search(r"login successful|logged in as|successfully logged", fresh, re.I):
                    login_flow.update(phase="done", message="Connecté à Claude.")
                    claude_login.update(state="ok", at=time.time())
                elif fresh and re.search(r"invalid|expired|failed|error|denied|incorrect", fresh, re.I):
                    lines = _login_lines(fresh)
                    login_flow["message"] = (lines[-1] if lines else "Code refusé.")[:300]
                    refused_at = refused_at or time.time()
        elif proc.poll() is not None:
            break
        if refused_at is not None and proc.poll() is None and time.time() - refused_at > 3:
            with login_lock:
                if login_flow["proc"] is proc and login_flow["phase"] == "checking":
                    login_flow["phase"] = "url"
            refused_at = None
    code = proc.wait()
    with login_lock:
        if login_flow["proc"] is not proc:
            return
        if login_flow["phase"] != "done":
            if code == 0:
                login_flow.update(phase="done", message="Connecté à Claude.")
                claude_login.update(state="ok", at=time.time())
            else:
                lines = _login_lines(login_flow["text"])
                login_flow.update(phase="failed", message=login_flow["message"] or (lines[-1] if lines else f"le CLI s'est arrêté (code {code})")[:300])
        login_flow.update(proc=None, master=None)
    try:
        os.close(master)
    except OSError:
        pass


def _login_cancel():
    with login_lock:
        proc = login_flow["proc"]
        login_flow.update(phase="idle", url=None, message="", proc=None, master=None, text="", mark=0)
    if proc is not None and proc.poll() is None:
        _signal_group(proc, signal.SIGTERM)


def _login_start():
    """Starts `claude auth login` in a terminal of its own and waits for the address
    it prints. The user opens it in a browser, approves, and gets a CODE to paste
    (the redirect goes to platform.claude.com, not to the phone)."""
    _login_cancel()
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 4000, 0, 0))  # wide: the address must not wrap
    # A token given in the environment would win over the login: not for this one.
    env = {k: v for k, v in os.environ.items() if k not in ("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    env.update(TERM="xterm-256color", BROWSER="true")
    try:
        proc = subprocess.Popen(["claude", "auth", "login", "--claudeai"], env=env, stdin=slave, stdout=slave, stderr=slave, close_fds=True, start_new_session=True)
    except OSError as err:
        os.close(master)
        os.close(slave)
        with login_lock:
            login_flow.update(phase="failed", message=f"claude est introuvable : {err}")
        return _login_snapshot()
    os.close(slave)
    with login_lock:
        login_flow.update(phase="starting", url=None, message="", proc=proc, master=master, text="", mark=0)
    threading.Thread(target=_login_reader, args=(proc, master), daemon=True).start()
    deadline = time.time() + 25
    while time.time() < deadline:
        snapshot = _login_snapshot()
        if snapshot["phase"] in ("url", "failed", "done"):
            return snapshot
        time.sleep(0.2)
    return dict(_login_snapshot(), message="Le CLI n'a pas donné d'adresse de connexion (25 s).")


def _login_code(code):
    """Gives the code shown by the login page to the waiting CLI and waits for the verdict."""
    code = (code or "").strip()
    if not code or len(code) > 4000 or re.search(r"\s", code):
        return dict(_login_snapshot(), message="Ce n'est pas un code de connexion (une seule suite de caractères, sans espace).")
    with login_lock:
        proc, master = login_flow["proc"], login_flow["master"]
        ready = proc is not None and proc.poll() is None and login_flow["phase"] == "url"
        if ready:
            login_flow.update(phase="checking", message="", mark=len(login_flow["text"]))
    if not ready:
        return dict(_login_snapshot(), message="Aucune connexion en cours : ouvre d'abord la page de connexion.")
    try:
        os.write(master, code.encode() + b"\r")
    except OSError as err:
        with login_lock:
            login_flow.update(phase="failed", message=f"impossible de donner le code au CLI : {err}")
        return _login_snapshot()
    deadline = time.time() + 45
    while time.time() < deadline:
        snapshot = _login_snapshot()
        if snapshot["phase"] in ("done", "failed") or (snapshot["phase"] == "url" and snapshot["message"]):
            return snapshot
        time.sleep(0.25)
    return dict(_login_snapshot(), message="Pas de réponse du CLI après 45 s.")


# ---- What comes back through the relay ---------------------------------------

def _relay_check_text(force=False):
    """The test that tells whether Claude's cloud environment can reach the relay
    (its network access must allow ntfy.sh — a setting only claude.ai can change):
    added to the first message of a NEW session while the relay is unconfirmed, and
    sent on its own by the app's "Retester". "" when already confirmed."""
    with lock:
        if relay_cloud["ok"] is not None and not force:
            return ""
        relay_cloud.update(asked=time.time(), ok=None)
        topic = waiting_topic
        _save_state()
    return (
        "\n\n[Aiwa] Test du relais, à faire tout de suite avant le reste : exécute "
        f"`curl -s -m 8 -H 'Title: aiwa-check' -d ok {NTFY_SERVER}/{topic}` puis continue normalement ; "
        "si le réseau la bloque, ignore l'erreur et ne t'y attarde pas."
    )


def _relay_cloud_state():
    """ok (a command of Claude's reached the relay), pending (asked a moment ago),
    missing (asked, nothing came: the environment probably blocks ntfy.sh) or untested."""
    with lock:
        asked, ok = relay_cloud["asked"], relay_cloud["ok"]
    if ok is not None:
        return "ok"
    if asked is None:
        return "untested"
    return "pending" if time.time() - asked < CLOUD_CHECK_WAIT else "missing"


def _cloud_check_seen():
    with lock:
        first = relay_cloud["ok"] is None
        relay_cloud["ok"] = time.time()
        _save_state()
    if first:
        print(f"[{_ts()}] the cloud environment reaches the relay", flush=True)


def _sphere_name(name, code):
    """The sphere's file name: the one it was sent under, else the key it registers."""
    if SPHERE_NAME_RE.fullmatch(name or ""):
        return name
    found = SPHERE_KEY_RE.search(code)
    if found and SPHERE_NAME_RE.fullmatch(found.group(1)):
        return found.group(1)
    return "sphere.sphere.js"


def _slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:40].strip("-")


def _aiwa_name(name, code):
    """The Aiwa contract's file name: the one it was sent under, else its <title>."""
    if AIWA_FILE_RE.fullmatch(name or ""):
        return name
    found = HTML_TITLE_RE.search(code)
    slug = _slug(found.group(1)) if found else ""
    return f"{slug or 'app'}.aiwa.html"


def _sphere_received(event, kind="sphere"):
    """A sphere (kind "sphere": `curl -T name.sphere.js … Title: aiwa-sphere`) or an Aiwa
    contract (kind "aiwa": `curl -T name.aiwa.html … Title: aiwa-app`) Claude sent: ntfy
    turns the file into an attachment, which is downloaded here and kept."""
    global sphere
    attachment = event.get("attachment") if isinstance(event.get("attachment"), dict) else None
    try:
        if attachment:
            url = str(attachment.get("url") or "")
            if not url.startswith(NTFY_SERVER + "/file/"):
                raise ValueError("adresse de pièce jointe inattendue")
            if isinstance(attachment.get("size"), int) and attachment["size"] > SPHERE_MAX:
                raise ValueError("sphère trop grosse")
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "aiwa"}), timeout=30) as reply:
                raw = reply.read(SPHERE_MAX + 1)
            if len(raw) > SPHERE_MAX:
                raise ValueError("sphère trop grosse")
            code, sent_name = raw.decode("utf-8"), str(attachment.get("name") or "")
        else:
            code, sent_name = str(event.get("message") or ""), ""
        if not code.strip():
            raise ValueError("sphère vide")
    except (OSError, ValueError) as err:  # UnicodeDecodeError is a ValueError
        print(f"[{_ts()}] sphere from the relay not kept: {err}", flush=True)
        return
    name = _aiwa_name(sent_name, code) if kind == "aiwa" else _sphere_name(sent_name, code)
    try:
        SPHERE_DIR.mkdir(parents=True, exist_ok=True)
        (SPHERE_DIR / name).write_text(code, encoding="utf-8")
        for pattern in ("*.sphere.js", "*.aiwa.html"):
            for old in sorted(SPHERE_DIR.glob(pattern), key=lambda f: f.stat().st_mtime, reverse=True)[SPHERE_KEEP:]:
                old.unlink()
    except OSError as err:
        print(f"[{_ts()}] sphere could not be saved: {err}", flush=True)
        return
    with lock:
        sphere = {"name": name, "size": len(code.encode("utf-8")), "ts": int(time.time()), "seen": False, "event": event.get("id"), "kind": kind}
        _save_state()
    print(f"[{_ts()}] {kind} received from the relay: {name} ({len(code)} chars)", flush=True)


def _sphere_snapshot():
    with lock:
        return {k: sphere[k] for k in ("name", "size", "ts", "seen", "kind")} if sphere else None


def _sphere_code():
    with lock:
        last = dict(sphere) if sphere else None
    if last is None:
        return {"ok": False, "error": "aucune sphère reçue"}
    try:
        code = (SPHERE_DIR / last["name"]).read_text(encoding="utf-8")
    except OSError:
        return {"ok": False, "error": "le fichier de la sphère est introuvable"}
    return {"ok": True, "name": last["name"], "code": code, "kind": last["kind"]}


def _sphere_seen():
    with lock:
        if sphere:
            sphere["seen"] = True
            _save_state()


def _relay_event(event):
    """One message of the relay topic: a sphere (title aiwa-sphere, or a *.sphere.js
    attachment), an Aiwa contract (title aiwa-app, or a *.aiwa.html attachment), the relay
    test (title aiwa-check) or Claude's "I wait for you" ping."""
    now = time.time()
    with lock:
        if event.get("id") and event.get("id") == relay_seen["id"]:
            return  # the last one, replayed after a restart
        relay_seen.update(id=event.get("id"), time=event["time"] if isinstance(event.get("time"), int) else int(now))
        _save_state()
    title = str(event.get("title") or "")
    attachment = event.get("attachment")
    attached = str(attachment.get("name") or "") if isinstance(attachment, dict) else ""
    kind = "aiwa" if title == "aiwa-app" or attached.endswith(".aiwa.html") else "sphere" if title == "aiwa-sphere" or attached.endswith(".sphere.js") else None
    if title == "aiwa-check":
        _cloud_check_seen()
    elif kind:
        if sphere is None or sphere.get("event") != event.get("id"):
            threading.Thread(target=_sphere_received, args=(event, kind), daemon=True).start()
    else:
        text = str(event.get("message", ""))
        if isinstance(event.get("time"), int) and now - event["time"] > 120:
            return  # a ping replayed long after the fact must not wake the alert again
        _ping_seen(text)
        if text != "test":  # "test" is Aiwa's own ping: it proves the phone side only
            _cloud_check_seen()


def _relay_listener():
    """Listens to the relay topic Claude pings when it waits for an answer, and
    through which its sphere and the relay test come back. Forever, reconnecting
    with a growing pause: an unreachable relay just means no alert."""
    backoff = 5
    with lock:
        since = str(relay_seen["time"]) if relay_seen["time"] else str(int(time.time()))
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
                    if not isinstance(event, dict):
                        continue
                    if event.get("id"):
                        since = event["id"]
                    if event.get("event") == "message":
                        _relay_event(event)
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
                deploy, own, effort = deploy_mode, extra, current_effort
                more = list(extra_repos)
            with waiting_lock:
                is_waiting, last_ping = waiting["since"] is not None, waiting["last_ping"]
            self.reply_json({
                "version": BACKEND_VERSION, "model": model, "effort": effort, "cloud_session": cloud_session,
                "last_session": last_cloud or next((e.get("id") for e in _load_cloud_sessions() if e.get("id")), None),
                "repo": repo, "push_main": direct, "deploy": deploy, "autodeploy": deploy != "none", "extra": own, "extra_repos": more,
                "waiting": is_waiting, "alert_last": last_ping,
                "site": _site_snapshot(), "ci": _ci_snapshot(), "github_error": problem,
                "claude_login": _login_state(), "relay_cloud": _relay_cloud_state(), "sphere": _sphere_snapshot(),
            })
        elif self.path == "/api/cloud/sessions":
            self.reply_json(_load_cloud_sessions())
        elif self.path == "/api/github/repos":
            self.reply_json({"ok": True, "repos": _repo_choices()})
        elif self.path == "/api/instructions":
            self.reply_json({"text": _preview()})
        elif self.path == "/api/claude/login":
            self.reply_json(_login_snapshot())
        elif self.path == "/api/sphere":
            self.reply_json({"sphere": _sphere_snapshot()})
        elif self.path == "/api/sphere/code":
            self.reply_json(_sphere_code())
        else:
            self.send_error(404)

    def do_POST(self):
        global current_model, current_cloud, current_repo, push_main, deploy_mode, extra, current_effort, last_cloud
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
        elif self.path == "/api/claude/login/start":
            self.reply_json(_login_start())
        elif self.path == "/api/claude/login/code":
            self.reply_json(_login_code(body))
        elif self.path == "/api/claude/login/cancel":
            _login_cancel()
            self.reply_json({"accepted": True})
        elif self.path == "/api/claude/check":
            # Asked for by the app's set-up window: a fresh answer, not the cached one.
            logged = _claude_auth_status()
            if logged is not None:
                _set_login_state("ok" if logged else "needed")
            self.reply_json({"accepted": True, "claude_login": _login_state()})
        elif self.path == "/api/relay/retest":
            # Asks the CURRENT session to run the relay test again (a new session gets it
            # with its first message on its own).
            with lock:
                session, before = current_cloud, dict(relay_cloud)
            if not session:
                self.reply_json({"accepted": False, "reason": "aucune session en cours : le test part avec la première réponse d'une nouvelle session"})
                return
            sent = cloud_send(_relay_check_text(force=True).strip(), command=True)
            if sent.get("ok") is not True:
                with lock:
                    relay_cloud.update(before)  # nothing was asked after all
                    _save_state()
            self.reply_json({"accepted": sent.get("ok") is True, "reason": sent.get("error")})
        elif self.path == "/api/sphere/seen":
            _sphere_seen()
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
                if current_repo in extra_repos:
                    extra_repos.remove(current_repo)
                _save_state()
            if requested:
                _remember_repo(requested)
            self.reply_json({"accepted": True, "repo": current_repo})
        elif self.path == "/api/github/extra":
            # Checks or unchecks a repository Claude may ALSO work on ("" = none). Told
            # to it with the next message; whether it can reach the repository is the
            # platform's decision (its GitHub connection, the repositories attached).
            requested = body.strip()
            refusal = None
            if requested and not github.REPO_RE.fullmatch(requested):
                refusal = "invalid repository"
            else:
                with lock:
                    if not requested:
                        extra_repos.clear()
                    elif requested == current_repo:
                        refusal = "c'est déjà le dépôt principal"
                    elif requested in extra_repos:
                        extra_repos.remove(requested)
                    elif len(extra_repos) >= EXTRA_REPOS_MAX:
                        refusal = f"{EXTRA_REPOS_MAX} dépôts supplémentaires au plus"
                    else:
                        extra_repos.append(requested)
                    _save_state()
                    now = list(extra_repos)
            if refusal:
                self.reply_json({"accepted": False, "reason": refusal})
            else:
                if requested:
                    _remember_repo(requested)
                self.reply_json({"accepted": True, "extra_repos": now})
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
                if added in extra_repos:
                    extra_repos.remove(added)
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
                if options.get("deploy") in DEPLOY_MODES:
                    deploy_mode = options["deploy"]
                elif isinstance(options.get("autodeploy"), bool):
                    deploy_mode = "pages" if options["autodeploy"] else "none"
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
    _login_state()  # looks the login up in the background, so the first status already knows
    threading.Thread(target=_relay_listener, daemon=True).start()
    print(f"{time.strftime('%H:%M:%S')} Aiwa backend listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
