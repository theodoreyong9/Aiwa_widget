#!/data/data/com.termux/files/usr/bin/bash
# Downloads the latest Aiwa APK and hands it to Android's installer. Run by bootstrap.sh, and by hand:
#   bash ~/aiwa_widget/backend/install-apk.sh
#
# Three things it does so that "it did not download" can be told from "it did, and nothing opened":
#  - it checks what it got (a real APK is megabytes: a failed or cut transfer is not kept, and is said so);
#  - it keeps the file at a place you can find — $HOME/aiwa-debug.apk, and a copy in the phone's own Downloads
#    folder when Termux was given storage access (termux-setup-storage, once) — so the Files app can open it;
#  - it prints one last line saying which of those happened.
# Android still wants a tap of its own on the installer: nothing here can install silently.
set -uo pipefail

APK_URL="${AIWA_APK_URL:-https://github.com/theodoreyong9/Aiwa_widget/releases/download/android-latest/Aiwa_widget.apk}"
APK_PATH="$HOME/aiwa-debug.apk"
PART="$APK_PATH.part"
MIN_BYTES=1000000

rm -f "$PART"
got=0
if curl -fL --retry 3 --connect-timeout 20 -o "$PART" "$APK_URL" && [ "$(wc -c < "$PART" 2>/dev/null || echo 0)" -ge "$MIN_BYTES" ]; then
  got=1
fi
if [ "$got" -ne 1 ]; then
  rm -f "$PART"
  echo "APK: NOT downloaded (network, or GitHub did not answer). Try again, or open this address in the phone's browser:"
  echo "     $APK_URL"
  exit 1
fi
mv -f "$PART" "$APK_PATH"
size=$(( $(wc -c < "$APK_PATH") / 1024 / 1024 ))
echo "APK: downloaded, ${size} MB -> $APK_PATH"

if [ -d "$HOME/storage/downloads" ] && cp -f "$APK_PATH" "$HOME/storage/downloads/Aiwa_widget.apk" 2>/dev/null; then
  echo "APK: a copy is in the phone's Downloads folder (Aiwa_widget.apk): open it from the Files app if no installer appears."
else
  echo "APK: for a copy in the phone's Downloads folder, run termux-setup-storage once (and accept), then this script again."
fi

pkg install -y termux-api >/dev/null 2>&1 || true
if termux-open "$APK_PATH" 2>/dev/null; then
  echo "APK: the Android installer was asked to open it — tap Install/Update when it appears."
else
  echo "APK: the installer did not open by itself (the Termux:API app is needed for that): open $APK_PATH, or the Downloads copy, from a file manager."
fi
exit 0
