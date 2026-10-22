#!/bin/zsh
# Install (or update) the music server on a Linux box over ssh: /srv/music (venv with ytmusicapi + yt-dlp; node for yt-dlp).
#
#   MUSIC_HOST=<ssh-host> scripts/install-music.sh
#
# Rollback: ssh <ssh-host> 'sudo systemctl disable --now livingroomtv-music'
set -euo pipefail
cd "${0:A:h}/.."
H=${MUSIC_HOST:?set MUSIC_HOST to the server's ssh host}
U=$(ssh "$H" whoami)
scp -q server/music.py server/signin.py server/kids.py server/ythistory.py "$H":/srv/music/
sed "s/__USER__/$U/g" scripts/livingroomtv-music.service | ssh "$H" 'cat > /tmp/livingroomtv-music.service'
ssh "$H" 'sudo install -m 644 /tmp/livingroomtv-music.service /etc/systemd/system/ &&
  /srv/music/venv/bin/pip install -q -U ytmusicapi "yt-dlp[default]" paho-mqtt pillow &&
  sudo systemctl daemon-reload && sudo systemctl enable livingroomtv-music >/dev/null && sudo systemctl restart livingroomtv-music &&
  sleep 3 && curl -s -m 10 http://127.0.0.1:8097/health && echo'
