#!/data/data/com.termux/files/usr/bin/bash
# EXPERIMENTAL — not verified against a real device by this project.
#
# Claude Code's native binary is simply not published for
# linux-arm64-android at all (confirmed live: "Native binaries for
# linux-arm64-android are not available on this release channel" —
# Anthropic's own upstream distribution, nothing this repo can patch).
# Termux's own Node.js build appears to be the thing reporting
# "android" as the platform in the first place (Node's own
# process.platform never natively returns "android" — this is almost
# certainly a Termux-specific patch to its packaged Node). The
# workaround: run a REAL Ubuntu userland inside Termux via proot-distro
# (no network namespace, so 127.0.0.1 here is genuinely the same
# loopback the Aiwa app itself uses) and install a normal, unpatched
# Node.js inside THAT — which should report plain linux/arm64, a
# platform Anthropic does publish a real binary for. This does not
# change the actual kernel (still Android's own — proot is filesystem/
# syscall sandboxing, not virtualization), only the userland Node
# build, which is the part that matters here.
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
proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- bash -c '
set -euo pipefail
apt-get update -y
apt-get install -y curl python3
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
npm config set allow-scripts=@anthropic-ai/claude-code --location=user 2>/dev/null || true
npm install -g @anthropic-ai/claude-code --force
if ! claude --version >/dev/null 2>&1; then
  echo "claude still is not working inside Ubuntu — running its own postinstall directly."
  node "$(npm root -g)/@anthropic-ai/claude-code/install.cjs"
fi
claude --version
'

echo "== Logging into Claude Code (only needed once; follow its own prompts) =="
echo "If this is the first time, run:"
echo "  proot-distro login $DISTRO -- claude"
echo "by hand to authenticate before continuing — the backend assumes you're already logged in."

echo "== Preventing Android from killing Termux in the background =="
termux-wake-lock || echo "termux-wake-lock unavailable — install the Termux:API app/package for this to work."

echo "== Starting the Aiwa backend on 127.0.0.1:8787 (inside $DISTRO) =="
echo "Leave this running for as long as you want the app to work."
exec proot-distro login "$DISTRO" --bind "$REPO_DIR:/aiwa_widget" -- python3 /aiwa_widget/backend/aiwa_server.py
