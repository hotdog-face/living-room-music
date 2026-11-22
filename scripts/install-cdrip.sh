#!/bin/zsh
# Install (or update) the CD ripper on the music server: put a music CD in its USB drive and it rips into the music
# library by itself (cd/cdrip.py, started by udev through cdrip@<drive>.service).
#
#   MUSIC_HOST=<ssh-host> scripts/install-cdrip.sh
#
# The library paths default to the ones in cd/cdrip.py; set CD_LIBRARY / CD_PLEX_LIBRARY in the service to change them.
# Rollback: ssh <ssh-host> 'sudo rm /etc/udev/rules.d/99-cdrip.rules && sudo udevadm control --reload'
set -euo pipefail
cd "${0:A:h}/.."
H=${MUSIC_HOST:?set MUSIC_HOST to the server's ssh host}
U=$(ssh "$H" whoami)
ssh "$H" 'sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q cyanrip flac eject python3-libdiscid >/dev/null && mkdir -p /srv/music/cd/staging'
scp -q cd/cdrip.py "$H":/srv/music/cd/
sed "s/__USER__/$U/g" cd/cdrip@.service | ssh "$H" 'cat > /tmp/cdrip@.service'
scp -q cd/99-cdrip.rules "$H":/tmp/
ssh "$H" 'sudo install -m 644 /tmp/cdrip@.service /etc/systemd/system/ && sudo install -m 644 /tmp/99-cdrip.rules /etc/udev/rules.d/ &&
  sudo systemctl daemon-reload && sudo udevadm control --reload &&
  { [ -s /srv/music/cd/driveoffsets.htm ] || curl -s -m 30 -A "Mozilla/5.0" http://www.accuraterip.com/driveoffsets.htm -o /srv/music/cd/driveoffsets.htm; } &&
  echo installed; ls /dev/sr* 2>/dev/null || echo "no CD drive plugged in yet"'
