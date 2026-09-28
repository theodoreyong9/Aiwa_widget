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
