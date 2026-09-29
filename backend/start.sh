#!/data/data/com.termux/files/usr/bin/bash
# The FAST path — meant to be triggered by the Aiwa widget's own
# "Démarrer" button via Termux's real RUN_COMMAND automation API (see
# TermuxLauncher.kt), not typed by hand. Does none of the install/
# reinstall work setup-termux-proot.sh does (pkg update, apt-get
# update, npm install --force on every single call would make every
# "Démarrer" tap slow) — it only starts the ALREADY-installed backend
# inside the ALREADY-installed Ubuntu image. Run bootstrap.sh (which
# runs setup-termux-proot.sh) by hand at least once first.
set -uo pipefail

DISTRO=ubuntu
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$HOME/aiwa_backend.log"

# This script usually runs with no terminal at all (Termux's RUN_COMMAND), so
# everything goes to a file: `cat ~/aiwa_backend.log` shows why a start failed.
# Written FIRST, so that the file existing proves this script ran.
{
  echo "=== $(date) — start.sh, checkout $REPO_DIR ==="
} > "$LOG" 2>&1

# No "is Ubuntu installed?" pre-check: it either started proot a first time just
# to look (twice as slow) or guessed where proot-distro keeps its files (a guess
# that broke the start). If Ubuntu is missing, proot-distro itself says so, in the log.
termux-wake-lock >> "$LOG" 2>&1 || true

proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- python3 -u /aiwa_widget/backend/aiwa_server.py >> "$LOG" 2>&1
code=$?
echo "=== server stopped, exit code $code — $(date) ===" >> "$LOG"
if [ "$code" -ne 0 ]; then
  echo "If Ubuntu is not installed yet, run bootstrap.sh by hand once:" >> "$LOG"
  echo "  curl -fsSL https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/backend/bootstrap.sh | bash" >> "$LOG"
fi
exit "$code"
