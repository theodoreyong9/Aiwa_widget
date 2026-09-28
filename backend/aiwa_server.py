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
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def _ts():
    return time.strftime("%H:%M:%S")

HOST = "127.0.0.1"
PORT = 8787
# How long a single `claude` invocation gets before this backend kills
# it and gives up. This is a safety net against it hanging with no
# output at all (reported live, likely tied to --resume specifically —
# see run_claude) — without it, `busy` could stay stuck true forever,
# permanently refusing every message after the one that got stuck.
RESULT_TIMEOUT_S = 120

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
    # Diagnostic trace added live — two watchdog-based fixes in a row
    # failed to actually stop "busy" getting stuck permanently after
    # the first message, with no matching `claude` process even found
    # running at the time. Rather than guess a fourth blind fix, this
    # prints exactly what happens at each step so the real cause is
    # directly visible in the server's own terminal instead of inferred.
    print(f"[{_ts()}] run_claude: starting {command}", flush=True)
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1,
        )
    except OSError as err:
        print(f"[{_ts()}] run_claude: Popen FAILED: {err}", flush=True)
        emit({"type": "error", "message": f"could not start claude: {err}"})
        with lock:
            busy = False
        return
    print(f"[{_ts()}] run_claude: pid={process.pid} started", flush=True)
    try:
        message = {"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}}
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.close()
        # Reported live: breaking the loop on "result" (see below) was
        # not enough on its own — every message after the first still
        # got rejected as "busy" forever, even with that fix in place.
        # The likely real cause: `claude -p --resume <session>` (only
        # used from the SECOND message onward, once a real session
        # exists) appears to sometimes hang and produce NO output at
        # all — the plain first-message case (no --resume) worked fine.
        # Without this watchdog, `for line in process.stdout` below
        # blocks forever with nothing to read, `busy` never clears, and
        # every later message is refused permanently. Killing the
        # process after a bounded wait guarantees `busy` always clears
        # and the client always gets a real, honest error either way,
        # whatever the exact reason turns out to be.
        def _on_timeout():
            print(f"[{_ts()}] run_claude: WATCHDOG firing after {RESULT_TIMEOUT_S}s, killing pid={process.pid}", flush=True)
            process.kill()
        watchdog = threading.Timer(RESULT_TIMEOUT_S, _on_timeout)
        watchdog.daemon = True
        watchdog.start()
        try:
            for line in process.stdout:
                try:
                    event = json.loads(line)
                except ValueError:
                    print(f"[{_ts()}] run_claude: non-JSON line: {line!r}", flush=True)
                    continue
                print(f"[{_ts()}] run_claude: event type={event.get('type')!r}", flush=True)
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
                    # A "result" event IS claude's final output for a
                    # one-shot -p invocation — nothing meaningful is left
                    # to read once we have it, and claude doesn't
                    # promptly close stdout on its own afterward.
                    print(f"[{_ts()}] run_claude: got result event, breaking out of read loop", flush=True)
                    break
        finally:
            watchdog.cancel()
        print(f"[{_ts()}] run_claude: read loop ended (saw_result={saw_result}), waiting on process", flush=True)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            print(f"[{_ts()}] run_claude: wait(timeout=5) expired, killing pid={process.pid}", flush=True)
            process.kill()
            process.wait()
        print(f"[{_ts()}] run_claude: process exited with returncode={process.returncode}", flush=True)
        # A crash or an early exit (bad session id, claude not on PATH
        # inside PATH resolved differently, etc.) would otherwise leave
        # the Android client polling forever with nothing to show —
        # this is the one real, guaranteed termination event either way.
        if not saw_result:
            emit({"type": "error", "message": f"claude exited with code {process.returncode} before finishing (killed after {RESULT_TIMEOUT_S}s with no result if that code looks like a kill signal)"})
    finally:
        print(f"[{_ts()}] run_claude: clearing busy flag", flush=True)
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
                    print(f"[{_ts()}] do_POST: REJECTING /api/message — busy is already True", flush=True)
                    self.reply_json({"accepted": False, "reason": "busy"})
                    return
                busy = True
            print(f"[{_ts()}] do_POST: accepting /api/message, spawning run_claude thread", flush=True)
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
