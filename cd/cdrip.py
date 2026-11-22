#!/usr/bin/env python3
"""CD ripper for Mitchell's CD collection (Oct 31 2026): put a disc in the music server's USB drive and it rips by itself.

    cdrip.py /dev/sr0            rip the disc in that drive (udev starts this as cdrip@sr0.service when a CD goes in)
    cdrip.py --retag <folder>    re-read a folder's tags into the collection (after fixing an unknown disc by hand)

1. Reads the disc's ID and looks it up: MusicBrainz first, then gnudb (the old freedb) for discs MusicBrainz lacks.
2. Already in the library (same artist, album, number of songs)? Just adds it to the collection and ejects.
3. Rips with cyanrip: secure (paranoia), drive offset from AccurateRip's list, each song checked against AccurateRip,
   FLAC with tags, ReplayGain and the cover embedded.
4. Files it with the rest of the music on the NAS, the way the library already is:
       00_FLAC/<Album artist>/<Album>/01 - Song.flac, cover.jpg, Artwork/ (front, back, disc, cd.json), rip log, cue
   Unknown discs go to 00_FLAC/_Unknown CDs/<date> to be named later.
5. Asks Plex to scan that folder, adds the disc to the collection (the Music app's CDs tab) and ejects.

Progress is in /srv/music/cd/status.json (the CDs tab shows it); log in /srv/music/cd/rip.log.
"""
import datetime
import fcntl
import glob
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import threading
import urllib.error
import urllib.parse
import urllib.request

LIBRARY = os.environ.get("CD_LIBRARY", "/mnt/nas/plex/Library/Music Library/00_FLAC")
PLEX_LIBRARY = os.environ.get("CD_PLEX_LIBRARY", "/volume1/Plex/Library/Music Library/00_FLAC")    # the same folder as the Plex container sees it
PLEX = "http://127.0.0.1:32400"
PLEX_SECTION = 8
CONF = os.path.expanduser("~/.config/living-room-tv")
HERE = os.environ.get("CD_DATA", "/srv/music/cd")
STATUS = os.path.join(HERE, "status.json")
COLLECTION = os.path.join(HERE, "collection.json")
OFFSETS = os.path.join(HERE, "driveoffsets.htm")                 # http://www.accuraterip.com/driveoffsets.htm
DRIVE = os.path.join(HERE, "drive.json")                         # {"offset": N} to override the list
STAGING = os.path.join(HERE, "staging")
UA = "LivingRoomCDs/1.0 ( https://github.com/hotdog-face/living-room-music )"  # MusicBrainz asks for a contact link


def log(*a):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + " ".join(str(x) for x in a)
    print(line, flush=True)
    with open(os.path.join(HERE, "rip.log"), "a") as f:
        f.write(line + "\n")


def status(**s):
    s["at"] = int(time.time())
    tmp = STATUS + ".tmp"
    json.dump(s, open(tmp, "w"))
    os.replace(tmp, STATUS)


def get_json(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def download(url, path):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        if len(data) > 1000:
            open(path, "wb").write(data)
            return True
    except Exception as e:
        log("download", url, e)
    return False


def safe(name):
    """A folder/file name that works on the NAS (SMB too): no / \\ : * ? " < > |, no trailing dots or spaces."""
    name = re.sub(r"\s*:\s*", " - ", name or "")                  # "Live: Tokyo" -> "Live - Tokyo"
    name = re.sub(r'[\\/|]+', "-", name)
    name = re.sub(r'[*?"<>]+', "", name)
    name = re.sub(r"\s+", " ", name).strip().rstrip(". ")
    return name[:120] or "Unknown"


def plain(s):
    """MusicBrainz's typographic characters (‐ ’ “ ” …) as plain ones, like the rest of the library."""
    for a, b in (("\u2010", "-"), ("\u2011", "-"), ("\u2012", "-"), ("\u2013", "-"), ("\u2019", "'"), ("\u2018", "'"),
                 ("\u201c", '"'), ("\u201d", '"'), ("\u2026", "..."), ("\u00a0", " ")):
        s = (s or "").replace(a, b)
    return s


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower().replace("&", "and"))


# ---- what's on the disc ------------------------------------------------------------------------------------------------

def read_disc(dev):
    import libdiscid
    d = libdiscid.read(dev)
    return {"mb": d.id, "freedb": d.freedb_id, "toc": d.toc, "first": d.first_track, "last": d.last_track,
            "sectors": d.sectors, "offsets": list(d.track_offsets)}


def musicbrainz(disc):
    """The best MusicBrainz release for this disc: an official CD, US first, then the earliest."""
    url = (f"https://musicbrainz.org/ws/2/discid/{disc['mb']}?toc={urllib.parse.quote(disc['toc'])}"
           "&inc=artist-credits+recordings+release-groups+labels&fmt=json")
    try:
        j = get_json(url)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    releases = j.get("releases") or []
    if not releases:
        return None

    def rank(r):
        exact = any(disc["mb"] in [d.get("id") for d in m.get("discs") or []] for m in r.get("media") or [])
        cd = all((m.get("format") or "").endswith("CD") for m in r.get("media") or [])
        return (not exact, r.get("status") != "Official", not cd, r.get("country") not in ("US", "XW"),
                r.get("date") or "9999")
    r = sorted(releases, key=rank)[0]
    media = r.get("media") or []
    medium = next((m for m in media if disc["mb"] in [d.get("id") for d in m.get("discs") or []]), None)
    if medium is None:                                           # fuzzy TOC match: the medium with this many songs
        n = disc["last"] - disc["first"] + 1
        medium = next((m for m in media if (m.get("track-count") or len(m.get("tracks") or [])) == n), media[0] if media else {})
    credit = lambda ac: plain("".join(a.get("name", "") + a.get("joinphrase", "") for a in ac or []))
    rg = r.get("release-group") or {}
    return {
        "source": "musicbrainz", "release": r["id"], "group": rg.get("id"),
        "artist": credit(r.get("artist-credit")) or "Unknown Artist", "album": plain(r.get("title")) or "Unknown Album",
        "date": r.get("date") or rg.get("first-release-date") or "", "year": (rg.get("first-release-date") or r.get("date") or "")[:4],
        "country": r.get("country"), "label": ((r.get("label-info") or [{}])[0].get("label") or {}).get("name"),
        "barcode": r.get("barcode"), "disc": medium.get("position") or 1, "discs": len(media) or 1,
        "tracks": [{"n": t.get("position") or i + 1, "title": plain(t.get("title")) or f"Track {i + 1}",
                    "artist": credit(t.get("artist-credit")) or credit(r.get("artist-credit"))}
                   for i, t in enumerate(medium.get("tracks") or [])],
        "type": rg.get("primary-type"), "comment": r.get("disambiguation") or "",
    }


def gnudb(disc):
    """freedb's successor, for discs MusicBrainz doesn't know (lots of older and local CDs are only here)."""
    try:                                                         # gnudb only answers registered users (an address
        user = open(os.path.join(CONF, "gnudb.user")).read().strip()   # signed up at gnudb.org); skipped without one
    except OSError:
        return None
    n = disc["last"] - disc["first"] + 1
    secs = disc["sectors"] // 75
    hello = f"hello={urllib.parse.quote_plus(user)}+music-server+LivingRoomCDs+1.0&proto=6"
    q = f"cddb query {disc['freedb']} {n} {' '.join(str(o) for o in disc['offsets'])} {secs}"
    base = "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd="
    try:
        with urllib.request.urlopen(base + urllib.parse.quote_plus(q) + "&" + hello, timeout=30) as r:
            lines = r.read().decode("utf-8", "replace").splitlines()
        if not lines or lines[0][:3] not in ("200", "210", "211"):
            return None
        hit = lines[0][4:] if lines[0].startswith("200") else lines[1]
        cat, did = hit.split()[:2]
        with urllib.request.urlopen(base + urllib.parse.quote_plus(f"cddb read {cat} {did}") + "&" + hello, timeout=30) as r:
            text = r.read().decode("utf-8", "replace")
    except Exception as e:
        log("gnudb", e)
        return None
    f = {}
    for line in text.splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            f[k] = f.get(k, "") + v
    artist, _, album = f.get("DTITLE", "").partition(" / ")
    tracks = []
    for i in range(n):
        t = f.get(f"TTITLE{i}", f"Track {i + 1}")
        ta = artist
        if " / " in t and artist.lower() in ("various", "various artists"):
            ta, _, t = t.partition(" / ")
        tracks.append({"n": i + 1, "title": t.strip(), "artist": ta.strip()})
    return {"source": "gnudb", "artist": artist.strip() or "Unknown Artist", "album": (album or f.get("DTITLE", "")).strip() or "Unknown Album",
            "year": f.get("DYEAR", "").strip(), "date": f.get("DYEAR", "").strip(), "disc": 1, "discs": 1, "tracks": tracks,
            "genre": f.get("DGENRE", "").strip()}


def cover_art(meta, art_dir):
    """Front, back and the disc itself from the Cover Art Archive; the front from iTunes if it has none."""
    os.makedirs(art_dir, exist_ok=True)
    got = {}
    for kind, mbid in (("release", meta.get("release")), ("release-group", meta.get("group"))):
        if not mbid or "front" in got:
            continue
        try:
            imgs = get_json(f"https://coverartarchive.org/{kind}/{mbid}").get("images") or []
        except Exception:
            continue
        for img in imgs:
            types = [t.lower() for t in img.get("types") or []]
            url = (img.get("thumbnails") or {}).get("1200") or (img.get("thumbnails") or {}).get("large") or img.get("image")
            for want, name in (("front", "front.jpg"), ("back", "back.jpg"), ("medium", "disc.jpg")):
                if want in types and want not in got and url and (kind == "release" or want == "front"):
                    if download(url, os.path.join(art_dir, name)):
                        got[want] = name
    if "front" not in got:
        try:
            term = urllib.parse.quote_plus(f"{meta['artist']} {meta['album']}")
            hits = get_json(f"https://itunes.apple.com/search?term={term}&entity=album&limit=5").get("results") or []
            hit = next((h for h in hits if norm(h.get("collectionName", "")).startswith(norm(meta["album"])[:12])), None)
            if hit and download(hit["artworkUrl100"].replace("100x100bb", "1200x1200bb"), os.path.join(art_dir, "front.jpg")):
                got["front"] = "front.jpg"
        except Exception as e:
            log("itunes art", e)
    return got


# ---- the drive ---------------------------------------------------------------------------------------------------------

def drive_model(dev):
    out = subprocess.run(["udevadm", "info", "--query=property", dev], capture_output=True, text=True).stdout
    p = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
    return (p.get("ID_VENDOR") or "").replace("_", " ").strip(), (p.get("ID_MODEL") or "").replace("_", " ").strip()


def drive_offset(dev):
    """The drive's read offset: drive.json if set, else AccurateRip's list of drives (model names match loosely)."""
    try:
        return int(json.load(open(DRIVE))["offset"]), "drive.json"
    except Exception:
        pass
    vendor, model = drive_model(dev)
    want = norm(model)
    if not want or not os.path.exists(OFFSETS):
        return 0, f"unknown drive {vendor} {model}"
    text = open(OFFSETS, encoding="latin-1").read()
    rows = [(html.unescape(n).strip(), int(o)) for n, o in
            re.findall(r'<td[^>]*><font[^>]*>([^<]+)</font></td>\s*<td[^>]*><font[^>]*>([+-]?\d+)</font></td>', text)]
    exact = [(n, o) for n, o in rows if norm(re.split(r"\s-\s", n)[-1]) == want]
    loose = [(n, o) for n, o in rows if len(want) >= 6 and want in norm(n)]
    for n, o in exact or loose:
        return o, f"AccurateRip list: {n}"
    return None, f"{vendor} {model} not in AccurateRip's list"


def find_offset(dev):
    """cyanrip -f: works the offset out from a disc that's in AccurateRip (most are). Saved in drive.json for next time."""
    status(state="looking", message="New drive: working out its offset (first disc only, a few minutes)…")
    p = subprocess.run(["cyanrip", "-d", dev, "-f"], capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=1800)
    m = re.findall(r"Offset of ([+-])(\d+) confirmed", p.stdout + p.stderr)
    if not m:
        log("offset not found:", (p.stdout + p.stderr)[-800:])
        return None
    off = int(m[-1][1]) * (-1 if m[-1][0] == "-" else 1)
    vendor, model = drive_model(dev)
    json.dump({"offset": off, "drive": f"{vendor} {model}", "found": time.strftime("%Y-%m-%d")}, open(DRIVE, "w"))
    return off


def eject(dev):
    subprocess.run(["eject", dev], capture_output=True)


# ---- the collection -------------------------------------------------------------------------------------------------------

def load_collection():
    try:
        return json.load(open(COLLECTION))
    except Exception:
        return []


def save_collection(items):
    tmp = COLLECTION + ".tmp"
    json.dump(items, open(tmp, "w"), indent=1)
    os.replace(tmp, COLLECTION)


def add_to_collection(entry):
    def same(c):
        return (entry.get("disc_id") and c.get("disc_id") == entry["disc_id"]) or \
               (c.get("folder") == entry["folder"] and c.get("disc", 1) == entry.get("disc", 1))
    items = [c for c in load_collection() if not same(c)]
    items.append(entry)
    save_collection(items)


def plex_scan(folder):
    try:
        token = open(os.path.join(CONF, "plex.token")).read().strip()
        rel = os.path.relpath(folder, LIBRARY)
        q = urllib.parse.urlencode({"path": f"{PLEX_LIBRARY}/{rel}", "X-Plex-Token": token})
        urllib.request.urlopen(f"{PLEX}/library/sections/{PLEX_SECTION}/refresh?{q}", timeout=30).read()
        log("plex scan", rel)
    except Exception as e:
        log("plex scan failed", e)


def find_existing(meta):
    """An album folder already in the library for this disc (any capitalisation), with enough songs in it."""
    artist_dir = next((os.path.join(LIBRARY, d) for d in os.listdir(LIBRARY) if norm(d) == norm(safe(meta["artist"]))), None)
    if not artist_dir:
        return None
    for d in os.listdir(artist_dir):
        if norm(d) == norm(safe(meta["album"])) or norm(d).startswith(norm(safe(meta["album"]))) and len(norm(meta["album"])) > 6:
            path = os.path.join(artist_dir, d)
            songs = glob.glob(os.path.join(glob.escape(path), "**", "*.flac"), recursive=True)
            if meta["discs"] == 1 and len(songs) >= len(meta["tracks"]) or meta["discs"] > 1 and any(
                    re.match(rf"{meta['disc']}-\d", os.path.basename(s)) for s in songs):
                return path
    return None


# ---- tags -------------------------------------------------------------------------------------------------------------

def read_tags(path):
    out = subprocess.run(["metaflac", "--export-tags-to=-", path], capture_output=True, text=True).stdout
    tags = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            tags.setdefault(k.upper(), v)
    return tags


def set_tags(path, tags):
    args = ["metaflac"] + [f"--remove-tag={k}" for k in tags] + [f"--set-tag={k}={v}" for k, v in tags.items() if v]
    subprocess.run(args + [path], check=True)


def entry_for(folder, meta, disc, accurate, cover, art, already=False):
    return {
        "disc_id": disc["mb"] if disc else None, "freedb": disc["freedb"] if disc else None,
        "artist": meta["artist"], "album": meta["album"], "year": meta.get("year") or "", "label": meta.get("label"),
        "disc": meta.get("disc", 1), "discs": meta.get("discs", 1), "tracks": [t["title"] for t in meta["tracks"]],
        "source": meta.get("source"), "release": meta.get("release"), "folder": folder,
        "art": art, "accurate": accurate, "added": int(time.time()), "already_had": already,
        "needs_info": meta.get("source") == "none",
    }


# ---- the rip ----------------------------------------------------------------------------------------------------------

def rip(dev):
    os.makedirs(HERE, exist_ok=True)
    lock = open(os.path.join(HERE, f"lock-{os.path.basename(dev)}"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        log(dev, "already ripping")
        return 0
    status(state="reading", message="Reading the disc…")
    for _ in range(20):                                          # the drive spins up a few seconds after closing
        try:
            disc = read_disc(dev)
            break
        except Exception as e:
            err = e
            time.sleep(2)
    else:
        log(dev, "can't read the disc:", err)
        status(state="error", message="Couldn't read that disc (is it a music CD?)")
        eject(dev)
        return 1
    log(dev, "disc", disc["mb"], disc["freedb"], disc["last"], "tracks")

    status(state="looking", message="Looking it up…")
    meta = None
    try:
        meta = musicbrainz(disc)
    except Exception as e:
        log("musicbrainz", e)
    if not meta:
        meta = gnudb(disc)
    if not meta:
        stamp = time.strftime("%Y-%m-%d %H.%M")
        n = disc["last"] - disc["first"] + 1
        meta = {"source": "none", "artist": "Unknown Artist", "album": f"Unknown CD {stamp}", "year": "", "date": "",
                "disc": 1, "discs": 1, "tracks": [{"n": i + 1, "title": f"Track {i + 1}", "artist": "Unknown Artist"} for i in range(n)]}
    log("found", meta["source"], meta["artist"], "-", meta["album"], meta.get("year"), f"disc {meta['disc']}/{meta['discs']}")
    title = f"{meta['artist']} – {meta['album']}" + (f" (disc {meta['disc']} of {meta['discs']})" if meta["discs"] > 1 else "")

    if meta["source"] != "none":
        have = find_existing(meta)
        if have:
            log("already in the library:", have)
            art = sorted(os.path.basename(p) for p in glob.glob(os.path.join(glob.escape(have), "Artwork", "*.jpg")))
            art_dir = os.path.join(have, "Artwork")
            if not os.path.exists(os.path.join(art_dir, "front.jpg")):
                art = sorted(cover_art(meta, art_dir).values())
            e = entry_for(have, meta, disc, None, None, art, already=True)
            add_to_collection(e)
            json.dump(e, open(os.path.join(art_dir, "cd.json"), "w"), indent=1)
            status(state="done", album=meta["album"], artist=meta["artist"], message=f"Already had {title}: added to your CDs")
            eject(dev)
            return 0

    offset, why = drive_offset(dev)
    if offset is None:
        offset = find_offset(dev)
        why += f"; found from the disc: {offset}" if offset is not None else "; couldn't find it from this disc, using 0"
        offset = offset or 0
    log("drive offset", offset, why)
    work = os.path.join(STAGING, f"{os.path.basename(dev)}-{int(time.time())}")
    os.makedirs(work, exist_ok=True)
    art_dir = os.path.join(work, "Artwork")
    status(state="ripping", album=meta["album"], artist=meta["artist"], track=0, tracks=len(meta["tracks"]),
           message=f"Getting the cover for {title}…")
    art = cover_art(meta, art_dir) if meta["source"] != "none" else {}

    cmd = ["cyanrip", "-d", dev, "-s", str(offset), "-o", "flac", "-D", "rip", "-F", "{track} - {title}",
           "-L", "rip", "-M", "rip", "-T", "os_unicode"]
    if meta["source"] == "musicbrainz":
        cmd += ["-R", meta["release"]]
    else:
        cmd += ["-N"]
    if art.get("front"):
        cmd += ["-C", f"Front={os.path.join(art_dir, 'front.jpg')}", "-U"]
    started = time.time()
    text, songs, code = run_cyanrip(cmd, work, meta, title, started)
    if code != 0 and not songs and meta["source"] == "musicbrainz" and re.search(r"No mediums match|MusicBrainz", text):
        log("cyanrip didn't take the release; ripping without MusicBrainz and tagging it here")
        meta["tag_here"] = True
        shutil.rmtree(os.path.join(work, "rip"), ignore_errors=True)
        cmd = [c for c in cmd if c not in ("-R", meta["release"])] + ["-N"]
        text, songs, code = run_cyanrip(cmd, work, meta, title, started)
    if code != 0 or len(songs) < len(meta["tracks"]):
        log("cyanrip failed", code, text[-1500:])
        status(state="error", album=meta["album"], artist=meta["artist"],
               message=f"The rip of {title} didn't finish (scratched?). Kept what it got in {work}")
        eject(dev)
        return 1
    finish(dev, disc, meta, title, work, art, art_dir, text, songs, started)
    return 0


def run_cyanrip(cmd, work, meta, title, started):
    log("run", " ".join(cmd))
    p = subprocess.Popen(cmd, cwd=work, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = []

    def reader():
        for raw in iter(p.stdout.readline, b""):
            out.append(raw.decode("utf-8", "replace"))
    t = threading.Thread(target=reader, daemon=True)
    t.start()
    while p.poll() is None:
        tail = "".join(out[-20:]).replace("\r", "\n")
        m = re.findall(r"track (\d+), progress - ([\d.]+)%", tail)
        track, pct = (int(m[-1][0]), float(m[-1][1])) if m else (max(len(glob.glob(os.path.join(work, "rip", "*.flac"))), 1), 0)
        status(state="ripping", album=meta["album"], artist=meta["artist"], track=track, tracks=len(meta["tracks"]),
               percent=round(((track - 1) + pct / 100) / max(len(meta["tracks"]), 1) * 100), started=int(started),
               message=f"Ripping {title}: song {track} of {len(meta['tracks'])}")
        time.sleep(2)
    t.join(5)
    text = "".join(out)
    open(os.path.join(work, "cyanrip-output.txt"), "a").write(text)
    return text, sorted(glob.glob(os.path.join(work, "rip", "*.flac"))), p.returncode


def finish(dev, disc, meta, title, work, art, art_dir, text, songs, started):
    accurate = accuraterip(text, len(songs))
    log("accuraterip", accurate)

    # tags: unknown/gnudb discs get theirs here; every disc gets the album artist and the disc's IDs
    for i, s in enumerate(songs):
        tr = meta["tracks"][i] if i < len(meta["tracks"]) else {"title": f"Track {i + 1}", "artist": meta["artist"]}
        tags = {"ALBUMARTIST": meta["artist"], "MUSICBRAINZ_DISCID": disc["mb"], "CDDB_DISCID": disc["freedb"],
                "DISCNUMBER": str(meta["disc"]), "DISCTOTAL": str(meta["discs"])}
        if meta["source"] != "musicbrainz" or meta.get("tag_here"):
            tags.update({"TITLE": tr["title"], "ARTIST": tr["artist"] or meta["artist"], "ALBUM": meta["album"],
                         "DATE": meta.get("year") or "", "TRACKNUMBER": str(i + 1), "TRACKTOTAL": str(len(songs)),
                         "GENRE": meta.get("genre") or ""})
        set_tags(s, tags)

    # into the library
    if meta["source"] == "none":
        dest = os.path.join(LIBRARY, "_Unknown CDs", safe(meta["album"].replace("Unknown CD ", "")))
    else:
        artist_dir = next((os.path.join(LIBRARY, d) for d in os.listdir(LIBRARY) if norm(d) == norm(safe(meta["artist"]))),
                          os.path.join(LIBRARY, safe(meta["artist"])))
        dest = os.path.join(artist_dir, safe(meta["album"]))
    multi = meta["discs"] > 1
    if os.path.exists(dest) and not multi:
        dest += f" ({meta.get('comment') or meta.get('country') or meta.get('year') or 'CD'})".replace("()", "")
        if os.path.exists(dest):
            dest += f" {time.strftime('%Y%m%d-%H%M')}"
    status(state="saving", album=meta["album"], artist=meta["artist"], message=f"Saving {title} to the NAS…")
    os.makedirs(os.path.join(dest, "Artwork"), exist_ok=True)
    for i, s in enumerate(songs):
        tr = meta["tracks"][i]["title"] if i < len(meta["tracks"]) else f"Track {i + 1}"
        name = (f"{meta['disc']}-" if multi else "") + f"{i + 1:02d} - {safe(tr)}.flac"
        shutil.copyfile(s, os.path.join(dest, name))
    suffix = f" CD{meta['disc']}" if multi else ""
    base = safe(f"{meta['artist']} - {meta['album']}{suffix}")
    for ext in ("log", "cue"):
        for f in glob.glob(os.path.join(work, "rip", f"*.{ext}")):
            shutil.copyfile(f, os.path.join(dest, f"{base}.{ext}"))
    for name in art.values():
        shutil.copyfile(os.path.join(art_dir, name), os.path.join(dest, "Artwork", name))
    if art.get("front") and not os.path.exists(os.path.join(dest, "cover.jpg")):
        shutil.copyfile(os.path.join(art_dir, "front.jpg"), os.path.join(dest, "cover.jpg"))
    e = entry_for(dest, meta, disc, accurate, art.get("front"), sorted(art.values()))
    e["seconds"] = int(time.time() - started)
    json.dump(e, open(os.path.join(dest, "Artwork", f"cd{meta['disc'] if multi else ''}.json"), "w"), indent=1)
    copied = len(glob.glob(os.path.join(glob.escape(dest), "*.flac")))
    if copied >= len(songs):
        shutil.rmtree(work, ignore_errors=True)
    add_to_collection(e)
    plex_scan(dest)
    mins = round((time.time() - started) / 60)
    verdict = ("not in AccurateRip's database, ripped securely" if not accurate["in_db"] else
               "every song matches AccurateRip" if accurate["ok"] == accurate["tracks"] else
               f"{accurate['ok']} of {accurate['tracks']} songs match AccurateRip (check the others)")
    msg = f"Done: {title} in {mins} min, {verdict}"
    if meta["source"] == "none":
        msg = f"Ripped an unknown disc ({len(songs)} songs) to “_Unknown CDs”: needs a name"
    log(msg)
    status(state="done", album=meta["album"], artist=meta["artist"], message=msg, folder=dest)
    eject(dev)


def accuraterip(text, n):
    """cyanrip's summary: "Tracks ripped accurately: 11/12" (missing when the disc isn't in AccurateRip's database)."""
    m = re.search(r"Tracks ripped accurately: (\d+)/(\d+)", text)
    part = re.search(r"Tracks ripped partially accurately: (\d+)/", text)
    missing = "Unable to get AccuRIP DB data" in text
    return {"tracks": n, "ok": int(m.group(1)) if m else 0, "partial": int(part.group(1)) if part else 0, "in_db": not missing}


def retag(folder):
    """After a disc's tags were fixed by hand: read them back into the collection (and move it out of _Unknown CDs)."""
    songs = sorted(glob.glob(os.path.join(glob.escape(folder), "*.flac")))
    t = read_tags(songs[0])
    items = load_collection()
    for c in items:
        if os.path.normpath(c["folder"]) == os.path.normpath(folder):
            c.update(artist=t.get("ALBUMARTIST") or t.get("ARTIST") or c["artist"], album=t.get("ALBUM") or c["album"],
                     year=(t.get("DATE") or "")[:4], tracks=[read_tags(s).get("TITLE", "") for s in songs], needs_info=False)
    save_collection(items)
    plex_scan(folder)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--retag":
        retag(sys.argv[2])
    elif len(sys.argv) == 2:
        sys.exit(rip(sys.argv[1]))
    else:
        print(__doc__)
