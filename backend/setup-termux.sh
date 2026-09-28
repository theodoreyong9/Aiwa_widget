#!/data/data/com.termux/files/usr/bin/bash
# Run this INSIDE Termux, on the same phone the Aiwa APK is installed
# on: curl/copy this repo (or just this script + aiwa_server.py) onto
# the device, then:
#   bash setup-termux.sh
#
# Installs everything aiwa_server.py needs (Node.js for the Claude Code
# CLI itself, Python to run the server) and starts the server with a
# wake-lock so Android doesn't throttle/kill Termux the moment you
# switch to the Aiwa app. HONEST LIMIT: this does not survive a reboot
# or Termux being force-closed — you run this again to restart it. It
# also assumes `pkg`/apt-style Termux package names, which is how real
# Termux works today but isn't something this project controls.
set -euo pipefail

echo "== Installing Node.js and Python via Termux's own package manager =="
pkg update -y
pkg install -y nodejs python

echo "== Installing the Claude Code CLI =="
# This npm's own "install-scripts" gate blocks the package's postinstall
# by default — which is exactly what downloads claude's real native
# binary — leaving a `claude` file on PATH that exists but doesn't
# work ("claude native binary not installed"). Pre-allowing it here
# means the install below actually gets a working binary the first
# time; `|| true` because older npm versions don't have this config key
# at all, and that must not be fatal under set -e.
npm config set allow-scripts=@anthropic-ai/claude-code --location=user 2>/dev/null || true
# --force: re-running this script (e.g. to update, or after this exact
# script previously succeeded) hits a real npm quirk otherwise — a
# global bin symlink from the prior install already exists on disk, and
# plain `npm install -g` refuses to overwrite it (EEXIST), rather than
# treating a same-package reinstall as the ordinary case it is. This
# makes re-running safe, which is the whole point of a setup script.
npm install -g @anthropic-ai/claude-code --force

if ! command -v claude >/dev/null 2>&1; then
  echo "claude was not found on PATH after npm install — the package"
  echo "name may have changed. Check https://docs.claude.com/claude-code"
  echo "for the current install instructions, then re-run this script."
  exit 1
fi

if ! claude --version >/dev/null 2>&1; then
  echo "claude is on PATH but not actually working yet (its postinstall"
  echo "was skipped despite the config above, or downloaded nothing) —"
  echo "running its own real postinstall directly."
  GLOBAL_NODE_MODULES="$(npm root -g)"
  node "$GLOBAL_NODE_MODULES/@anthropic-ai/claude-code/install.cjs"
fi

echo "== Logging into Claude Code (only needed once; follow its own prompts) =="
claude --version
echo "If this is the first time, run 'claude' once by hand to authenticate"
echo "before continuing — the backend below assumes you're already logged in."

echo "== Preventing Android from killing Termux in the background =="
termux-wake-lock || echo "termux-wake-lock unavailable — install the Termux:API app/package for this to work."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "== Starting the Aiwa backend on 127.0.0.1:8787 =="
echo "Leave this running (this terminal, or a Termux session kept open) for as long as you want the app to work."
exec python3 "$SCRIPT_DIR/aiwa_server.py"
