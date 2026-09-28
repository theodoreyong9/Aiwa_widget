# Aiwa Widget

Widget-first Android interface for Claude Code.

The home-screen widget is the primary product. The full app is a secondary surface for configuration and fallback UI.

Target UX:
- select a Claude Code session
- type or dictate a message
- keep real Claude Code session continuity
- display streaming Claude output
- answer Claude when it asks a question
- send a separate background instruction while work is running
- show working / waiting / finished / error states

Architecture:
Aiwa widget/app -> ClaudeBridge -> local backend -> Claude Code CLI.

The APK does not download or execute Kotlin/DEX from GitHub. Remote configuration is limited to declarative data.

The current Claude Code CLI remains the execution backend because it is not an Android SDK. The backend is hidden from the normal user workflow.

## Status against the target UX

Real and working (as of the polling fix in `LocalClaudeBridge.kt` — see `docs/claude-code.md`):
- type a message and send it, with real Claude output actually reaching the app (the client now polls `/api/events` until a real `done`/`error`, instead of firing one GET that raced the backend and produced nothing) — **but only once a real backend is actually running and reachable; see "Running this for real" below.**
- working / finished / error states (`AiwaState.Status`), now actually wired through `AiwaRepository` and read by both `MainActivity` and the home-screen widget, instead of sitting unused.
- the home-screen widget itself now shows this real, live state (session, status, a preview of the output or the pending question) instead of static placeholder text — it still opens the full app to actually type a message, since Glance's widget surface has no real text-input component to embed one directly.

Now also real:
- **voice dictation.** `MainActivity`'s 🎙️ button requests `RECORD_AUDIO` at runtime, then launches Android's own `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` and fills the message field with what it heard. Needs a real speech-recognition service on the device (present on virtually all real phones with Google's app installed; may not exist on a bare emulator image).
- **session selection.** Tapping "Session : …" in the app calls a real `/api/sessions`, which now scans `~/.claude/projects/**/*.jsonl` — the same real transcript files `claude --resume` itself reads — instead of returning one hard-coded id. Picking one calls `/api/session` to make it the active one; picking "Nouvelle session" clears it so the next message starts a genuinely new conversation, whose real id the backend then captures from that run's own `result` event so the conversation keeps going correctly afterward. **Honest limit**: this reads Claude Code's own on-disk storage layout, which is internal to the CLI and not a stable public API this project controls — if a future CLI version changes it, this starts returning nothing rather than failing loudly.

Still not real — these were never built, not by this session and not before it, whatever the "Fond" wording might suggest:
- **background instruction while work is running.** `/api/background` deliberately returns `not wired yet`; the "Fond" button that called it has been removed from `MainActivity` rather than leave a control that always silently fails. Real support needs the backend to keep one `claude` process alive across multiple messages instead of spawning a fresh one per `/api/message` and closing its stdin right after — and it's genuinely unverified whether `claude -p`'s stream-json input even supports a second user message arriving while the first is still being answered, rather than this needing a different transport entirely.
- **answering a mid-conversation question from Claude.** `AiwaState.WAITING`/`question` exist in the model, but in `-p` (print, non-interactive) mode there may be no distinct "Claude is asking a question and paused" event to detect in the first place, separate from an ordinary turn ending — a question from Claude is very likely just normal assistant text at the end of a turn, already answerable through the same "type a message and send it" flow above with no special state needed. Building a real answering flow first needs settling whether this distinction is real.
- no automated tests, on either the Kotlin or the Python side.

## Running this for real

Installing the APK is not enough on its own — the app only ever talks to `127.0.0.1:8787` (see `docs/claude-code.md`), and nothing in this repo starts that server for you. **`backend/aiwa_server.py` (and the `claude` CLI itself) has to actually be running on the SAME device the app is installed on** — `127.0.0.1` is per-device loopback, so a server running on a separate computer is not reachable this way at all, port-forwarding aside. "fail to connect to 127.0.0.1:8787" means exactly this: nothing is listening there yet, not a bug in the app.

**Honest tradeoff, worth knowing before setting this up**: `claude` is a real dev-tool CLI, built to operate on a real project's files on a real computer — running it via Termux puts it on your *phone's* filesystem, disconnected from whatever project you actually work on at your computer. That's a real architectural limitation of this design, not just a rough edge — a backend running on your own computer instead, with the app connecting to it remotely, would be a better fit for "check on / steer a Claude Code session while away from your desk," but is a bigger change (real auth, a configurable server address instead of a hard-coded `127.0.0.1`) that hasn't been built here. What follows is for the current, on-phone-via-Termux design.

On a real phone, install [Termux](https://termux.dev/) (a real terminal app, not this project's own code — get it from F-Droid; the Play Store build is outdated and often broken) and [Termux:API](https://wiki.termux.com/wiki/Termux:API) (needed for the wake-lock below), then, inside Termux, get this repo onto the device (`pkg install git && git clone https://github.com/theodoreyong9/aiwa_widget && cd aiwa_widget`) and run:
```
bash backend/setup-termux.sh
```
This installs Node.js (for the Claude Code CLI) and Python, installs the CLI itself, takes a wake-lock so Android doesn't kill Termux the moment you switch to the Aiwa app, and starts the server. Leave that Termux session running for as long as you want the app to work — closing Termux or letting Android kill it stops the backend; run the script again to restart it. It does not survive a reboot on its own.

## Reinstalling without uninstalling first

CI now signs every debug build with a fixed, committed `debug.keystore` at the repo root (the well-known public Android debug convention — alias/password `android`, never a real secret) instead of AGP's own default of auto-generating a different one per machine. Every GitHub Actions run is a fresh machine, so before this fix each build's APK had a genuinely different signing key, and Android refuses to install an APK over an existing one when the signatures don't match — hence needing to uninstall every time. Installing a new `aiwa-debug.apk` over the old one should now work as a normal update (keeping app data) as long as both were built after this fix.
