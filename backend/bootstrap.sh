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

exec bash "$REPO_DIR/backend/setup-termux-proot.sh"
