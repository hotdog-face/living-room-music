#!/usr/bin/env python3
"""Label the family's YouTube history for the Music app's Kids section, with Claude (Mitchell, Oct 21 2026: "use claude
for that. my local model is weak"). Runs on a computer (it has the claude command); the history and labels live on
the music server in /srv/music/kids. Only videos not labelled before are sent, so later runs are small.

    python3 tools/label_kids.py            fetch new history on the music server, label the new videos, rebuild the Kids list
    python3 tools/label_kids.py --test     label 12 videos and print them (nothing saved)
"""
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

HOST = os.environ.get("MUSIC_HOST", "music-server")   # ssh host of the music server
DIR = "/srv/music/kids"
BATCH = 120
MODEL = "sonnet"

PROMPT = """You sort a family's YouTube watch history (two small kids; the parents dance with them in the living room).
For each video (title | channel) give one label:
kids_song: a song or music video made for young children: The Wiggles, Ms Rachel songs, DJ Raphi, Super Simple Songs, Cocomelon,
  Sesame Street songs, Disney/Pixar movie songs (e.g. "Real Gone" or "Life Is a Highway" from Cars, "Hawaiian Roller Coaster Ride"),
  fan-made Lightning McQueen songs, truck and vehicle songs for kids, dance songs for kids ("Freeze Dance").
family_dance: a well-known upbeat, clean pop, party or novelty song a family dances to with small children
  (Gangnam Style, We Like to Party (Vengaboys), Golden from KPop Demon Hunters, Happy, Can't Stop the Feeling, YMCA ...).
kids_video: a kids' show or episode, a compilation of movie clips, a read-aloud book, a learning video that's mostly talking,
  toy, Hot Wheels or race videos. Not music even if a song plays in it.
grown_music: any other music (rock, rap, jazz, electronic, DJ sets, adult pop) that isn't clearly a family dance song.
other: anything else (talk, news, tech, gaming, comedy).
Also "song": for kids_song and family_dance, a clean short "Title - Artist" name ("Real Gone - Sheryl Crow (Cars)",
"The Fruit Song - DJ Raphi"); otherwise "".
Reply with JSON only, no prose: {"items": [{"n": 1, "label": "...", "song": "..."}, ...]} covering every numbered line."""

KINDS = {"kids_song", "family_dance", "kids_video", "grown_music", "other"}


def ssh(cmd, data=None):
    return subprocess.run(["ssh", "-o", "BatchMode=yes", HOST, cmd], input=data, capture_output=True, text=True, check=True).stdout


def ask(batch):
    lines = "\n".join(f"{i + 1}. {v['title']} | {v['channel']}" for i, v in enumerate(batch))
    for attempt in range(3):
        r = subprocess.run(["claude", "-p", "--model", MODEL, PROMPT + "\n\nVideos:\n" + lines],
                           capture_output=True, text=True, timeout=900)
        m = re.search(r"\{.*\}", r.stdout, re.S)
        try:
            items = json.loads(m.group(0))["items"]
            out = {}
            for it in items:
                n = int(it.get("n", 0))
                if 1 <= n <= len(batch):
                    lab = it.get("label") if it.get("label") in KINDS else "other"
                    out[batch[n - 1]["id"]] = {"label": lab, "song": (it.get("song") or "").strip()}
            if len(out) >= len(batch) * 0.9:
                return out
        except Exception as e:
            print("  retry:", e, r.stderr[-200:], flush=True)
    return {}


def main():
    test = "--test" in sys.argv
    if not test:
        print(ssh(f"cd /srv/music && venv/bin/python kids.py --fetch").strip())
    hist = json.loads(ssh(f"cat {DIR}/history.json"))
    labels = {} if test else json.loads(ssh(f"cat {DIR}/labels.json 2>/dev/null || echo '{{}}'"))
    todo = list({v["id"]: v for v in hist if v["id"] not in labels}.values())
    if test:
        todo = todo[:12]
    print(f"{len(todo)} videos to label with Claude ({MODEL})", flush=True)
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    done = 0
    with ThreadPoolExecutor(6) as pool:
        for got in pool.map(ask, batches):
            labels.update(got)
            done += 1
            print(f"  batch {done}/{len(batches)}: {len(got)} labelled", flush=True)
            if not test and done % 6 == 0:                       # save as it goes
                ssh(f"cat > {DIR}/labels.json", json.dumps(labels))
    if test:
        for v in todo:
            print(labels.get(v["id"]), "|", v["title"], "|", v["channel"])
        return
    ssh(f"cat > {DIR}/labels.json", json.dumps(labels))
    print(ssh("cd /srv/music && venv/bin/python kids.py").strip())


if __name__ == "__main__":
    main()
