#!/data/data/com.termux/files/usr/bin/bash
# The FAST path — meant to be triggered by the Aiwa widget's own
# "Démarrer" button via Termux's real RUN_COMMAND automation API (see
# TermuxLauncher.kt), not typed by hand. Does none of the install/
# reinstall work setup-termux-proot.sh does (pkg update, apt-get
# update, npm install --force on every single call would make every
# "Démarrer" tap slow) — it only starts the ALREADY-installed backend
# inside the ALREADY-installed Ubuntu image. Run bootstrap.sh (which
# runs setup-termux-proot.sh) by hand at least once first; this will
# fail loudly if that was never done.
set -euo pipefail

DISTRO=ubuntu
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! proot-distro list --installed 2>/dev/null | grep -q "^$DISTRO"; then
  echo "Ubuntu isn't installed yet — run bootstrap.sh by hand once first:"
  echo "  curl -fsSL https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/backend/bootstrap.sh | bash"
  exit 1
fi

termux-wake-lock || true

exec proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- python3 /aiwa_widget/backend/aiwa_server.py
