#!/bin/zsh
# Build the TV app, install it on an Android TV over adb and push its config (music server + shared secret).
#
#   MUSIC_SERVER=http://<server-ip>:8097 scripts/deploy-music.sh <tv-ip> ["living room"]
#
# The room name decides whether the TV drives the lights (only "living room" does). The secret is read from
# ~/.config/living-room-tv/hub.secret, the same file the server reads.
set -euo pipefail
cd "${0:A:h}/.."
HOST=${1:?usage: deploy-music.sh <tv-ip> [room]}
ROOM=${2:-living room}
SERVER=${MUSIC_SERVER:?set MUSIC_SERVER, e.g. http://<server-ip>:8097}
SERIAL="$HOST:5555"
PKG=com.livingroomtv.music
DEST=/sdcard/Android/data/$PKG/files

adb connect "$SERIAL" >/dev/null
[[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" == device ]] || { echo "TV $HOST not reachable over adb"; exit 1; }
./gradlew :app:assembleRelease -q
adb -s "$SERIAL" install -r app/build/outputs/apk/release/app-release.apk
TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT
python3 -c 'import json,os,sys; json.dump({"server": sys.argv[2], "room": sys.argv[3], "secret": open(os.path.expanduser("~/.config/living-room-tv/hub.secret")).read().strip()}, open(sys.argv[1], "w"))' "$TMP" "$SERVER" "$ROOM"
adb -s "$SERIAL" shell mkdir -p "$DEST"
adb -s "$SERIAL" push "$TMP" "$DEST/config.json" >/dev/null
adb -s "$SERIAL" shell pm grant $PKG android.permission.RECORD_AUDIO   # the visualizers read our own output (Visualizer)
echo "Music installed on $HOST"
