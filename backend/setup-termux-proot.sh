#!/data/data/com.termux/files/usr/bin/bash
# EXPERIMENTAL — not verified against a real device by this project.
#
# Claude Code's native binary is simply not published for
# linux-arm64-android at all (confirmed live: "Native binaries for
# linux-arm64-android are not available on this release channel" —
# Anthropic's own upstream distribution, nothing this repo can patch).
#
# The original theory here was that Termux's OWN Node.js build was
# specifically patched to report platform "android", and that a plain
# Ubuntu userland inside proot-distro plus a vanilla NodeSource Node
# would report plain "linux" instead. Confirmed live to be WRONG (or at
# least incomplete): even after this script's own Node "install" step,
# `proot-distro login ubuntu -- node -e "console.log(process.platform)"`
# still printed "android", with ANDROID_ROOT=/system and
# ANDROID_DATA=/data also visible inside that login shell. proot does
# not virtualize the kernel (it's filesystem/syscall sandboxing only),
# and — confirmed live — `proot-distro login` does not reset PATH or
# Android-specific env vars inherited from the outer Termux process
# either. Combined, that means `command -v node` below was finding
# TERMUX'S OWN node binary (still reachable via the leaked PATH), so
# the "already installed" check skipped installing a real one inside
# Ubuntu at all — not a kernel-visibility problem, an environment-leak
# problem. Fixed by forcing a clean PATH and clearing the Android-
# specific vars for this login, and by checking node's actual reported
# platform instead of just its presence on PATH (command -v alone
# already burned us once before, for `claude --version` vs. `command -v
# claude` — same class of mistake, now fixed here too).
set -euo pipefail

DISTRO=ubuntu
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== Installing proot-distro via Termux's own package manager =="
pkg update -y
pkg install -y proot-distro

echo "== Installing Ubuntu inside proot-distro (large download, first time only) =="
# Checking via `proot-distro list --installed` and grepping for the
# distro name turned out unreliable in practice — reported live: the
# script tried to install anyway and crashed on proot-distro's own
# "container 'ubuntu' already exists" error, meaning that check's
# output-parsing didn't recognize an install that was already there.
# Testing whether we can actually log in is a direct functional check
# instead of depending on `list`'s exact text format.
if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
  echo "$DISTRO already installed — skipping."
else
  proot-distro install "$DISTRO" || true
  if ! proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
    echo "Ubuntu container looks broken or only half-installed."
    echo "Run 'proot-distro reset $DISTRO' by hand, then re-run this script."
    exit 1
  fi
fi

echo "== Installing Node.js, Python, and the Claude Code CLI INSIDE $DISTRO =="
# Ubuntu's own apt Node.js is usually too old for the Claude Code CLI —
# NodeSource's setup script is the standard way to get a modern one.
# `env -u ...` + a forced PATH below strip the Android-specific vars
# and Termux's own bin dir that were confirmed live to leak into this
# login shell otherwise (see the file header comment for the full
# story) — without this, `command -v node` finds Termux's own node
# instead of installing a real one inside Ubuntu.
proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- \
  env -u ANDROID_ROOT -u ANDROID_DATA -u ANDROID_ASSETS -u ANDROID_STORAGE \
      -u ANDROID_ART_ROOT -u ANDROID_I18N_ROOT -u ANDROID_TZDATA_ROOT \
      -u ANDROID_RUNTIME_ROOT -u BOOTCLASSPATH -u DEX2OATBOOTCLASSPATH \
      -u EXTERNAL_STORAGE \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  bash -c '
set -euo pipefail
apt-get update -y
apt-get install -y curl python3

node_is_real_linux() {
  command -v node >/dev/null 2>&1 && [ "$(node -p process.platform 2>/dev/null)" = "linux" ]
}

if ! node_is_real_linux; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

if ! node_is_real_linux; then
  echo "node still does not report platform=linux after installing — something is still leaking through:"
  command -v node || echo "(no node on PATH at all)"
  node -p process.platform || true
  exit 1
fi

npm config set allow-scripts=@anthropic-ai/claude-code --location=user 2>/dev/null || true
npm install -g @anthropic-ai/claude-code --force
if ! claude --version >/dev/null 2>&1; then
  echo "claude still is not working inside Ubuntu — running its own postinstall directly."
  node "$(npm root -g)/@anthropic-ai/claude-code/install.cjs"
fi
claude --version
'

echo "== Claude account =="
echo "Nothing to type here: once the Aiwa widget is on your home screen, it says"
echo "\"Claude n'est pas connecté\" until you tap it and approve the login in your browser."
echo "(By hand, if you ever prefer: proot-distro login $DISTRO -- claude)"

echo "== Preventing Android from killing Termux in the background =="
termux-wake-lock || echo "termux-wake-lock unavailable — install the Termux:API app/package for this to work."

# A backend started earlier (by the app, or by the last run of this script) still holds port 8787 and still
# runs the OLD code, which an update just replaced on disk: starting a second one used to die with
# "Address already in use" and leave the old one answering. Stop it first, and wait for the port to be free.
echo "== Stopping a backend that is already running (it would keep the old code) =="
pkill -f "[a]iwa_server.py" >/dev/null 2>&1 || true
for i in $(seq 1 20); do
  curl -sf http://127.0.0.1:8787/api/status >/dev/null 2>&1 || break
  sleep 0.5
done

# Something answers again: the app started a backend meanwhile, on the code that is now on disk. Nothing to add.
if curl -sf http://127.0.0.1:8787/api/status >/dev/null 2>&1; then
  echo "A backend answers on 127.0.0.1:8787 (started meanwhile, with the updated code): nothing more to start."
  [ -f "$HOME/.aiwa_apk_last.txt" ] && { echo "== The Aiwa APK (from the step at the start) =="; cat "$HOME/.aiwa_apk_last.txt"; }
  exit 0
fi

# What the APK step said, again: it scrolled away under the long setup above.
if [ -f "$HOME/.aiwa_apk_last.txt" ]; then
  echo "== The Aiwa APK (from the step at the start) =="
  cat "$HOME/.aiwa_apk_last.txt"
fi

echo "== Starting the Aiwa backend on 127.0.0.1:8787 (inside $DISTRO) =="
echo "Leave this running for as long as you want the app to work."
exec proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- python3 -u /aiwa_widget/backend/aiwa_server.py
