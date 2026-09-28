#!/usr/bin/env python3
"""Local bridge between the Aiwa Android app and the Claude Code CLI.

Binds to 127.0.0.1 only — this is a same-device bridge, never a network
service, and has no authentication of its own on that basis.

/api/events is a real poll-and-clear queue, not a live stream: a single
GET right after POST returns only whatever happened to land in that
instant, since the real work runs in a background thread. The Android
client (LocalClaudeBridge.kt) must poll this repeatedly until it sees a
real "done" or "error" event — it used to fire exactly one GET, which is
why streaming output never actually appeared.
"""
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "127.0.0.1"
PORT = 8787

events = []
lock = threading.Lock()
busy = False
# None means "no session picked yet — the next message starts a real,
# brand-new Claude Code session instead of --resume-ing anything".
# Real ids come from either a real, on-disk session (see
# list_real_sessions below) or from the session_id a fresh run's own
# real "result" event reports back (see run_claude) — never invented.
current_session = None


def emit(event):
    with lock:
        events.append(event)


def list_real_sessions(limit=20):
    """Real, on-disk Claude Code sessions — reads the same transcript
    files `claude --resume <id>` itself reads, under
    ~/.claude/projects/<project>/<session-id>.jsonl. HONEST LIMIT: this
    directory layout is Claude Code's own, undocumented-here, internal
    storage format; it has been correct for the CLI version this was
    built against (see docs/claude-code.md) but isn't a stable public
    API this project controls — if a future CLI version changes it,
    this will start returning nothing rather than failing loudly, and
    needs updating."""
    projects_dir = Path.home() / ".claude" / "projects"
    if not projects_dir.is_dir():
        return []
    entries = []
    for jsonl_path in projects_dir.glob("*/*.jsonl"):
        session_id = jsonl_path.stem
        preview = session_id
        try:
            with jsonl_path.open("r", encoding="utf-8") as f:
                for line in f:
                    try:
                        record = json.loads(line)
                    except ValueError:
                        continue
                    message = record.get("message") or {}
                    if message.get("role") != "user":
                        continue
                    content = message.get("content")
                    if isinstance(content, str) and content.strip():
                        preview = content.strip()[:80]
                        break
                    if isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "text" and block.get("text", "").strip():
                                preview = block["text"].strip()[:80]
                                break
                    break
            mtime = jsonl_path.stat().st_mtime
        except OSError:
            mtime = 0
        entries.append({"id": session_id, "preview": preview, "mtime": mtime})
    entries.sort(key=lambda e: e["mtime"], reverse=True)
    for e in entries:
        del e["mtime"]
    return entries[:limit]


def run_claude(text):
    """Runs in its own daemon thread, one per /api/message — but only
    one at a time system-wide (see the busy guard in do_POST): two
    concurrent `claude` invocations against the same session could
    otherwise interleave or conflict."""
    global busy, current_session
    saw_result = False
    with lock:
        session = current_session
    command = ["claude", "-p"]
    if session:
        command += ["--resume", session]
    command += ["--input-format", "stream-json", "--output-format", "stream-json",
                "--include-partial-messages", "--verbose"]
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1,
        )
    except OSError as err:
        emit({"type": "error", "message": f"could not start claude: {err}"})
        with lock:
            busy = False
        return
    try:
        message = {"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}}
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.close()
        for line in process.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if event.get("type") == "stream_event":
                delta = event.get("event", {}).get("delta", {})
                if delta.get("type") == "text_delta":
                    emit({"type": "text", "text": delta.get("text", "")})
            elif event.get("type") == "result":
                saw_result = True
                # A fresh run (no --resume) only reveals its own real
                # session id here — capturing it is what lets the NEXT
                # message actually continue this same conversation
                # instead of starting yet another new one each time.
                real_session_id = event.get("session_id")
                if real_session_id:
                    with lock:
                        current_session = real_session_id
                emit({"type": "done", "result": event.get("result", ""), "session_id": real_session_id})
                # Reported live: every message after the first got
                # rejected as "busy" forever. Root cause: a "result"
                # event IS claude's final output for a one-shot -p
                # invocation, but this loop kept reading `process.stdout`
                # waiting for more lines that never came — claude
                # apparently doesn't promptly close stdout after
                # printing its result — so `finally: busy = False` below
                # never ran. Nothing meaningful is left to read once we
                # have the result.
                break
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        # A crash or an early exit (bad session id, claude not on PATH
        # inside PATH resolved differently, etc.) would otherwise leave
        # the Android client polling forever with nothing to show —
        # this is the one real, guaranteed termination event either way.
        if not saw_result:
            emit({"type": "error", "message": f"claude exited with code {process.returncode} before finishing"})
    finally:
        with lock:
            busy = False


class Handler(BaseHTTPRequestHandler):
    def reply_json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/events":
            with lock:
                pending = list(events)
                events.clear()
            self.reply_json(pending)
        elif self.path == "/api/status":
            with lock:
                status = "busy" if busy else "ready"
                session = current_session
            self.reply_json({"session": session, "status": status})
        elif self.path == "/api/sessions":
            self.reply_json(list_real_sessions())
        else:
            self.send_error(404)

    def do_POST(self):
        global busy, current_session
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "invalid Content-Length")
            return
        body = self.rfile.read(length).decode()
        if self.path == "/api/message":
            with lock:
                if busy:
                    self.reply_json({"accepted": False, "reason": "busy"})
                    return
                busy = True
            threading.Thread(target=run_claude, args=(body,), daemon=True).start()
            self.reply_json({"accepted": True})
        elif self.path == "/api/session":
            # An empty body means "forget the current session — the
            # next message starts a genuinely new one".
            with lock:
                if busy:
                    self.reply_json({"accepted": False, "reason": "busy"})
                    return
                current_session = body.strip() or None
            self.reply_json({"accepted": True, "session": current_session})
        elif self.path == "/api/background":
            self.reply_json({"accepted": False, "reason": "not wired yet"})
        else:
            self.send_error(404)


if __name__ == "__main__":
    # serve_forever() itself never prints anything — reported live as
    # "nothing happens after 'Leave this running...'", which was in
    # fact the server working correctly, just silently. This one line
    # is the only visible confirmation the user gets that it's actually
    # up rather than hung.
    print(f"Aiwa backend listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
