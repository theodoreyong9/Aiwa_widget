#!/data/data/com.termux/files/usr/bin/bash
# Downloads the latest Aiwa APK and leaves it where the phone's Files app can open it. Run by bootstrap.sh, and by hand:
#   bash ~/aiwa_widget/backend/install-apk.sh          (add --open to also hand it to Android's installer from here)
#
# It does NOT open the installer by itself: you pick Aiwa_widget.apk in the Files app (Downloads) and tap
# Install/Update, which is how an install from outside Termux is meant to go. It also makes sure that
# "it did not download" can be told from "it did":
#  - it checks what it got (a real APK is megabytes: a failed or cut transfer is not kept, and is said so);
#  - it puts ONE file, Aiwa_widget.apk, in the phone's Downloads folder, replaced each time (and keeps
#    $HOME/aiwa-debug.apk, inside Termux); Termux needs the storage permission for that, which it asks for;
#  - it prints one last line saying where the file is.
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
if [ ! -d "$DL" ]; then
  # Termux cannot see the phone's Downloads folder until the storage permission is granted: ask for it, and wait
  # for the answer (the folder appears once it is given).
  termux-setup-storage >/dev/null 2>&1 || true
  waited=0
  while [ ! -d "$DL" ] && [ "$waited" -lt "${AIWA_STORAGE_WAIT:-30}" ]; do sleep 1; waited=$((waited + 1)); done
fi
if [ -d "$DL" ]; then
  rm -f "$DL/Aiwa_widget.apk" 2>/dev/null
  if cp -f "$APK_PATH" "$DL/Aiwa_widget.apk" 2>/dev/null; then
    echo "APK: ready — open the Files app, go to Downloads, tap Aiwa_widget.apk, then Install/Update. (Replaced, not duplicated.)"
  else
    echo "APK: could not write Downloads/Aiwa_widget.apk (an older file of that name may belong to another app, such as your browser): delete it in the Files app, then run this again."
  fi
  others=$(ls -1 "$DL" 2>/dev/null | grep -E '^Aiwa_widget.+\.apk$' | tr '\n' ' ')
  [ -n "$others" ] && echo "APK: other copies of the app in Downloads, not made by this script (older builds — delete them in the Files app): $others"
else
  echo "APK: Termux cannot see the phone's Downloads folder yet (storage permission). Accept it when Android asks — or run termux-setup-storage — then run this script again."
fi

# Only when asked: hand the file to Android's installer from here.
if [ "${1:-}" = "--open" ]; then
  pkg install -y termux-api >/dev/null 2>&1 || true
  if termux-open "$APK_PATH" 2>/dev/null; then
    echo "APK: the Android installer was asked to open it — tap Install/Update when it appears."
  else
    echo "APK: the installer did not open from here (the Termux:API app is needed for that): use the Files app as above."
  fi
fi
exit 0
