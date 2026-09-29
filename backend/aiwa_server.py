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
"""
import fcntl
import json
import os
import pty
import re
import select
import shlex
import shutil
import struct
import subprocess
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def _ts():
    return time.strftime("%H:%M:%S")


HOST = "127.0.0.1"
PORT = 8787
# Bumped whenever the app starts depending on a new backend feature; the
# app compares it (via /api/status) with the version it expects.
BACKEND_VERSION = 4
# Passed to `claude --model` when a new cloud session is created. Kept
# restrictive: it ends up as a command-line argument.
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
# Terminal escape sequences: CSI, OSC, and the two-byte kind (ESC 7 / ESC 8
# save/restore the cursor — those left "78" in front of an error message).
ANSI_RE = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[ -/]*[0-~])")
CTRL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")

lock = threading.Lock()  # guards the state below
# None = the CLI's own default model.
current_model = None
# The cloud session messages go to; None = the next message creates one.
current_cloud = None
cloud_busy = False
store_lock = threading.Lock()


def _load_state():
    global current_model, current_cloud
    try:
        data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    if isinstance(data, dict):
        model, cloud = data.get("model"), data.get("cloud")
        current_model = model if isinstance(model, str) and MODEL_RE.fullmatch(model) else None
        current_cloud = cloud if isinstance(cloud, str) and CLOUD_ID_RE.fullmatch(cloud) else None


def _save_state():
    """Survives a backend restart (the app restarts it when it updates):
    without this, the next message after a restart would silently start a
    brand-new cloud session instead of continuing the current one."""
    try:
        STATE_FILE.write_text(json.dumps({"model": current_model, "cloud": current_cloud}), encoding="utf-8")
    except OSError as err:
        print(f"[{_ts()}] could not save state: {err}", flush=True)


def _clean(text):
    return CTRL_RE.sub("", ANSI_RE.sub("", text).replace("\r", "\n"))


def _ensure_cloud_repo():
    CLOUD_DIR.mkdir(parents=True, exist_ok=True)
    if not (CLOUD_DIR / ".git").exists():
        subprocess.run(["git", "init", "-q"], cwd=CLOUD_DIR, check=True)
    has_commit = subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD"], cwd=CLOUD_DIR, capture_output=True).returncode == 0
    if not has_commit:
        (CLOUD_DIR / "README.md").write_text("chat\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=CLOUD_DIR, check=True)
        subprocess.run(["git", "-c", "user.name=aiwa", "-c", "user.email=aiwa@example.com", "commit", "-qm", "init"], cwd=CLOUD_DIR, check=True)


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
            proc.terminate()
        os.close(master)
    try:
        code = proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()
        code = proc.wait()
    if reason != "exited":
        # `script` may leave the CLI it started running; nothing else
        # runs a cloud command while this one holds cloud_busy.
        subprocess.run(["pkill", "-f", "[c]laude .*--cloud"], capture_output=True)
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


def _save_cloud_session(session_id, title, url):
    """The CLI has no non-interactive way to LIST cloud sessions, so the
    ones Aiwa created or was given a link to are remembered here."""
    with store_lock:
        entries = _load_cloud_sessions()
        existing = next((e for e in entries if e.get("id") == session_id), None)
        entries = [e for e in entries if e.get("id") != session_id]
        entries.insert(0, {
            "id": session_id,
            "title": (existing or {}).get("title") or title,
            "url": url or (existing or {}).get("url") or f"https://claude.ai/code/{session_id}",
        })
        CLOUD_STORE.write_text(json.dumps(entries[:30]), encoding="utf-8")


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
    global current_cloud
    match = CLOUD_ID_RE.search(text)
    if match is None:
        return None
    session_id = match.group(0)
    url_match = CLOUD_URL_RE.search(text)
    _save_cloud_session(session_id, "Session " + session_id[:16], url_match.group(0) if url_match else None)
    with lock:
        current_cloud = session_id
        _save_state()
    return session_id


def cloud_send(text):
    """Sends one message to the current cloud session — creating a new
    one when none is selected. Synchronous: creating a session can take a
    while, the cloud machine has to start."""
    global current_cloud, cloud_busy
    with lock:
        if cloud_busy:
            return {"ok": False, "error": "busy"}
        cloud_busy = True
        session_id = current_cloud
        model = current_model
    title = " ".join(text.split())[:60]
    try:
        if session_id:
            command = ["claude", "-p", "--cloud", session_id, "--output-format", "json"]
            done = subprocess.run(command, input=text, capture_output=True, text=True, timeout=90)
            output = _clean((done.stdout or "") + (done.stderr or ""))
            _log_cloud("follow-up", command, done.returncode, output)
            data = _last_json_object(output)
            if data is not None:
                ok, url, error = data.get("ok") is True, data.get("url"), data.get("error")
            else:
                ok, url, error = done.returncode == 0, None, None
            if not ok:
                return {"ok": False, "error": error or output.strip()[-600:] or f"code {done.returncode}"}
            _save_cloud_session(session_id, title, url)
            return {"ok": True, "session_id": session_id, "url": url}
        _ensure_cloud_repo()
        task = "Message : " + text if text.lstrip().startswith("-") else text
        command = ["claude"] + (["--model", model] if model else []) + ["--cloud", task]
        # Under `script` the CLI gets a full terminal including a
        # controlling one (a bare pty has none, and a program that opens
        # /dev/tty then fails); without `script` it just gets the pty.
        run = ["script", "-q", "-e", "-c", shlex.join(command), "/dev/null"] if shutil.which("script") else command
        code, output, reason, timeline = _run_with_pty(run, CLOUD_DIR, 180, stop_after_session_id=True)
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
        _save_cloud_session(found, title, url)
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
            self.reply_json({"version": BACKEND_VERSION, "model": model, "cloud_session": cloud_session})
        elif self.path == "/api/cloud/sessions":
            self.reply_json(_load_cloud_sessions())
        else:
            self.send_error(404)

    def do_POST(self):
        global current_model, current_cloud
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "invalid Content-Length")
            return
        body = self.rfile.read(length).decode()
        if self.path == "/api/cloud/message":
            self.reply_json(cloud_send(body))
        elif self.path == "/api/cloud/select":
            # "new" = the next message creates a session; otherwise the id
            # of one of /api/cloud/sessions.
            target = body.strip()
            if target != "new" and not CLOUD_ID_RE.fullmatch(target):
                self.reply_json({"accepted": False, "reason": "invalid cloud session"})
                return
            with lock:
                current_cloud = None if target == "new" else target
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
        else:
            self.send_error(404)


if __name__ == "__main__":
    _load_state()
    print(f"Aiwa backend listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
