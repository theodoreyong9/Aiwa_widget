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

PERSISTENT PROCESS: one `claude` process is now kept alive across
multiple messages instead of spawning a fresh one per /api/message —
confirmed live that every single message paid a ~20-30s cold-start cost
(proot overhead + Node.js startup) under the old one-process-per-message
design, since claude was relaunched from scratch every single time.
HONEST LIMIT going into this: whether claude's stream-json input mode
really supports several user turns fed one at a time into the SAME live
process (instead of exiting after the first, which is what closing
stdin right after the one message used to do) was unverified — this
file is the actual test of that assumption, not a guarantee.
"""
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def _ts():
    return time.strftime("%H:%M:%S")


HOST = "127.0.0.1"
PORT = 8787
# Bumped whenever the app starts depending on a new backend feature. The
# app compares this (reported via /api/status) against the version it
# expects, so a stale checkout/running server is detected and fixed
# instead of silently missing routes (reported live: history never
# loaded because the backend running on the phone predated /api/history).
BACKEND_VERSION = 2
# Longest transcript /api/history will send back — sessions can be
# megabytes; only the most recent part is useful in a phone UI.
MAX_HISTORY_CHARS = 40000
# Values passed to `claude --model`. Restrictive on purpose: it ends up
# as a command-line argument, and must never look like another flag.
SESSION_RE = re.compile(r"[A-Za-z0-9-]{8,64}")
MODEL_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}")
# How long a single turn gets before this backend gives up on it and
# kills the underlying process. This is a safety net against a turn
# hanging with no output at all — without it, `busy` could stay stuck
# true forever, permanently refusing every message after the one that
# got stuck.
RESULT_TIMEOUT_S = 120

events = []
lock = threading.Lock()
busy = False
# None means "no session picked yet — the next message starts a real,
# brand-new Claude Code session instead of --resume-ing anything".
# Real ids come from either a real, on-disk session (see
# list_real_sessions below) or from the session_id a fresh run's own
# real "result" event reports back — never invented.
current_session = None
# None means "the CLI's own default model" — otherwise an alias or model
# id passed as `claude --model <value>` whenever a process is started.
current_model = None
# The one persistent claude process, or None if none is currently
# running (nothing sent yet, it crashed, or a session switch killed it
# deliberately — see /api/session below). Guarded by process_lock
# rather than the general-purpose `lock` above, since starting/killing
# it can take a moment and shouldn't block unrelated /api/events polls.
process = None
process_lock = threading.Lock()
# The watchdog timer for whichever turn is currently in flight, so the
# reader thread can cancel it the moment a real result arrives instead
# of waiting out the full timeout every time. Guarded by `lock`.
current_watchdog = None


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


def _extract_text(content):
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
        return "".join(parts).strip()
    return ""


def read_session_transcript(session_id):
    """The FULL past conversation for one session — the same transcript
    file list_real_sessions() peeks at for a one-line preview, but
    walking every user/assistant turn instead of stopping at the first
    one. Reported live: "ni dans le widget ni dans l'application il n'y
    a la récupération du contenu de la conversation" — resuming an
    existing session only ever showed turns sent AFTER switching to it;
    the actual past conversation was never loaded at all. Same HONEST
    LIMIT as list_real_sessions() on the on-disk layout this reads."""
    projects_dir = Path.home() / ".claude" / "projects"
    if not projects_dir.is_dir():
        return None
    # Filtering by exact filename stem, rather than interpolating
    # session_id (an HTTP query param) straight into a glob pattern,
    # avoids that pattern ever being able to escape projects_dir via a
    # crafted "../" value — moot today since this only binds to
    # 127.0.0.1, but cheap to get right regardless.
    match = next((p for p in projects_dir.glob("*/*.jsonl") if p.stem == session_id), None)
    if match is None:
        return None
    turns = []
    with match.open("r", encoding="utf-8") as f:
        for line in f:
            try:
                record = json.loads(line)
            except ValueError:
                continue
            message = record.get("message") or {}
            role = message.get("role")
            if role not in ("user", "assistant"):
                continue
            text = _extract_text(message.get("content"))
            if not text:
                continue
            turns.append(("🧑" if role == "user" else "🤖") + " " + text)
    transcript = "\n\n".join(turns)
    if len(transcript) > MAX_HISTORY_CHARS:
        transcript = "…" + transcript[-MAX_HISTORY_CHARS:]
    return transcript


def _kill_process(proc):
    try:
        proc.kill()
        proc.wait(timeout=5)
    except Exception as err:
        print(f"[{_ts()}] _kill_process: ignoring {err!r} while killing pid={proc.pid}", flush=True)


def _start_process(session, model):
    command = ["claude", "-p"]
    if session:
        command += ["--resume", session]
    if model:
        command += ["--model", model]
    command += ["--input-format", "stream-json", "--output-format", "stream-json",
                "--include-partial-messages", "--verbose"]
    print(f"[{_ts()}] _start_process: {command}", flush=True)
    proc = subprocess.Popen(
        command,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1,
    )
    print(f"[{_ts()}] _start_process: pid={proc.pid} started", flush=True)
    return proc


def _reader_loop(proc):
    """Runs for the WHOLE LIFETIME of one persistent claude process —
    decoupled from any single message's turn, since the process now
    outlives many messages instead of exiting after one. Each "result"
    event ends the CURRENT turn (clears busy, cancels that turn's
    watchdog) without touching the process itself, which stays open for
    the next message."""
    global busy, current_session, process, current_watchdog
    try:
        for line in proc.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                print(f"[{_ts()}] reader(pid={proc.pid}): non-JSON line: {line!r}", flush=True)
                continue
            print(f"[{_ts()}] reader(pid={proc.pid}): event type={event.get('type')!r}", flush=True)
            if event.get("type") == "stream_event":
                delta = event.get("event", {}).get("delta", {})
                if delta.get("type") == "text_delta":
                    emit({"type": "text", "text": delta.get("text", "")})
            elif event.get("type") == "result":
                # A fresh process (no --resume) only reveals its own
                # real session id here — capturing it is what lets a
                # LATER process (e.g. after a restart) --resume this
                # same conversation; the current live process doesn't
                # need it again, it already has full context loaded.
                real_session_id = event.get("session_id")
                if real_session_id:
                    with lock:
                        current_session = real_session_id
                emit({"type": "done", "result": event.get("result", ""), "session_id": real_session_id})
                with lock:
                    busy = False
                    if current_watchdog is not None:
                        current_watchdog.cancel()
                        current_watchdog = None
    finally:
        # The process exited — a crash, the watchdog killing it, or
        # (untested territory) claude closing stdout on its own for
        # some reason after all. Whatever turn was in flight, if any,
        # gets a real, honest termination event instead of leaving the
        # client polling forever with nothing, and this process is no
        # longer usable for the next message — _ensure_process will
        # start a fresh one.
        returncode = proc.poll()
        print(f"[{_ts()}] reader(pid={proc.pid}): loop ended, returncode={returncode}", flush=True)
        with process_lock:
            if process is proc:
                process = None
        with lock:
            if busy:
                busy = False
                if current_watchdog is not None:
                    current_watchdog.cancel()
                    current_watchdog = None
                emit({"type": "error", "message": f"claude exited unexpectedly (code {returncode}) mid-turn"})


def _drop_process_and_rewarm(reason):
    """The live process belongs to the previous session/model, so it has
    to go — and starting the replacement right away (instead of lazily on
    the next message) means the ~20-30s cold start overlaps with the user
    doing something else, rather than landing on their next message."""
    global process
    with process_lock:
        if process is not None:
            print(f"[{_ts()}] {reason}: killing pid={process.pid}", flush=True)
            _kill_process(process)
            process = None
    threading.Thread(target=_ensure_process, daemon=True).start()


def _ensure_process():
    """Returns a live persistent process, starting one if needed."""
    global process
    with process_lock:
        if process is not None and process.poll() is None:
            return process
        with lock:
            session = current_session
            model = current_model
        process = _start_process(session, model)
        threading.Thread(target=_reader_loop, args=(process,), daemon=True).start()
        return process


def send_message(text):
    """Writes one user turn to the persistent process's stdin — started
    first if it's not already running — WITHOUT closing stdin
    afterward. Closing stdin right after the one message is exactly
    what used to signal claude "no more input coming, wrap up and
    exit", which is why every single message needed a brand new
    process (and paid its ~20-30s cold start) before this change."""
    global busy, current_watchdog
    proc = _ensure_process()
    message = {"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}}
    print(f"[{_ts()}] send_message: writing to pid={proc.pid}", flush=True)
    try:
        proc.stdin.write(json.dumps(message) + "\n")
        proc.stdin.flush()
    except (BrokenPipeError, OSError) as err:
        print(f"[{_ts()}] send_message: write FAILED: {err}", flush=True)
        emit({"type": "error", "message": f"could not send to claude: {err}"})
        with lock:
            busy = False
        return

    def _on_timeout():
        print(f"[{_ts()}] send_message: WATCHDOG firing after {RESULT_TIMEOUT_S}s, killing pid={proc.pid}", flush=True)
        _kill_process(proc)

    watchdog = threading.Timer(RESULT_TIMEOUT_S, _on_timeout)
    watchdog.daemon = True
    with lock:
        current_watchdog = watchdog
    watchdog.start()


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
                model = current_model
            self.reply_json({"session": session, "status": status, "model": model, "version": BACKEND_VERSION})
        elif self.path == "/api/sessions":
            self.reply_json(list_real_sessions())
        elif self.path.startswith("/api/history"):
            session_id = parse_qs(urlparse(self.path).query).get("session", [None])[0]
            transcript = read_session_transcript(session_id) if session_id else None
            self.reply_json({"transcript": transcript})
        else:
            self.send_error(404)

    def do_POST(self):
        global busy, current_session, current_model, process
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
            print(f"[{_ts()}] do_POST: accepting /api/message, spawning send_message thread", flush=True)
            threading.Thread(target=send_message, args=(body,), daemon=True).start()
            self.reply_json({"accepted": True})
        elif self.path == "/api/session":
            # An empty body means "forget the current session — the
            # next message starts a genuinely new one". Either way, the
            # persistent process (if any) belongs to the OLD session's
            # context, so it has to go — the next message starts a
            # fresh one, --resume-ing the newly picked session if any.
            requested_session = body.strip()
            if requested_session and not SESSION_RE.fullmatch(requested_session):
                self.reply_json({"accepted": False, "reason": "invalid session id"})
                return
            with lock:
                if busy:
                    self.reply_json({"accepted": False, "reason": "busy"})
                    return
                current_session = requested_session or None
            _drop_process_and_rewarm(f"/api/session -> {current_session!r}")
            self.reply_json({"accepted": True, "session": current_session})
        elif self.path == "/api/model":
            requested = body.strip()
            if requested and not MODEL_RE.fullmatch(requested):
                self.reply_json({"accepted": False, "reason": "invalid model"})
                return
            with lock:
                if busy:
                    self.reply_json({"accepted": False, "reason": "busy"})
                    return
                current_model = requested or None
            _drop_process_and_rewarm(f"/api/model -> {current_model!r}")
            self.reply_json({"accepted": True, "model": current_model})
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
    # Reported live: the first message of every fresh server start still
    # pays claude's real ~20-30s cold-start cost, same as before the
    # persistent-process change — unavoidable the FIRST time, but there
    # is no reason to make the user sit through it AFTER they've already
    # typed and sent something. Pre-warming it here, in the background,
    # right as the server starts (which itself now starts automatically
    # when the app opens — see TermuxLauncher.kt), means that cost
    # mostly overlaps with the user opening the app and typing, instead
    # of happening only once they've already hit "Envoyer". Backgrounded
    # so it doesn't delay the HTTP server actually starting to listen.
    threading.Thread(target=_ensure_process, daemon=True).start()
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
