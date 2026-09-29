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
import json
import os
import pty
import re
import select
import shlex
import shutil
import signal
import struct
import subprocess
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import aiwa_github as github


def _ts():
    return time.strftime("%H:%M:%S")


HOST = "127.0.0.1"
PORT = 8787
# Bumped whenever the app starts depending on a new backend feature; the
# app compares it (via /api/status) with the version it expects.
BACKEND_VERSION = 6
# Passed to `claude --model` when a new cloud session is created, and to
# `/model` in an existing one. Kept restrictive: it ends up as a
# command-line argument / slash-command argument.
MODEL_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}")

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
cloud_busy = False
# GitHub: the repository new sessions start on ("owner/name"; None = the
# plain chat) and whether Claude pushes straight to its main branch
# (otherwise to a fresh aiwa/<date> branch).
current_repo = None
push_main = True
github_error = None  # the last GitHub problem worth showing in the app
store_lock = threading.Lock()
# Two `claude -p --cloud <id>` runs never overlap (a send and the /rename
# that follows a creation, for instance).
followup_lock = threading.Lock()


def _load_state():
    global current_model, current_cloud, current_repo, push_main
    try:
        data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    if isinstance(data, dict):
        model, cloud, repo = data.get("model"), data.get("cloud"), data.get("repo")
        current_model = model if isinstance(model, str) and MODEL_RE.fullmatch(model) else None
        current_cloud = cloud if isinstance(cloud, str) and CLOUD_ID_RE.fullmatch(cloud) else None
        current_repo = repo if isinstance(repo, str) and github.REPO_RE.fullmatch(repo) else None
        push_main = data.get("push_main") is not False


def _save_state():
    """Survives a backend restart (the app restarts it when it updates):
    without this, the next message after a restart would silently start a
    brand-new cloud session instead of continuing the current one."""
    try:
        STATE_FILE.write_text(json.dumps({
            "model": current_model, "cloud": current_cloud, "repo": current_repo,
            "push_main": push_main,
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


def _save_cloud_session(session_id, title, url, repo=None, branch=None, direct=None):
    """The CLI has no non-interactive way to LIST cloud sessions, so the
    ones Aiwa created or was given a link to are remembered here, with the
    repository (and branch) a session was started on."""
    with store_lock:
        entries = _load_cloud_sessions()
        existing = next((e for e in entries if e.get("id") == session_id), None) or {}
        entries = [e for e in entries if e.get("id") != session_id]
        entry = {
            "id": session_id,
            "title": existing.get("title") or title,
            "url": url or existing.get("url") or f"https://claude.ai/code/{session_id}",
        }
        for key, value in (("repo", repo), ("branch", branch), ("direct", direct)):
            value = existing.get(key) if value is None else value
            if value is not None:
                entry[key] = value
        entries.insert(0, entry)
        CLOUD_STORE.write_text(json.dumps(entries[:30]), encoding="utf-8")


def _session_entry(session_id):
    return next((e for e in _load_cloud_sessions() if e.get("id") == session_id), {})


def _instructions(repo, branch, direct):
    """Appended to every message sent in a repository session."""
    lines = [f"[Aiwa] Dépôt de travail : {repo}."]
    if direct:
        lines.append(f"Quand tu as terminé, fais un commit et pousse directement sur la branche {branch} (pas de pull request).")
    else:
        lines.append(f"Travaille sur la branche {branch} (déjà créée) : commit et push dessus, ne touche pas à la branche principale.")
    return "\n\n" + "\n".join(lines)


def _last_json_object(output):
    for line in reversed(output.strip().splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def cloud_add(text):
    """Adds an EXISTING cloud session, given its link or id (copied from
    the Claude app / claude.ai/code), and selects it."""
    global current_cloud, current_repo
    match = CLOUD_ID_RE.search(text)
    if match is None:
        return None
    session_id = match.group(0)
    url_match = CLOUD_URL_RE.search(text)
    _save_cloud_session(session_id, "Session " + session_id[:16], url_match.group(0) if url_match else None)
    with lock:
        current_cloud = session_id
        current_repo = _session_entry(session_id).get("repo")
        _save_state()
    return session_id


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
    global current_cloud, cloud_busy, github_error
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
    try:
        if session_id:
            sent = text
            entry = _session_entry(session_id)
            if not command and entry.get("repo"):
                sent += _instructions(entry["repo"], entry.get("branch") or "main", entry.get("direct", True))
            result = _queue_followup(session_id, sent)
            if not result["ok"]:
                return {"ok": False, "error": result["error"]}
            if not command:
                _save_cloud_session(session_id, title, result["url"])
            return {"ok": True, "session_id": session_id, "url": result["url"]}
        directory, branch = CLOUD_DIR, None
        if repo:
            # The session starts on the chosen repository: the cloud clones
            # the same GitHub repo at the branch checked out in this local
            # clone (needs Claude's own GitHub access, which the user grants).
            try:
                directory, branch = github.prepare_clone(repo, direct_now)
            except github.GithubError as err:
                github_error = str(err)
                return {"ok": False, "error": f"dépôt {repo} : {err}"}
            github_error = None
        else:
            _ensure_cloud_repo()
        task = text + (_instructions(repo, branch, direct_now) if repo else "")
        if task.lstrip().startswith("-"):
            task = "Message : " + task
        command_line = ["claude"] + (["--model", model] if model else []) + ["--cloud", task]
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
        if found is None:
            reason = "délai dépassé" if timed_out else f"aucun identifiant de session trouvé (code {code})"
            return {"ok": False, "error": reason + " — sortie : " + output.strip()[-600:]}
        with lock:
            current_cloud = found
            _save_state()
        _save_cloud_session(found, title, url, repo=repo, branch=branch, direct=direct_now if repo else None)
        threading.Thread(target=_rename_session, args=(found, title), daemon=True).start()
        return {"ok": True, "session_id": found, "url": url}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "délai dépassé"}
    except (OSError, subprocess.CalledProcessError) as err:
        return {"ok": False, "error": str(err)}
    finally:
        with lock:
            cloud_busy = False


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
            login = github.account_snapshot()
            self.reply_json({
                "version": BACKEND_VERSION, "model": model, "cloud_session": cloud_session,
                "repo": repo, "push_main": direct,
                "github": {
                    "connected": login is not None, "login": login,
                },
                "github_error": problem,
            })
        elif self.path == "/api/cloud/sessions":
            self.reply_json(_load_cloud_sessions())
        elif self.path == "/api/github/repos":
            try:
                self.reply_json({"ok": True, "repos": github.list_repos()})
            except github.GithubError as err:
                self.reply_json({"ok": False, "error": str(err)})
        else:
            self.send_error(404)

    def do_POST(self):
        global current_model, current_cloud, current_repo, push_main
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
            with lock:
                current_cloud = None if target == "new" else target
                if target != "new":
                    # A session belongs to the repository it was started on.
                    current_repo = _session_entry(target).get("repo")
                _save_state()
            self.reply_json({"accepted": True, "cloud_session": current_cloud})
        elif self.path == "/api/cloud/add":
            added = cloud_add(body)
            if added is None:
                self.reply_json({"accepted": False, "reason": "no session id in that text"})
            else:
                self.reply_json({"accepted": True, "cloud_session": added})
        elif self.path == "/api/model":
            requested = body.strip()
            if requested and not MODEL_RE.fullmatch(requested):
                self.reply_json({"accepted": False, "reason": "invalid model"})
                return
            with lock:
                current_model = requested or None
                _save_state()
            self.reply_json({"accepted": True, "model": current_model})
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
            self.reply_json({"accepted": True, "repo": current_repo})
        elif self.path == "/api/options":
            try:
                options = json.loads(body or "{}")
            except ValueError:
                options = None
            if not isinstance(options, dict):
                self.reply_json({"accepted": False, "reason": "invalid options"})
                return
            with lock:
                if isinstance(options.get("push_main"), bool):
                    push_main = options["push_main"]
                _save_state()
            self.reply_json({"accepted": True, "push_main": push_main})
        else:
            self.send_error(404)


if __name__ == "__main__":
    _load_state()
    print(f"Aiwa backend listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
