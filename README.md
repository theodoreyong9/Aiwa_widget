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
- type a message and send it, with real Claude output actually reaching the app (the client now polls `/api/events` until a real `done`/`error`, instead of firing one GET that raced the backend and produced nothing).
- working / finished / error states (`AiwaState.Status`), now actually wired through `AiwaRepository` and read by both `MainActivity` and the home-screen widget, instead of sitting unused.
- the home-screen widget itself now shows this real, live state (session, status, a preview of the output or the pending question) instead of static placeholder text — it still opens the full app to actually type or dictate, since Glance's widget surface has no real text-input component to embed one directly.

Still not real:
- session selection — `/api/sessions` returns the one hard-coded development session id; there is no real selector.
- answering a mid-conversation question from Claude — `AiwaState.WAITING`/`question` exist in the model but nothing yet detects a real question from the stream and prompts for an answer.
- background instruction while work is running — `/api/background` deliberately returns `not wired yet`.
- no automated tests, on either the Kotlin or the Python side.
