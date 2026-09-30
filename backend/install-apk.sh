#!/data/data/com.termux/files/usr/bin/bash
# Downloads the latest Aiwa APK and hands it to Android's installer. Run by bootstrap.sh, and by hand:
#   bash ~/aiwa_widget/backend/install-apk.sh
#
# Three things it does so that "it did not download" can be told from "it did, and nothing opened":
#  - it checks what it got (a real APK is megabytes: a failed or cut transfer is not kept, and is said so);
#  - it keeps the file at a place you can find — $HOME/aiwa-debug.apk, and a copy (one file, replaced each time) in
#    the phone's own Downloads folder when Termux was given storage access (termux-setup-storage, once);
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

# ONE file of a fixed name, replaced each time: the older copy is removed before the new one is written.
# (A browser or the Files app makes "Aiwa_widget (1).apk" when the name is taken — this script never does.)
DL="$HOME/storage/downloads"
if [ -d "$DL" ]; then
  rm -f "$DL/Aiwa_widget.apk" 2>/dev/null
  if cp -f "$APK_PATH" "$DL/Aiwa_widget.apk" 2>/dev/null; then
    echo "APK: the latest is in the phone's Downloads folder as Aiwa_widget.apk (replaced, not duplicated): open it from the Files app if no installer appears."
  else
    echo "APK: could not write Downloads/Aiwa_widget.apk (an older file of that name may belong to another app, such as your browser): delete it in the Files app, then run this again."
  fi
  others=$(ls -1 "$DL" 2>/dev/null | grep -E '^Aiwa_widget.+\.apk$' | tr '\n' ' ')
  [ -n "$others" ] && echo "APK: other copies of the app in Downloads, not made by this script (older builds — delete them in the Files app): $others"
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
