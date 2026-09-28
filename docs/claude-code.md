# Claude Code integration

Verified development transport: Claude Code 2.1.282 with `--resume`, stream-json input/output, partial messages and verbose mode.

The hard-coded development session id this originally shipped with (`8aab65ab-182b-40ab-b163-30338c87d2f5`) is gone — `current_session` in `aiwa_server.py` now starts as `None` (a real, brand-new session on the first message) and is set either by a real pick from `/api/sessions` (which lists actual `~/.claude/projects/**/*.jsonl` transcripts) or automatically from a fresh run's own real `result.session_id`. Background instruction remains deliberately unimplemented until its supported transport is verified — see below.

## One persistent `claude` process, not one per message

Confirmed live: the original design spawned a brand new `claude -p ...`
process for every single message, closing its stdin right after writing
the one message — which is exactly what told claude "no more input
coming, wrap up and exit". Every message therefore paid claude's real
cold-start cost (proot overhead + Node.js startup) on top of actual
model latency, roughly 20-30s of dead silence before the first token,
every single time.

`aiwa_server.py` now keeps ONE `claude` process alive (`process`,
managed by `_ensure_process`/`_start_process`) and writes each new
message to its stdin WITHOUT closing it, so the process can (assuming
this holds — see honest limit below) keep answering turn after turn
without ever restarting. `--resume <session>` is only needed to START a
process against a previously-existing session; once a process is alive,
subsequent turns need no `--resume` at all, since that same process
already has the conversation loaded. A dedicated `_reader_loop` thread
now runs for the process's whole lifetime rather than "read until one
result then stop", since the process now outlives many individual
turns. Switching or clearing the session (`/api/session`) kills the
current process outright, since it belongs to the old session's
context — the next message starts a fresh one.

**HONEST LIMIT**: whether claude's stream-json input mode genuinely
supports several user turns fed one at a time into the same live
process — rather than being designed to exit after the first — was
unverified when this was built. If it turns out not to, expect the
process to exit (or otherwise stop responding) after the first message
again, surfacing as the `{"type": "error", "message": "claude exited
unexpectedly ..."}` event from `_reader_loop`'s cleanup path.

## `/api/events` is a poll-and-clear queue, not a live stream

`aiwa_server.py` appends events to an in-memory list as the persistent
process's `_reader_loop` thread reads `claude`'s stdout; `GET
/api/events` returns whatever is currently queued and clears it. A
single GET right after `POST /api/message` returns will almost always
race that background thread and see nothing — the Android side
(`LocalClaudeBridge.kt`) has to poll repeatedly (every 150ms, bounded by
a 5-minute timeout) until it sees a real `"done"` or `"error"` event.
This was the actual reason streaming output never appeared: the
original bridge fired exactly one GET, immediately.

The backend also now guards against a second `/api/message` while one
is running (`busy`, checked and set under the same lock as the event
queue) — two concurrent `claude` invocations against the same session
id could otherwise interleave or conflict. A rejected
POST returns `{"accepted": false, "reason": "busy"}`; the bridge treats
any non-`accepted:true` response as a real error rather than silently
proceeding.

If `claude` never emits a `result` event (crash, bad session, exits
early), the backend now emits a real `{"type": "error", "message": ...}`
event of its own so a hung process can't leave the Android client
polling forever with nothing to show.
