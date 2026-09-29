#!/data/data/com.termux/files/usr/bin/bash
# The ONE command a user ever needs to run in Termux, for both the
# first install and every later update — from wherever they happen to
# be, e.g. wherever Termux resumed their last session in:
#   curl -fsSL https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/backend/bootstrap.sh | bash
#
# Always installs/updates to the SAME fixed location ($HOME/aiwa_widget)
# regardless of the current directory, instead of depending on the user
# remembering where they last cloned it — running `git clone` again
# from inside an existing clone (an easy mistake when Termux resumes in
# whatever directory you left it in) used to produce a nested
# aiwa_widget/aiwa_widget mess.
set -euo pipefail

REPO_DIR="$HOME/aiwa_widget"

if [ -d "$REPO_DIR/.git" ]; then
  echo "== Updating the existing install at $REPO_DIR =="
  git -C "$REPO_DIR" pull --ff-only
else
  echo "== Installing into $REPO_DIR =="
  pkg install -y git
  git clone https://github.com/theodoreyong9/aiwa_widget "$REPO_DIR"
fi

# The widget/app's "▶ Démarrer" button needs Termux's own separate
# opt-in for RUN_COMMAND (reported live: "Need allow external apps in
# termux properties files") — a real security gate of Termux's own,
# off by default, that no manifest permission on Aiwa's side can grant
# on its own. Folding it in here means it's set the same one time as
# everything else instead of a manual edit the user has to remember.
echo "== Allowing Aiwa to trigger Termux commands (RUN_COMMAND) =="
mkdir -p "$HOME/.termux"
PROPS="$HOME/.termux/termux.properties"
if ! grep -q "^allow-external-apps *= *true" "$PROPS" 2>/dev/null; then
  echo "allow-external-apps=true" >> "$PROPS"
  termux-reload-settings || true
fi

# Folds the APK install/update into this same one command, instead of
# needing a separate manual "open GitHub in a browser, download,
# install" step — reported live as an actual usability complaint.
# Android still requires a real, explicit tap on its own installer
# prompt for a sideloaded APK (termux-open just hands off to that
# system UI, it cannot silently install anything on its own — the same
# kind of real OS security gate as the RUN_COMMAND permission
# elsewhere in this project), so this can prompt but not finish the
# install unattended.
echo "== Downloading the latest Aiwa APK =="
APK_PATH="$HOME/aiwa-debug.apk"
# The APK is a file of the rolling GitHub release (the committed copy is the fallback for older builds).
if curl -fsSL -o "$APK_PATH" https://github.com/theodoreyong9/Aiwa_widget/releases/download/android-latest/Aiwa_widget.apk \
  || curl -fsSL -o "$APK_PATH" https://raw.githubusercontent.com/theodoreyong9/aiwa_widget/main/aiwa-debug.apk; then
  pkg install -y termux-api >/dev/null 2>&1 || true
  echo "== Opening the Android installer — tap Install/Update when it appears =="
  termux-open "$APK_PATH" || echo "Could not open the installer automatically (install the Termux:API app for this to work) — open $APK_PATH by hand from a file manager instead."
else
  echo "Could not download the APK (network issue?) — skipping. You can install it by hand later from https://github.com/theodoreyong9/Aiwa_widget/releases/download/android-latest/Aiwa_widget.apk"
fi

exec bash "$REPO_DIR/backend/setup-termux-proot.sh"
