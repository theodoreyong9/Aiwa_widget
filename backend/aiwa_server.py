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

HOST = "127.0.0.1"
PORT = 8787
SESSION = "8aab65ab-182b-40ab-b163-30338c87d2f5"

events = []
lock = threading.Lock()
busy = False


def emit(event):
    with lock:
        events.append(event)


def run_claude(text):
    """Runs in its own daemon thread, one per /api/message — but only
    one at a time system-wide (see the busy guard in do_POST): two
    concurrent `claude --resume SESSION` invocations against the same
    session could otherwise interleave or conflict."""
    global busy
    saw_result = False
    try:
        process = subprocess.Popen(
            ["claude", "-p", "--resume", SESSION,
             "--input-format", "stream-json", "--output-format", "stream-json",
             "--include-partial-messages", "--verbose"],
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
                emit({"type": "done", "result": event.get("result", "")})
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
            self.reply_json({"session": SESSION, "status": status})
        elif self.path == "/api/sessions":
            self.reply_json([SESSION])
        else:
            self.send_error(404)

    def do_POST(self):
        global busy
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
        elif self.path == "/api/background":
            self.reply_json({"accepted": False, "reason": "not wired yet"})
        else:
            self.send_error(404)


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
