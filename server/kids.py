#!/usr/bin/env python3
"""The kids' dance music, found in the family's YouTube (video app) history (Mitchell, Oct 21 2026: "a place where we can
listen exclusively to the kids' music that we play a lot ... Lightning McQueen songs, some official, some not ... a song
about fruit ... Miss Rachel ... some are just contemporary modern songs that suit kids and get their bodies moving ...
Gangnam Style and the We Like to Party song").

    /srv/music/venv/bin/python kids.py --fetch   fetch the watch history (kids/history.json)
    /srv/music/venv/bin/python kids.py           rebuild kids/kids.json from the history and kids/labels.json
    /srv/music/venv/bin/python kids.py --show    print the list

Claude labels each video once (music/label_kids.py, run on Mitchell's Mac, which has the claude command; he asked for
Claude rather than the local model). Labels:
  kids_song     music made for children (Wiggles, Ms Rachel, DJ Raphi, Super Simple Songs, Disney songs ...)
  family_dance  an upbeat, clean pop/party song a family dances to (Gangnam Style, We Like to Party, Golden ...)
  kids_video    a kids' show, episode, read-aloud, toy or race video: not music, left out
  grown_music   music for the grown-ups, left out
  other         everything else, left out
A family_dance song counts only when it was played among kids' videos (within 3 entries of one) or 3+ times, so the
grown-ups' own listening stays out. Ranked by how often it was played. Played as sound only, like everything else.
"""
import collections
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ythistory  # noqa: E402

DIR = "/srv/music/kids"
LABELS = os.path.join(DIR, "labels.json")
OUT = os.path.join(DIR, "kids.json")
KINDS = {"kids_song", "family_dance", "kids_video", "grown_music", "other"}


def fetch():
    os.makedirs(DIR, exist_ok=True)
    hist = ythistory.history(40)
    json.dump(hist, open(os.path.join(DIR, "history.json"), "w"))
    print(f"{len(hist)} history entries")


def build():
    hist = json.load(open(os.path.join(DIR, "history.json")))
    labels = json.load(open(LABELS)) if os.path.exists(LABELS) else {}
    missing = len({v["id"] for v in hist} - set(labels))
    if missing:
        print(f"{missing} videos not labelled yet (run music/label_kids.py on the Mac)")
    plays = collections.Counter(v["id"] for v in hist)
    info = {v["id"]: v for v in hist}
    kidsy = [labels.get(v["id"], {}).get("label") in ("kids_song", "kids_video") for v in hist]
    near_kids = set()
    for i, v in enumerate(hist):                                  # history is newest first: neighbours were watched around it
        if any(kidsy[j] for j in range(max(0, i - 3), min(len(hist), i + 4)) if j != i):
            near_kids.add(v["id"])
    songs = []
    for vid, n in plays.items():
        lab = labels.get(vid, {})
        keep = lab.get("label") == "kids_song" or (lab.get("label") == "family_dance" and (vid in near_kids or n >= 3))
        if keep:
            v = info[vid]
            name = lab.get("song") or v["title"]
            title, _, artist = name.partition(" - ")
            songs.append({"v": vid, "title": title.strip() or v["title"], "artist": artist.strip() or v["channel"],
                          "plays": n, "kind": lab["label"], "channel": v["channel"], "original": v["title"]})
    by_song = {}                                                   # one entry per song: its uploads' plays added up,
    for s in sorted(songs, key=lambda s: -s["plays"]):             # played as the most-played upload
        key = re.sub(r"[^a-z0-9]+", " ", re.sub(r"\(.*?\)", "", s["title"].lower())).strip()
        if key in by_song:
            by_song[key]["plays"] += s["plays"]
        else:
            by_song[key] = dict(s)
    songs = sorted(by_song.values(), key=lambda s: -s["plays"])
    json.dump({"built": int(time.time()), "songs": songs}, open(OUT, "w"))
    print(f"{len(songs)} kids' songs ({sum(1 for s in songs if s['kind'] == 'family_dance')} family dance songs)")


if __name__ == "__main__":
    if "--show" in sys.argv:
        for s in json.load(open(OUT))["songs"]:
            print(s["plays"], s["kind"], "|", s["title"], "|", s["artist"], "|", s["original"])
    elif "--fetch" in sys.argv:
        fetch()
    else:
        build()
