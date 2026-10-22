#!/usr/bin/env python3
"""Sign the music server in to YouTube Music: paste the request headers copied from Chrome's Network panel, then Ctrl-D.
Takes Chrome's two-line layout ("cookie" on one line, its value on the next), "name: value" lines, or a mix, and skips
anything else (a stray value at the top, Chrome's "Decoded:" notes). Prints only whether it worked, never the values.

    /srv/music/venv/bin/python /srv/music/signin.py
"""
import json
import os
import re
import sys

from ytmusicapi import YTMusic
from ytmusicapi.setup import setup_browser

OUT = os.path.expanduser("~/.config/living-room-tv/ytmusic-browser.json")
BRAND = os.path.expanduser("~/.config/living-room-tv/ytmusic-brand")   # a brand channel's page id (not secret)
KEEP = {"x-goog-pageid", "accept", "accept-language", "authorization", "content-type", "cookie", "origin", "referer", "x-goog-authuser",
        "x-goog-visitor-id", "x-origin", "x-youtube-client-name", "x-youtube-client-version", "x-youtube-bootstrap-logged-in"}
DESKTOP = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
           "Chrome/155.0.0.0 Safari/537.36")                     # the desktop site, even if Chrome was in phone view

print("Paste the request headers, then press Enter and Ctrl-D:", file=sys.stderr)
lines = [l.strip() for l in sys.stdin.read().splitlines() if l.strip()]
headers = {}
i = 0
while i < len(lines):
    line = lines[i]
    m = re.match(r"^:?([A-Za-z0-9-]+):\s+(.+)$", line)
    if m and m.group(1).lower() in KEEP:                         # "name: value"
        headers[m.group(1).lower()] = m.group(2)
        i += 1
    elif line.lower() in KEEP and i + 1 < len(lines):           # Chrome's two lines
        headers[line.lower()] = lines[i + 1]
        i += 2
    else:
        i += 1
missing = [k for k in ("cookie", "x-goog-authuser") if k not in headers]
if missing:
    sys.exit(f"Didn't find {', '.join(missing)} in what was pasted. Copy the Request headers again (down past the cookie).")
headers["user-agent"] = DESKTOP
brand = headers.pop("x-goog-pageid", None)                     # YouTube Music as a brand channel: pass it as the user
setup_browser(OUT, "\n".join(f"{k}: {v}" for k, v in headers.items()))
os.chmod(OUT, 0o600)
if brand:
    open(BRAND, "w").write(brand)
elif os.path.exists(BRAND):
    os.remove(BRAND)
try:
    yt = YTMusic(OUT, user=brand) if brand else YTMusic(OUT)
    n = len(yt.get_liked_songs(limit=2000).get("tracks") or [])
    who = (yt.get_account_info() or {}).get("accountName") or "?"
    print(f"SIGNED-IN-OK as {who}{' (brand channel)' if brand else ''}, Google account #{headers.get('x-goog-authuser')}: {n} liked songs")
except Exception as e:
    sys.exit(f"Saved, but YouTube Music didn't accept it: {type(e).__name__}. Try copying fresh headers.")
