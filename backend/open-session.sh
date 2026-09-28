#!/data/data/com.termux/files/usr/bin/bash
# Fired by the widget's own session-id tap (OpenSessionInTermuxAction.kt)
# via Termux's real RUN_COMMAND automation — reported live as explicitly
# wanted: open the REAL Claude Code CLI session directly, not Aiwa's own
# app UI. Requires the same one-time setup as everything else here
# (bootstrap.sh) — this only resumes an ALREADY-installed Ubuntu/claude.
set -euo pipefail

SESSION="${1:?session id required}"
DISTRO=ubuntu

exec proot-distro login "$DISTRO" -- claude --resume "$SESSION"
