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

# Ubuntu present? A directory test, NOT a `proot-distro login ... -- true`:
# that check used to start proot a first time just to look, then a second
# time for real — each start costs seconds on a phone, so every launch of
# the backend was about twice as slow as it had to be.
if [ ! -d "${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/installed-rootfs/$DISTRO" ]; then
  echo "Ubuntu isn't installed yet — run bootstrap.sh by hand once first:"
  echo "  curl -fsSL https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/backend/bootstrap.sh | bash"
  exit 1
fi

termux-wake-lock || true

# The server's own output goes to a file (this script usually runs with no
# terminal at all, through Termux's RUN_COMMAND): `cat ~/aiwa_backend.log`
# shows why a start failed.
exec proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- python3 -u /aiwa_widget/backend/aiwa_server.py > "$HOME/aiwa_backend.log" 2>&1
