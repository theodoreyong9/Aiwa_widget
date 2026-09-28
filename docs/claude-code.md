# Claude Code integration

Verified development transport: Claude Code 2.1.282 with `--resume`, stream-json input/output, partial messages and verbose mode.

Development session: `8aab65ab-182b-40ab-b163-30338c87d2f5`.

Production must replace the hard-coded ID with a controlled session selector. Background instruction remains deliberately unimplemented until its supported transport is verified.

## `/api/events` is a poll-and-clear queue, not a live stream

`aiwa_server.py` appends events to an in-memory list as `run_claude()`'s
background thread reads `claude`'s stdout; `GET /api/events` returns
whatever is currently queued and clears it. A single GET right after
`POST /api/message` returns will almost always race that background
thread and see nothing — the Android side (`LocalClaudeBridge.kt`) has
to poll repeatedly (every 150ms, bounded by a 5-minute timeout) until it
sees a real `"done"` or `"error"` event. This was the actual reason
streaming output never appeared: the original bridge fired exactly one
GET, immediately.

The backend also now guards against a second `/api/message` while one
is running (`busy`, checked and set under the same lock as the event
queue) — two concurrent `claude --resume SESSION` invocations against
the same session id could otherwise interleave or conflict. A rejected
POST returns `{"accepted": false, "reason": "busy"}`; the bridge treats
any non-`accepted:true` response as a real error rather than silently
proceeding.

If `claude` never emits a `result` event (crash, bad session, exits
early), the backend now emits a real `{"type": "error", "message": ...}`
event of its own so a hung process can't leave the Android client
polling forever with nothing to show.
