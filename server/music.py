#!/usr/bin/env python3
"""Music for the Living Room TVs: YouTube Music, sound only (no videos, ever), with discovery built from the liked songs.
Mitchell, Oct 21 2026: "limit stimulation in the living room for the kids, but still have entertainment for the parents
by listening to music ... feel like the app that's on mobile, but with a better way of discovering music similar to what
I've already filled my liked playlist with."

    /srv/music/venv/bin/python music.py [--port 8097]

GET  /health                 {"ok", "signedIn"}  (no secret needed)
GET  /home                   rows: stations, new finds, artists you might love, deep cuts, forgotten favourites, playlists ...
GET  /list?type=playlist|album&id=    a playlist ("LM" = Liked songs) or an album: title, art, tracks
GET  /artist?id=             an artist: top songs, albums, singles, fans also like
GET  /search?q=              songs, artists, albums, playlists (never videos)
POST /station                {"id": "likes"|"blend"|"fresh"|"radio:<videoId>"|"artist:<channelId>", "exclude": [ids], "n"}
                             -> the next tracks of an endless station (the TV asks again near the end of its queue)
GET  /stream?v=<videoId>     the song's audio (AAC from YouTube), proxied with Range support so the TV can seek
GET  /prepare?v=             look up a song's audio ahead of time (the TV asks for the next song while one plays)
POST /vote                   {"v", "vote": "like"|"dislike"|"none"} -> YouTube Music's thumbs (likes land in Liked songs)
POST /played                 {"v", "seconds", "length", "skipped"} -> listening history, for discovery

Discovery (rebuilt every 6 hours, and soon after a like): YouTube Music's song radios seeded from ~40 liked songs (recent
likes and random older ones). A song that turns up from several different liked songs ranks far above one that turns up
once; songs from artists you already like go to "More from artists you love", the rest to "New finds". Related artists
of your most-liked artists that you have no likes from become "Artists you might love". Thumbs down, or skipping a
find early twice, keeps it out for good. Every find says why ("Because you like X and Y").

Sign-in (Mitchell does it, once): YouTube Music request headers from his browser, saved by
`ytmusicapi browser --file ~/.config/living-room-tv/ytmusic-browser.json`. Until then search, artists and radios work;
likes, playlists and discovery need it. Every TV call carries X-Hub-Secret (the hub's shared secret).
"""
import argparse
import collections
import json
import os
import random
import re
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yt_dlp
from ytmusicapi import YTMusic

CONF = os.path.expanduser("~/.config/living-room-tv")
AUTH = os.path.join(CONF, "ytmusic-browser.json")
BRAND = os.path.join(CONF, "ytmusic-brand")                     # set by signin.py when it's a brand channel
DATA = os.environ.get("MUSIC_DATA", "/srv/music")
DISCOVERY = os.path.join(DATA, "discovery.json")
COOKIES = os.path.join(DATA, "cookies.txt")
SECRET = open(os.path.join(CONF, "hub.secret")).read().strip()
REBUILD_EVERY = 6 * 3600
NOT_MUSIC = {"MUSIC_VIDEO_TYPE_PODCAST_EPISODE", "MUSIC_VIDEO_TYPE_UGC"}   # podcasts and random uploads stay out


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


# ---- YouTube Music ----------------------------------------------------------------------------------------------

class Yt:
    """One YTMusic client, signed in when the browser headers file exists (picked up again when it changes)."""
    def __init__(self):
        self.lock = threading.Lock()
        self.mtime = None
        self.client = YTMusic()
        self.signed_in = False

    def get(self):
        with self.lock:
            m = os.path.getmtime(AUTH) if os.path.exists(AUTH) else None
            if m != self.mtime:
                self.mtime = m
                try:
                    brand = open(BRAND).read().strip() if os.path.exists(BRAND) else None
                    self.client = (YTMusic(AUTH, user=brand) if brand else YTMusic(AUTH)) if m else YTMusic()
                    self.signed_in = bool(m)
                    if m:
                        write_cookies()
                    log("YouTube Music:", "signed in" if m else "not signed in")
                except Exception as e:
                    log("sign-in file unusable:", e)
                    self.client, self.signed_in = YTMusic(), False
            return self.client


YT = Yt()


def write_cookies():
    """yt-dlp gets the same sign-in (Premium's better audio), as a Netscape cookie file only this user can read."""
    try:
        h = {k.lower(): v for k, v in json.load(open(AUTH)).items()}
        lines = ["# Netscape HTTP Cookie File"]
        for part in h.get("cookie", "").split(";"):
            if "=" in part:
                k, v = part.strip().split("=", 1)
                lines.append(f".youtube.com\tTRUE\t/\tTRUE\t{int(time.time()) + 365 * 86400}\t{k}\t{v}")
        fd = os.open(COOKIES, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write("\n".join(lines) + "\n")
    except Exception as e:
        log("cookies:", e)


def art(thumbs, size=544):
    """The largest thumbnail, asked for at [size] (Google's image server resizes square album art on request)."""
    if isinstance(thumbs, dict):
        thumbs = thumbs.get("thumbnails") or []
    if not thumbs:
        return None
    url = thumbs[-1].get("url") or ""
    if "googleusercontent.com" in url or "ggpht.com" in url:
        url = re.sub(r"=w\d+-h\d+[^/]*$", f"=w{size}-h{size}-l90-rj", url)
        url = re.sub(r"=s\d+[^/]*$", f"=s{size}", url)
    return url or None


def seconds(s):
    if isinstance(s, (int, float)):
        return int(s)
    if not s or not isinstance(s, str):
        return 0
    n = 0
    for p in s.split(":"):
        n = n * 60 + int(p) if p.isdigit() else n
    return n


VIDEO_WORDS = re.compile(r"\s*[\[(](official\s+)?(music\s+)?(video|visualizer|lyric video|lyrics?|audio|hd|4k)[^\])]*[\])]", re.I)
VARIANT = re.compile(r"\b(instrumental|karaoke|sped up|slowed|reverb|8d audio|nightcore|cover)\b", re.I)


def same_song(t):
    """The same song under another upload: the title without brackets, plus the first artist."""
    title = re.sub(r"[\[(][^\])]*[\])]", "", t["title"]).lower()
    return re.sub(r"[^a-z0-9]+", " ", title).strip() + "|" + (t["artist"].split(",")[0].strip().lower())


def track(t, reason=None):
    """A song the way the TV wants it, or None (no id, a podcast, a random upload)."""
    if not t or not t.get("videoId") or t.get("videoType") in NOT_MUSIC:
        return None
    arts = [a for a in (t.get("artists") or []) if a.get("name")]
    alb = t.get("album") if isinstance(t.get("album"), dict) else None
    out = {
        "type": "track", "v": t["videoId"], "title": VIDEO_WORDS.sub("", t.get("title") or "").strip(),
        "artist": ", ".join(a["name"] for a in arts) or (t.get("author") or ""),
        "artistId": next((a.get("id") for a in arts if a.get("id")), None),
        "album": alb.get("name") if alb else None, "albumId": alb.get("id") if alb else None,
        "art": art(t.get("thumbnails") or t.get("thumbnail")),
        "length": t.get("duration_seconds") or seconds(t.get("duration") or t.get("length")),
    }
    if out["art"] and "i.ytimg.com/vi/" in out["art"]:                # a video's frame (with black bars): square it up
        out["art"] = f"/ytimg?v={out['v']}"
    lead = out["artist"].split(",")[0].strip()
    if lead and out["title"].lower().startswith(lead.lower() + " - "):   # "Artist - Song" uploads: just the song
        out["title"] = out["title"][len(lead) + 3:].strip().strip('"')
    if reason:
        out["reason"] = reason
    return out


def card(kind, id_, title, subtitle="", thumbs=None, reason=None, art_url=None):
    return {"type": kind, "id": id_, "title": title or "", "subtitle": subtitle or "", "art": art_url or art(thumbs),
            **({"reason": reason} if reason else {})}


def tracks(items, reason=None):
    seen, out = set(), []
    for t in items or []:
        x = track(t, reason)
        if x and x["v"] not in seen:
            seen.add(x["v"])
            out.append(x)
    return out


# ---- what we remember (sqlite) ------------------------------------------------------------------------------------

def db():
    c = sqlite3.connect(os.path.join(DATA, "music.sqlite"), timeout=30)
    c.execute("CREATE TABLE IF NOT EXISTS plays (v TEXT, ts INTEGER, seconds INTEGER, length INTEGER, skipped INTEGER)")
    c.execute("CREATE TABLE IF NOT EXISTS votes (v TEXT PRIMARY KEY, vote TEXT, ts INTEGER)")
    c.execute("CREATE TABLE IF NOT EXISTS meta (v TEXT PRIMARY KEY, json TEXT)")      # every track the TV has seen
    return c


def disliked():
    with db() as c:
        return {r[0] for r in c.execute("SELECT v FROM votes WHERE vote='dislike'")}


def early_skips():
    """Songs skipped in their first 30 seconds at least twice: discovery leaves them out."""
    with db() as c:
        return {r[0] for r in c.execute("SELECT v FROM plays WHERE skipped=1 AND seconds<30 GROUP BY v HAVING count(*)>=2")}


def liked_ids():
    """Liked songs as YouTube Music has them, with the TV's own thumbs since then on top."""
    ids = {t["v"] for t in liked()}
    with db() as c:
        for v, vote in c.execute("SELECT v, vote FROM votes"):
            (ids.add if vote == "like" else ids.discard)(v)
    return ids


def _plex_unliked():
    with db() as c:
        return {r[0] for r in c.execute("SELECT v FROM votes WHERE vote!='like' AND v LIKE 'plex:%'")}


def _norm(s):
    s = re.sub(r"[\(\[][^\)\]]*[\)\]]", "", (s or "").lower())               # (Remastered 2009), [Live], (feat. X)
    s = re.split(r" - (?:remaster|live|mono|stereo|single|radio|edit|\d{4})", s)[0]
    s = re.split(r"\bfeat\.?\b|\bft\.?\b", s)[0]
    return re.sub(r"[^a-z0-9]+", "", s.replace("&", "and"))


def song_key(title, artist):
    return _norm(title) + "|" + _norm((artist or "").split(",")[0])


def plex_index():
    """Songs you own in Plex, by title and artist: a YouTube Music song you own plays from your file instead."""
    def load():
        albums = {a["ratingKey"]: a for a in shelf_albums()}
        idx = {}
        for t in shelf_tracks():
            if t.get("parentRatingKey") not in albums:
                continue
            k = song_key(t.get("title"), t.get("originalTitle") or t.get("grandparentTitle"))
            if k.split("|")[0] and k not in idx:
                idx[k] = t
        return idx
    try:
        return cached("plex-index", 900, load)
    except Exception as e:
        log("plex index:", e)
        return {}


def mark(obj, ids, owned=None):
    """Every track in an answer says whether it's liked (the TV's thumbs show it), and, when you own it in Plex,
    which Plex file to play instead (plex) and its quality."""
    if owned is None:
        owned = plex_index()
    if isinstance(obj, dict):
        if obj.get("type") == "track":
            v = obj.get("v") or ""
            obj["liked"] = (v in ids) if not v.startswith("plex:") else (v in ids or (obj.get("liked") and v not in _plex_unliked()))
            if not v.startswith("plex:"):
                t = owned.get(song_key(obj.get("title"), obj.get("artist")))
                if t:
                    obj["plex"] = f"plex:{t['ratingKey']}"
                    obj["quality"] = (p_track(t).get("quality") or "Plex") + " · from your Plex"
        for v in obj.values():
            if isinstance(v, (dict, list)):
                mark(v, ids, owned)
    elif isinstance(obj, list):
        for v in obj:
            mark(v, ids, owned)
    return obj


def last_played():
    with db() as c:
        return dict(c.execute("SELECT v, max(ts) FROM plays WHERE seconds>=30 GROUP BY v"))


# ---- library ----------------------------------------------------------------------------------------------------

_cache = {}


def cached(key, ttl, fn):
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    val = fn()
    _cache[key] = (time.time(), val)
    return val


def liked():
    """Every liked song, newest like first (empty when not signed in)."""
    def load():
        yt = YT.get()
        if not YT.signed_in:
            return []
        return tracks(yt.get_liked_songs(limit=5000).get("tracks"))
    return cached("liked", 600, load)


def forget(*keys):
    for k in keys:
        _cache.pop(k, None)


# ---- discovery ----------------------------------------------------------------------------------------------------

_build_lock = threading.Lock()


def build_discovery():
    if not _build_lock.acquire(blocking=False):
        return
    try:
        t0 = time.time()
        forget("liked")
        likes = liked()
        if not likes:
            return
        yt = YT.get()
        L = {t["v"] for t in likes}
        bad = disliked() | early_skips()
        by_artist = collections.Counter(t["artistId"] for t in likes if t["artistId"])
        artist_name = {t["artistId"]: t["artist"].split(",")[0] for t in likes if t["artistId"]}
        seeds = likes[:15] + random.sample(likes[15:], min(25, max(0, len(likes) - 15)))

        def radio(seed):
            try:
                return seed, yt.get_watch_playlist(seed["v"], radio=True, limit=40).get("tracks") or []
            except Exception as e:
                log("radio", seed["v"], e)
                return seed, []

        cand = {}
        with ThreadPoolExecutor(6) as pool:
            for seed, items in pool.map(radio, seeds):
                for rank, t in enumerate(items[1:]):
                    x = track(t)
                    if not x or x["v"] in L or x["v"] in bad:
                        continue
                    c = cand.setdefault(x["v"], {"t": x, "score": 0.0, "seeds": []})
                    c["score"] += 1.0 / (1 + rank / 8)
                    if seed["title"] not in c["seeds"]:
                        c["seeds"].append(seed["title"])

        # Related artists of the most-liked artists, that nothing liked comes from yet.
        rel = {}

        def artist(aid):
            try:
                return aid, yt.get_artist(aid)
            except Exception as e:
                log("artist", aid, e)
                return aid, {}

        with ThreadPoolExecutor(6) as pool:
            for aid, a in pool.map(artist, [a for a, _ in by_artist.most_common(15)]):
                for r in (a.get("related") or {}).get("results") or []:
                    rid = r.get("browseId")
                    if not rid or rid in by_artist:
                        continue
                    e = rel.setdefault(rid, {"id": rid, "title": r.get("title"), "thumbs": r.get("thumbnails"), "by": []})
                    e["by"].append(artist_name.get(aid, ""))
            top_rel = sorted(rel.values(), key=lambda e: -len(e["by"]))[:12]
            for rid, a in pool.map(artist, [e["id"] for e in top_rel[:8]]):
                e = rel[rid]
                for rank, t in enumerate(((a.get("songs") or {}).get("results") or [])[:4]):
                    x = track(t)
                    if not x or x["v"] in L or x["v"] in bad:
                        continue
                    c = cand.setdefault(x["v"], {"t": x, "score": 0.0, "seeds": [], "fans": []})
                    c["score"] += 0.3 * len(e["by"]) / (1 + rank)
                    c.setdefault("fans", []).extend(n for n in e["by"] if n not in c.get("fans", []))

        def reason(c):
            s = c["seeds"]
            if len(s) >= 3:
                return f"Because you like {s[0]}, {s[1]} and {len(s) - 2} more"
            if len(s) == 2:
                return f"Because you like {s[0]} and {s[1]}"
            if s:
                return f"Because you like {s[0]}"
            fans = c.get("fans") or []
            return f"Fans of {' and '.join(fans[:2])} love this artist" if fans else ""

        ranked = sorted(cand.values(), key=lambda c: -(c["score"] * (1 + 0.6 * (len(c["seeds"]) - 1 if c["seeds"] else 0))))
        fresh, deep, per_artist = [], [], collections.Counter()
        seen_songs = {same_song(t) for t in likes}                # another upload of a liked song isn't a find
        for c in ranked:
            t = dict(c["t"], reason=reason(c))
            key = same_song(t)
            if key in seen_songs or VARIANT.search(t["title"]):
                continue
            seen_songs.add(key)
            a = t["artistId"] or t["artist"]
            if per_artist[a] >= 2:                               # no artist floods a list
                continue
            per_artist[a] += 1
            (deep if t["artistId"] in by_artist else fresh).append(t)
        artists = [card("artist", e["id"], e["title"], "Artist", e["thumbs"],
                        reason="Fans of " + " and ".join(sorted(set(e["by"]))[:2]))
                   for e in sorted(rel.values(), key=lambda e: -len(e["by"]))[:16]]
        out = {"built": int(time.time()), "fresh": fresh[:300], "deep": deep[:150], "artists": artists,
               "likes": len(likes), "seeds": len(seeds)}
        tmp = DISCOVERY + ".tmp"
        json.dump(out, open(tmp, "w"))
        os.replace(tmp, DISCOVERY)
        log(f"discovery: {len(fresh)} new finds, {len(deep)} deep cuts, {len(artists)} artists from {len(likes)} likes "
            f"in {time.time() - t0:.0f}s")
    except Exception as e:
        log("discovery failed:", repr(e))
    finally:
        _build_lock.release()


def discovery():
    try:
        return json.load(open(DISCOVERY))
    except Exception:
        return {}


def rebuild_soon(delay=60):
    threading.Timer(delay, build_discovery).start()


def discovery_loop():
    while True:
        d = discovery()
        if YT.get() and YT.signed_in and time.time() - d.get("built", 0) > REBUILD_EVERY:
            build_discovery()
        if YT.signed_in and time.time() - new_releases().get("built", 0) > 3 * 86400:
            try:
                build_new()
            except Exception as e:
                log("new releases failed:", repr(e))
        time.sleep(600)



# ---- Plex: the record shelf (Mitchell's own music library, played as-is: FLAC stays lossless) -----------------------

PLEX = "http://127.0.0.1:32400"
PLEX_TOKEN_FILE = os.path.join(CONF, "plex.token")
PLEX_SECTION = 8                                                 # "Music Library"
AUTHORS = {"Top 100 Sci-Fi Books", "Jen Sincero", "Joe Haldeman", "Kurt Vonnegut", "Robert A. Heinlein", "Cal Newport",
           "Grant Cardone", "Michael Gerber"}                    # audiobooks filed as music: not on the shelf


def plex(path, method="GET", **params):
    q = urllib.parse.urlencode(dict(params, **{"X-Plex-Token": open(PLEX_TOKEN_FILE).read().strip()}))
    req = urllib.request.Request(f"{PLEX}{path}{'&' if '?' in path else '?'}{q}", headers={"Accept": "application/json"}, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        b = r.read()
    return json.loads(b).get("MediaContainer", {}) if b.strip().startswith(b"{") else {}


def plex_img(thumb):
    return "/img?p=" + urllib.parse.quote(thumb) if thumb else None


def is_book(a):
    genres = {g.get("tag", "").lower() for g in a.get("Genre") or []}
    return (a.get("parentTitle") in AUTHORS or any("audiobook" in g for g in genres)
            or "unabridged" in (a.get("title") or "").lower() or a.get("title") == "[Unknown Album]")


def quality(m):
    """'FLAC · 24-bit / 96 kHz', 'MP3 · 320 kbps' ..."""
    if not m:
        return None
    codec = (m.get("audioCodec") or "").upper()
    part = (m.get("Part") or [{}])[0]
    stream = next((s for s in part.get("Stream") or [] if s.get("streamType") == 2), {})
    depth, rate = stream.get("bitDepth"), stream.get("samplingRate")
    if codec in ("FLAC", "ALAC", "WAV", "AIFF") and depth and rate:
        return f"{codec} · {depth}-bit / {rate / 1000:g} kHz" + (" · played as 16-bit / 48 kHz" if rate > 48000 else
                                                                  " · played as 16-bit" if int(depth) > 16 else "")
    return f"{codec} · {m.get('bitrate')} kbps" if m.get("bitrate") else codec or None


def album_quality(rk):
    """The album's real resolution, from one of its tracks' full details (listings leave out bit depth and rate)."""
    def load():
        kids = plex(f"/library/metadata/{rk}/children").get("Metadata") or []
        if not kids:
            return None
        t = (plex(f"/library/metadata/{kids[0]['ratingKey']}").get("Metadata") or [{}])[0]
        return quality((t.get("Media") or [{}])[0])
    return cached(f"aq:{rk}", 86400, load)


def p_track(t):
    m = (t.get("Media") or [{}])[0]
    q = quality(m)
    if q and q.split(" ")[0] in ("FLAC", "ALAC", "WAV", "AIFF") and "bit" not in q and t.get("parentRatingKey"):
        try:
            q = album_quality(t["parentRatingKey"]) or q
        except Exception:
            pass
    return {"type": "track", "v": f"plex:{t['ratingKey']}", "title": t.get("title") or "",
            "artist": t.get("originalTitle") or t.get("grandparentTitle") or "",
            "artistId": f"plex:{t['grandparentRatingKey']}" if t.get("grandparentRatingKey") else None,
            "album": t.get("parentTitle"), "albumId": f"plex:{t['parentRatingKey']}" if t.get("parentRatingKey") else None,
            "art": plex_img(t.get("parentThumb") or t.get("thumb") or t.get("grandparentThumb")),
            "length": int((t.get("duration") or 0) / 1000), "quality": q,
            "liked": (t.get("userRating") or 0) >= 8}


def shelf_tracks():
    """Every track in the music library (cached 15 minutes), with each album's formats for the album cards."""
    def load():
        return plex(f"/library/sections/{PLEX_SECTION}/all", type=10).get("Metadata") or []
    return cached("plex-tracks", 900, load)


def shelf_albums():
    def load():
        albums = [a for a in plex(f"/library/sections/{PLEX_SECTION}/all", type=9).get("Metadata") or [] if not is_book(a)]
        fmt = collections.defaultdict(collections.Counter)
        for t in shelf_tracks():
            fmt[t.get("parentRatingKey")][((t.get("Media") or [{}])[0].get("audioCodec") or "").upper()] += 1
        for a in albums:
            f = fmt.get(a["ratingKey"])
            a["_fmt"] = f.most_common(1)[0][0] if f else ""
        return albums
    return cached("plex-albums", 900, load)


def p_album(a):
    sub = " · ".join(str(x) for x in [a.get("parentTitle"), a.get("year"), a.get("_fmt")] if x)
    return card("album", f"plex:{a['ratingKey']}", a.get("title"), sub, art_url=plex_img(a.get("thumb")))


def wishlist():
    """Albums you love on YouTube Music that aren't in your Plex library: worth owning in lossless (CD or hi-res).
    Scored by liked songs (3 each), plus plays in YouTube Music's history and in this app."""
    def load():
        owned = {_norm(a.get("title")) + "|" + _norm(a.get("parentTitle")) for a in shelf_albums()}
        owned_titles = {_norm(a.get("title")) for a in shelf_albums()}
        albums = {}

        def add(t, points, why):
            aid, name = t.get("albumId"), t.get("album")
            if not aid or not name or not aid.startswith("MPRE"):
                return
            artist = (t.get("artist") or "").split(",")[0].strip()
            if _norm(name) + "|" + _norm(artist) in owned or _norm(name) in owned_titles:
                return
            if _norm(name) == _norm(t.get("title")) or re.search(r"\(.*(remix|edit|version|mix)\)|- (single|ep)$", name.lower()):
                return                                           # a single, not an album worth buying
            a = albums.setdefault(aid, {"id": aid, "title": name, "artist": artist, "art": t.get("art"), "score": 0,
                                        "liked": 0, "plays": 0})
            a["score"] += points
            a[why] += 1

        for t in liked():
            add(t, 3, "liked")
        try:
            for t in tracks(YT.get().get_history()):
                add(t, 1, "plays")
        except Exception as e:
            log("wishlist history:", e)
        with db() as c:
            for (j,) in c.execute("SELECT m.json FROM plays p JOIN meta m ON m.v = p.v WHERE p.seconds >= 30"):
                add(json.loads(j), 1, "plays")
        ranked = sorted((a for a in albums.values() if a["score"] >= 4 or a["liked"] >= 2), key=lambda a: -a["score"])
        out = []
        for a in ranked[:40]:
            why = " · ".join(x for x in [f"{a['liked']} liked song{'s' if a['liked'] != 1 else ''}" if a["liked"] else "",
                                         f"{a['plays']} play{'s' if a['plays'] != 1 else ''}" if a["plays"] else ""] if x)
            out.append(card("album", a["id"], a["title"], a["artist"], art_url=a["art"], reason=why))
        return out
    return cached("wishlist", 3600, load)


def shelf_page():
    albums = shelf_albums()
    rows = [{"id": "shelf-recent", "title": "Recently added to Plex",
             "items": [p_album(a) for a in sorted(albums, key=lambda a: -(a.get("addedAt") or 0))[:24]]}]
    by_genre = collections.defaultdict(list)
    artist_genre = collections.defaultdict(collections.Counter)  # an album with no genre goes with its artist's others
    for a in albums:
        for x in (a.get("Genre") or [])[:1]:
            artist_genre[a.get("parentRatingKey")][x.get("tag")] += 1
    for a in albums:
        g = next((x.get("tag") for x in a.get("Genre") or []), None)
        if not g and artist_genre[a.get("parentRatingKey")]:
            g = artist_genre[a.get("parentRatingKey")].most_common(1)[0][0]
        g = g or "Unsorted"
        by_genre[{"Pop/Rock": "Rock & pop", "R&B": "Soul & R&B", "Rap": "Hip-hop"}.get(g, g)].append(a)
    for g, items in sorted(by_genre.items(), key=lambda kv: (kv[0] == "Unsorted", -len(kv[1]))):
        rows.append({"id": f"shelf-{g}", "title": g,
                     "items": [p_album(a) for a in sorted(items, key=lambda a: (a.get("parentTitle") or "", a.get("year") or 0))]})
    artists = {}
    for a in albums:
        if a.get("parentRatingKey") and a.get("parentTitle") not in ("Various Artists",):
            artists.setdefault(a["parentRatingKey"], card("artist", f"plex:{a['parentRatingKey']}", a.get("parentTitle"), "Artist",
                                                          art_url=plex_img(a.get("parentThumb"))))
    try:
        wl = wishlist()
        if wl:
            rows.insert(1, {"id": "wishlist", "title": "Worth owning in lossless", "items": wl})
    except Exception as e:
        log("wishlist:", e)
    rows.append({"id": "shelf-artists", "title": "Artists in Plex", "items": sorted(artists.values(), key=lambda c: c["title"])})
    return {"rows": rows}


def shelf_album(rk):
    a = (plex(f"/library/metadata/{rk}").get("Metadata") or [{}])[0]
    items = [p_track(t) for t in plex(f"/library/metadata/{rk}/children").get("Metadata") or []]
    q = album_quality(rk) or next((t["quality"] for t in items if t.get("quality")), None)
    sub = " · ".join(str(x) for x in [a.get("parentTitle"), a.get("year"), q] if x)
    return {"title": a.get("title"), "subtitle": sub, "art": plex_img(a.get("thumb")), "tracks": items}


def shelf_artist(rk):
    a = (plex(f"/library/metadata/{rk}").get("Metadata") or [{}])[0]
    albums = [x for x in plex(f"/library/metadata/{rk}/children").get("Metadata") or [] if not is_book(x)]
    for x in albums:
        x.setdefault("parentTitle", a.get("title"))
    mine = [t for t in shelf_tracks() if t.get("grandparentRatingKey") == rk]
    top = [p_track(t) for t in sorted(mine, key=lambda t: -(t.get("viewCount") or 0))[:20]]
    rows = [{"id": "songs", "title": "Songs", "items": top}] if top else []
    rows.append({"id": "albums", "title": "Albums in Plex", "items": [p_album(dict(x, _fmt="")) for x in sorted(albums, key=lambda x: x.get("year") or 0)]})
    return {"title": a.get("title"), "subtitle": f"{len(albums)} albums in your Plex library", "art": plex_img(a.get("thumb")), "rows": rows}


def shelf_search(q):
    ql = q.lower()
    books = {a["ratingKey"] for a in plex(f"/library/sections/{PLEX_SECTION}/all", type=9).get("Metadata") or [] if is_book(a) and a.get("title") != "[Unknown Album]"}
    songs = [p_track(t) for t in shelf_tracks() if t.get("parentRatingKey") not in books and
             (ql in (t.get("title") or "").lower() or ql in (t.get("grandparentTitle") or "").lower() or ql in (t.get("originalTitle") or "").lower())]
    albums = [p_album(a) for a in shelf_albums() if ql in (a.get("title") or "").lower() or ql in (a.get("parentTitle") or "").lower()]
    rows = []
    if albums:
        rows.append({"id": "shelf-albums", "title": "In your Plex library", "items": albums[:20]})
    if songs:
        rows.append({"id": "shelf-songs", "title": "Songs in your Plex library", "items": songs[:30]})
    return rows


def shelf_station(id_, exclude, n):
    ex = set(exclude)
    albums = {a["ratingKey"] for a in shelf_albums()}
    pool = [t for t in shelf_tracks() if t.get("parentRatingKey") in albums and f"plex:{t['ratingKey']}" not in ex]
    if id_.startswith("radio:plex:") or id_.startswith("artist:plex:"):
        rk = id_.split(":")[-1]
        seed = next((t for t in shelf_tracks() if t["ratingKey"] == rk), None)
        artist = seed.get("grandparentRatingKey") if seed else rk
        same = [t for t in pool if t.get("grandparentRatingKey") == artist]
        genre = None
        if seed:
            alb = next((a for a in shelf_albums() if a["ratingKey"] == seed.get("parentRatingKey")), {})
            genre = next((g.get("tag") for g in alb.get("Genre") or []), None)
        kin_albums = {a["ratingKey"] for a in shelf_albums() if genre and any(g.get("tag") == genre for g in a.get("Genre") or [])}
        kin = [t for t in pool if t.get("parentRatingKey") in kin_albums and t not in same]
        random.shuffle(same)
        random.shuffle(kin)
        out = []
        while len(out) < n and (same or kin):                       # the artist, with kindred records between
            for src in (same, same, kin):
                if src and len(out) < n:
                    out.append(src.pop())
        return [p_track(t) for t in out]
    random.shuffle(pool)
    return [p_track(t) for t in pool[:n]]


CACHE = os.path.join(DATA, "cache")
_conv_locks = collections.defaultdict(threading.Lock)


def plex_format(rk):
    """(bit depth, sample rate) of a Plex track's audio."""
    def load():
        t = (plex(f"/library/metadata/{rk}").get("Metadata") or [{}])[0]
        part = ((t.get("Media") or [{}])[0].get("Part") or [{}])[0]
        st = next((x for x in part.get("Stream") or [] if x.get("streamType") == 2), {})
        return int(st.get("bitDepth") or 16), int(st.get("samplingRate") or 44100)
    return cached(f"fmt:{rk}", 86400, load)


def shield_copy(rk):
    """The Shield distorts high-resolution files (heard on 24-bit / 192 kHz Takanaka). Those are converted once to 16-bit
    at up to 48 kHz, what it sends over HDMI anyway (SoX resampler, triangular dither), and cached; 16-bit files at
    48 kHz or below play untouched, bit for bit."""
    depth, rate = plex_format(rk)
    if rate <= 48000 and depth <= 16:
        return None
    path = os.path.join(CACHE, f"{rk}.flac")
    with _conv_locks[rk]:
        if not os.path.exists(path):
            os.makedirs(CACHE, exist_ok=True)
            src = f"{PLEX}{plex_part(rk)}?X-Plex-Token={open(PLEX_TOKEN_FILE).read().strip()}"
            t0 = time.time()
            r = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", src, "-map", "0:a:0",
                                "-af", "aresample=resampler=soxr:precision=28:dither_method=triangular", "-ar", str(min(rate, 48000)),
                                "-sample_fmt", "s16", "-c:a", "flac", path + ".tmp.flac"],
                               capture_output=True, text=True, timeout=600)
            if r.returncode != 0:
                log("convert", rk, r.stderr[-300:])
                return None
            os.replace(path + ".tmp.flac", path)
            log(f"converted plex:{rk} ({depth}-bit / {rate} Hz) to 16-bit / {min(rate, 48000)} Hz in {time.time() - t0:.1f}s")
            trim_cache()
    return path


def trim_cache(limit=8 * 1024 ** 3):
    files = sorted((os.path.join(CACHE, f) for f in os.listdir(CACHE) if f.endswith(".flac") and ".tmp" not in f), key=os.path.getatime)
    total = sum(os.path.getsize(f) for f in files)
    while files and total > limit:
        f = files.pop(0)
        total -= os.path.getsize(f)
        os.remove(f)


def plex_part(rk):
    def load():
        t = (plex(f"/library/metadata/{rk}").get("Metadata") or [{}])[0]
        return ((t.get("Media") or [{}])[0].get("Part") or [{}])[0].get("key")
    return cached(f"part:{rk}", 3600, load)

# ---- time of day: what you play in the morning, afternoon, evening, night ------------------------------------------

BUCKETS = [("morning", "Good morning", 5, 11), ("afternoon", "Good afternoon", 11, 17), ("evening", "Good evening", 17, 21),
           ("night", "Good night", 21, 29)]


def bucket(hour=None):
    h = time.localtime().tm_hour if hour is None else hour
    h = h + 24 if h < 5 else h
    return next(b for b in BUCKETS if b[2] <= h < b[3])


def remember(obj):
    """Keeps every track the TV is shown, so plays (which only carry an id) can be turned back into songs."""
    found = []

    def walk(o):
        if isinstance(o, dict):
            if o.get("type") == "track" and o.get("v"):
                found.append((o["v"], json.dumps(o)))
            for v in o.values():
                if isinstance(v, (dict, list)):
                    walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(obj)
    if found:
        with db() as c:
            c.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", found)


def bucket_plays(name):
    """Songs played (30 s or more) in this part of the day over the last 60 days, most played first."""
    b = next(x for x in BUCKETS if x[0] == name)
    counts = collections.Counter()
    with db() as c:
        for v, ts in c.execute("SELECT v, ts FROM plays WHERE seconds>=30 AND ts>?", (int(time.time()) - 60 * 86400,)):
            if bucket(time.localtime(ts).tm_hour)[0] == b[0]:
                counts[v] += 1
        meta = {v: json.loads(j) for v, j in c.execute(
            f"SELECT v, json FROM meta WHERE v IN ({','.join('?' * len(counts))})", list(counts))} if counts else {}
    return [dict(meta[v], plays=n) for v, n in counts.most_common() if v in meta]


TIME_DEFAULTS = {                                                # until there's enough history for that part of the day
    "morning": ("kids", "Morning dance party", "The kids' favorites to start the day"),
    "afternoon": ("blend", "Afternoon mix", "Your likes and new finds"),
    "evening": ("evening", "Evening mix", "Mellow jazz, soul and grooves from your Plex"),
    "night": ("evening", "Late-night mix", "Mellow jazz, soul and grooves from your Plex"),
}


def time_card():
    name, greeting, _, _ = bucket()
    played = bucket_plays(name)
    if len(played) >= 15:
        return greeting, {"type": "station", "id": "time", "title": f"Your {name} mix", "subtitle": "What you play around this time",
                          "art": next((t["art"] for t in played if t.get("art")), None)}
    sid, title, sub = TIME_DEFAULTS[name]
    art = None
    try:
        if sid == "kids" and kids_songs():
            art = f"/ytimg?v={kids_songs()[0]['v']}"
        elif sid == "evening":
            jazz = [a for a in shelf_albums() if any(g.get("tag") == "Jazz" for g in a.get("Genre") or [])]
            art = plex_img(random.choice(jazz).get("thumb")) if jazz else None
    except Exception:
        pass
    return greeting, {"type": "station", "id": "time", "title": title, "subtitle": sub, "art": art, "_default": sid}


def time_station(exclude, n):
    name = bucket()[0]
    ex = set(exclude) | disliked()
    played = [t for t in bucket_plays(name) if t["v"] not in ex]
    if len(played) >= 15:                                        # favourites of this hour, with radio of them mixed in
        keyed = sorted(played, key=lambda t: -random.random() ** (1.0 / (1 + t["plays"])))[:max(4, n // 2)]
        out = list(keyed)
        try:
            out += [t for t in station(f"radio:{random.choice(keyed)['v']}", list(ex) + [t["v"] for t in out], n)]
        except Exception as e:
            log("time radio:", e)
        random.shuffle(out)
        return out[:n]
    sid = TIME_DEFAULTS[name][0]
    if sid == "evening":                                         # the mellow side of the Plex library, with some likes
        mellow = {a["ratingKey"] for a in shelf_albums()
                  if any(g.get("tag") in ("Jazz", "R&B", "Soul", "Electronic") for g in a.get("Genre") or [])}
        pool = [t for t in shelf_tracks() if t.get("parentRatingKey") in mellow and f"plex:{t['ratingKey']}" not in ex]
        random.shuffle(pool)
        out = [p_track(t) for t in pool[:n * 2 // 3]] + station("likes", list(ex), n // 3)
        random.shuffle(out)
        return out[:n]
    return station(sid, exclude, n)


# ---- new releases from your artists ---------------------------------------------------------------------------------

NEW = os.path.join(DATA, "new.json")
ARTIST_IDS = os.path.join(DATA, "artist_ids.json")


def build_new():
    """This year's singles and albums from your most-liked artists and the artists in your Plex library."""
    yt = YT.get()
    year = str(time.localtime().tm_year)
    likes = liked()
    counts = collections.Counter(t["artistId"] for t in likes if t["artistId"])
    names = {t["artistId"]: t["artist"].split(",")[0] for t in likes if t["artistId"]}
    ids = json.load(open(ARTIST_IDS)) if os.path.exists(ARTIST_IDS) else {}
    plex_artists = sorted({a.get("parentTitle") for a in shelf_albums() if a.get("parentTitle") not in (None, "Various Artists", "KJ Mixes")})
    for name in plex_artists:                                    # Plex names -> YouTube Music artist ids (once)
        if name not in ids:
            try:
                hit = next(iter(yt.search(name, filter="artists", limit=1)), None)
                ids[name] = hit.get("browseId") if hit else None
            except Exception:
                ids[name] = None
    json.dump(ids, open(ARTIST_IDS, "w"))
    artists = [a for a, _ in counts.most_common(30)] + [i for i in ids.values() if i and i not in counts]
    out = []

    def check(aid):
        try:
            a = yt.get_artist(aid)
        except Exception:
            return []
        got = []
        for key, kind in (("singles", "Single"), ("albums", "Album")):
            for x in ((a.get(key) or {}).get("results") or [])[:3]:
                if str(x.get("year") or "") == year and x.get("browseId"):
                    got.append(card("album", x["browseId"], x.get("title"), f"{a.get('name') or names.get(aid, '')} · {kind} · {year}",
                                    x.get("thumbnails"), reason=f"New from {a.get('name') or names.get(aid, '')}"))
        return got[:2]

    with ThreadPoolExecutor(6) as pool:
        for got in pool.map(check, artists):
            out += got
    seen, items = set(), []
    for c in out:
        if c["id"] not in seen:
            seen.add(c["id"])
            items.append(c)
    json.dump({"built": int(time.time()), "items": items}, open(NEW + ".tmp", "w"))
    os.replace(NEW + ".tmp", NEW)
    log(f"new releases: {len(items)} from {len(artists)} artists")


def new_releases():
    try:
        return json.load(open(NEW))
    except Exception:
        return {}


# ---- pages ------------------------------------------------------------------------------------------------------

STATIONS = [
    ("blend", "Likes + new finds", "Your liked songs mixed with music like them"),
    ("fresh", "New finds", "Only songs you haven't liked yet, picked from your likes"),
    ("likes", "Your likes", "All your liked songs, shuffled"),
    ("shelf", "Plex library", "Your Plex music, shuffled"),
]


def home():
    yt = YT.get()
    rows = []
    if not YT.signed_in:
        rows.append({"id": "signin", "title": "Sign in to see your likes and new finds", "items": []})
    d = discovery()
    likes = liked()
    bad = disliked()
    greeting, tcard = time_card()
    if YT.signed_in:
        rows.append({"id": "stations", "title": greeting, "items": [tcard] + [
            {"type": "station", "id": s, "title": t, "subtitle": sub,
             "art": next((x["art"] for x in (d.get("fresh") if s == "fresh" else likes)[i * 3:] if x.get("art")), None)}
            for i, (s, t, sub) in enumerate(STATIONS) if s != "shelf"]})
        if not tcard.get("art"):                                  # a default time card borrows its station's picture
            tcard["art"] = next((c["art"] for c in rows[-1]["items"] if c["id"] == tcard.get("_default") and c.get("art")), None)
    try:
        albums = shelf_albums()
        if albums:
            if rows and rows[-1]["id"] == "stations":
                pick = random.choice(albums)
                rows[-1]["items"].append({"type": "station", "id": "shelf", "title": "Plex library",
                                          "subtitle": "Your Plex music, shuffled", "art": plex_img(pick.get("thumb"))})
                ks = kids_songs()
                if ks:
                    rows[-1]["items"].append({"type": "station", "id": "kids", "title": "Kids' dance party",
                                              "subtitle": "The kids' favorites", "art": f"/ytimg?v={ks[0]['v']}"})
            recent = sorted(albums, key=lambda a: -(a.get("addedAt") or 0))[:8]
            rest = random.sample(albums, min(16, len(albums)))
            rows.append({"id": "shelf-home", "title": "From your Plex library",
                         "items": [p_album(a) for a in recent + [a for a in rest if a not in recent]][:20]})
    except Exception as e:
        log("plex shelf:", e)
    fresh = [t for t in d.get("fresh", []) if t["v"] not in bad]
    if fresh:
        rows.append({"id": "fresh", "title": "New finds for you", "items": fresh[:30]})
    nr = new_releases().get("items") or []
    if nr:
        rows.append({"id": "new", "title": "New from your artists", "items": nr[:30]})
    if d.get("artists"):
        rows.append({"id": "artists", "title": "Artists you might love", "items": d["artists"]})
    deep = [t for t in d.get("deep", []) if t["v"] not in bad]
    if deep:
        rows.append({"id": "deep", "title": "More from artists you love", "items": deep[:30]})
    if likes:
        lp = last_played()
        old = [t for t in likes[40:] if time.time() - lp.get(t["v"], 0) > 60 * 86400]
        random.shuffle(old)
        rows.append({"id": "forgotten", "title": "Forgotten favorites", "items": old[:25]})
        rows.append({"id": "liked", "title": "Liked songs", "items": likes[:30],
                     "more": {"type": "playlist", "id": "LM", "title": "Liked songs"}})
    if YT.signed_in:
        try:
            pls = cached("playlists", 900, lambda: yt.get_library_playlists(limit=50))
            items = [card("playlist", p["playlistId"], p.get("title"), p.get("count") and f"{p['count']} songs" or "",
                          p.get("thumbnails")) for p in pls if p.get("playlistId") and p.get("playlistId") != "LM"]
            if items:
                rows.append({"id": "playlists", "title": "Your playlists", "items": items})
        except Exception as e:
            log("playlists:", e)
        try:
            hist = cached("history", 300, lambda: tracks(yt.get_history()))
            if hist:
                rows.append({"id": "recent", "title": "Recently played", "items": hist[:25]})
        except Exception as e:
            log("history:", e)
    try:
        for shelf in cached("ythome", 1800, lambda: yt.get_home(limit=6)):
            items = []
            for c in shelf.get("contents") or []:
                if c.get("videoId"):
                    if c.get("videoType") in ("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"):
                        x = track(c)
                        if x:
                            items.append(x)
                elif c.get("playlistId") and not c.get("browseId", "").startswith("MPRE"):
                    items.append(card("playlist", c["playlistId"], c.get("title"), c.get("description") or "", c.get("thumbnails")))
                elif c.get("browseId", "").startswith("MPRE"):
                    items.append(card("album", c["browseId"], c.get("title"), c.get("year") or "", c.get("thumbnails")))
            mine = {r["title"].lower() for r in rows}
            st = (shelf.get("title") or "").lower()
            if len(items) >= 4 and "video" not in st and st not in mine and st not in ("listen together",):
                rows.append({"id": "yt-" + shelf.get("title", ""), "title": shelf.get("title"), "items": items})
    except Exception as e:
        log("yt home:", e)
    return {"signedIn": YT.signed_in, "rows": rows, "built": d.get("built")}


def list_page(kind, id_):
    if id_.startswith("plex:"):
        return shelf_album(id_[5:])
    yt = YT.get()
    if kind == "album":
        a = yt.get_album(id_)
        thumbs = a.get("thumbnails")
        items = []
        for t in a.get("tracks") or []:
            x = track(dict(t, thumbnails=t.get("thumbnails") or thumbs, album={"name": a.get("title"), "id": id_}))
            if x:
                items.append(x)
        sub = " · ".join(x for x in [", ".join(r["name"] for r in a.get("artists") or []), a.get("year")] if x)
        return {"title": a.get("title"), "subtitle": sub, "art": art(thumbs, 900), "tracks": items}
    if id_ == "LM":
        forget("liked")
        return {"title": "Liked songs", "subtitle": f"{len(liked())} songs", "art": next((t["art"] for t in liked() if t["art"]), None),
                "tracks": liked()}
    p = yt.get_playlist(id_, limit=500)
    return {"title": p.get("title"), "subtitle": p.get("author", {}).get("name") if isinstance(p.get("author"), dict) else "",
            "art": art(p.get("thumbnails"), 900), "tracks": tracks(p.get("tracks"))}


def artist_page(id_):
    if id_.startswith("plex:"):
        return shelf_artist(id_[5:])
    a = YT.get().get_artist(id_)
    rows = []
    songs = tracks((a.get("songs") or {}).get("results"))
    if songs:
        rows.append({"id": "songs", "title": "Top songs", "items": songs})
    for key, title in (("albums", "Albums"), ("singles", "Singles & EPs")):
        items = [card("album", x.get("browseId"), x.get("title"), x.get("year") or "", x.get("thumbnails"))
                 for x in (a.get(key) or {}).get("results") or [] if x.get("browseId")]
        if items:
            rows.append({"id": key, "title": title, "items": items})
    rel = [card("artist", x.get("browseId"), x.get("title"), "Artist", x.get("thumbnails"))
           for x in (a.get("related") or {}).get("results") or [] if x.get("browseId")]
    if rel:
        rows.append({"id": "related", "title": "Fans also like", "items": rel})
    return {"title": a.get("name"), "subtitle": a.get("monthlyListeners") and f"{a['monthlyListeners']} monthly listeners" or "",
            "art": art(a.get("thumbnails"), 900), "rows": rows}


def search(q):
    yt = YT.get()
    try:
        rows = shelf_search(q)
    except Exception as e:
        log("shelf search:", e)
        rows = []
    for flt, title in (("songs", "Songs"), ("artists", "Artists"), ("albums", "Albums"), ("playlists", "Playlists")):
        try:
            r = yt.search(q, filter=flt, limit=20)
        except Exception as e:
            log("search", flt, e)
            continue
        if flt == "songs":
            items = tracks(r)
        elif flt == "artists":
            items = [card("artist", x.get("browseId"), x.get("artist"), "Artist", x.get("thumbnails")) for x in r if x.get("browseId")]
        elif flt == "albums":
            items = [card("album", x.get("browseId"), x.get("title"),
                          " · ".join(y for y in [", ".join(a["name"] for a in x.get("artists") or []), x.get("year") or ""] if y),
                          x.get("thumbnails")) for x in r if x.get("browseId")]
        else:
            items = [card("playlist", (x.get("browseId") or "").removeprefix("VL"), x.get("title"), x.get("author") or "",
                          x.get("thumbnails")) for x in r if x.get("browseId")]
        if items:
            rows.append({"id": flt, "title": title, "items": items})
    return {"rows": rows}


def station(id_, exclude, n):
    if id_ == "time":
        return time_station(exclude, n)
    if id_ == "kids" or id_.startswith("kids:"):
        return kids_station(id_, exclude, n)
    if id_ == "shelf" or ":plex:" in id_:
        return shelf_station(id_, exclude, n)
    yt = YT.get()
    ex = set(exclude) | disliked()
    likes = [t for t in liked() if t["v"] not in ex]
    d = discovery()
    fresh = [t for t in d.get("fresh", []) + d.get("deep", []) if t["v"] not in ex and t["v"] not in early_skips()]

    def weighted(pool, k):                                       # better finds first, but not always the same order
        keyed = [(random.random() ** (1 + i / 20), t) for i, t in enumerate(pool)]
        return [t for _, t in sorted(keyed, key=lambda x: -x[0])][:k]

    if id_ == "likes":
        random.shuffle(likes)
        return likes[:n]
    if id_ == "fresh":
        return weighted(fresh, n)
    if id_ == "blend":
        random.shuffle(likes)
        f = weighted(fresh, n)
        out = []
        for i in range(n):
            src = f if i % 2 else likes
            if src:
                out.append(src.pop(0))
        return out
    if id_.startswith("radio:"):
        items = yt.get_watch_playlist(id_[6:], radio=True, limit=max(n, 30)).get("tracks")
        return [t for t in tracks(items) if t["v"] not in ex][:n]
    if id_.startswith("artist:"):
        a = yt.get_artist(id_[7:])
        if a.get("radioId"):
            items = yt.get_watch_playlist(playlistId=a["radioId"], limit=max(n, 30)).get("tracks")
        else:
            items = (a.get("songs") or {}).get("results")
        return [t for t in tracks(items) if t["v"] not in ex][:n]
    return []


# ---- kids' dance party (kids.py finds the songs in the YouTube history; Claude labelled them) ------------------------

KIDS = os.path.join(DATA, "kids", "kids.json")
KID_GROUPS = [                                                   # (id, row title, station title, test on the song)
    ("cars", "Cars and trucks", "Cars and trucks",
     lambda s: re.search(r"car|mcqueen|mater|truck|highway|real gone|radiator|race|wheels|monster|digger|vehicle", (s["title"] + " " + s["original"]).lower())),
    ("sing", "Sing and dance along", "Sing-alongs",
     lambda s: s["kind"] == "kids_song" and re.search(r"wiggles|rachel|raphi|super simple|sesame|cocomelon|bluey|pinkfong|fruit|dance", (s["title"] + " " + s["original"] + " " + s["channel"]).lower())),
    ("party", "Family dance hits", "Party hits", lambda s: s["kind"] == "family_dance"),
]


def kids_songs():
    try:
        songs = json.load(open(KIDS))["songs"]
    except Exception:
        return []
    bad = disliked()
    return [s for s in songs if s["v"] not in bad]


def kid_track(s):
    return {"type": "track", "v": s["v"], "title": s["title"], "artist": s["artist"], "artistId": None, "album": None,
            "albumId": None, "art": f"/ytimg?v={s['v']}", "length": 0, "plays": s["plays"]}


def kids_page():
    songs = kids_songs()
    if not songs:
        return {"rows": []}
    pick = lambda lst, i: f"/ytimg?v={lst[min(i, len(lst) - 1)]['v']}" if lst else None
    stations = [{"type": "station", "id": "kids", "title": "Dance party", "subtitle": f"All {len(songs)} favorites, shuffled",
                 "art": pick(songs, 0)}]
    rows = []
    for gid, row_title, st_title, test in KID_GROUPS:
        g = [s for s in songs if test(s)]
        if len(g) >= 4:
            stations.append({"type": "station", "id": f"kids:{gid}", "title": st_title, "subtitle": f"{len(g)} songs", "art": pick(g, 1)})
            rows.append({"id": f"kids-{gid}", "title": row_title, "items": [kid_track(s) for s in g[:40]]})
    return {"rows": [{"id": "kids-stations", "title": "Dance party", "items": stations},
                     {"id": "kids-top", "title": "Played the most", "items": [kid_track(s) for s in songs[:40]]}] + rows}


def kids_station(id_, exclude, n):
    """Favorites first, but never the same order: weighted by how often each was played."""
    ex = set(exclude)
    pool = kids_songs()
    if ":" in id_:
        test = next((t for g, _, _, t in KID_GROUPS if g == id_.split(":")[1]), None)
        pool = [s for s in pool if test and test(s)]
    pool = [s for s in pool if s["v"] not in ex] or [s for s in kids_songs() if s["v"] not in ex]
    keyed = sorted(pool, key=lambda s: -random.random() ** (1.0 / (1 + s["plays"])))
    return [kid_track(s) for s in keyed[:n]]


# ---- popular: what everyone else is listening to ---------------------------------------------------------------------

def _two_row(o, out):
    """Playlists in a YouTube Music browse page (musicTwoRowItemRenderer), read leniently."""
    if isinstance(o, dict):
        r = o.get("musicTwoRowItemRenderer")
        if r:
            bid = (((r.get("navigationEndpoint") or {}).get("browseEndpoint") or {}).get("browseId") or "")
            title = "".join(x.get("text", "") for x in (r.get("title") or {}).get("runs") or [])
            sub = "".join(x.get("text", "") for x in (r.get("subtitle") or {}).get("runs") or [])
            thumbs = (((r.get("thumbnailRenderer") or {}).get("musicThumbnailRenderer") or {}).get("thumbnail") or {}).get("thumbnails")
            if bid.startswith("VL"):
                out.append(card("playlist", bid[2:], title, sub, thumbs))
            return
        for v in o.values():
            _two_row(v, out)
    elif isinstance(o, list):
        for v in o:
            _two_row(v, out)


def mood_page(params, title):
    def load():
        res = _anon._send_request("browse", {"browseId": "FEmusic_moods_and_genres_category", "params": params})
        items = []
        _two_row(res, items)
        return {"title": title, "subtitle": "Playlists from YouTube Music", "art": items[0]["art"] if items else None,
                "rows": [{"id": f"mood-{i}", "title": "Playlists" if i == 0 else "More playlists", "items": items[i:i + 24]}
                         for i in range(0, len(items), 24)]}
    return cached(f"mood:{params}", 6 * 3600, load)


def popular_page():
    """Charts (US and worldwide), trending, top albums and artists, genre charts, moods & genres; plus the chart
    songs from artists nothing in Liked songs comes from: popular, but new to you."""
    def load():
        us, gl = _anon.get_charts("US"), _anon.get_charts("ZZ")
        lists = {p["title"]: p for p in (us.get("videos") or []) + (gl.get("videos") or [])}

        def songs(title_part, n):
            p = next((v for k, v in lists.items() if title_part in k), None)
            if not p:
                return []
            try:
                return tracks(_anon.get_playlist(p["playlistId"], limit=n).get("tracks"))
            except Exception as e:
                log("chart", title_part, e)
                return []

        trending = songs("Trending 20", 20)
        us_top = songs("Daily Top Music Videos - United States", 50)
        world_top = songs("Daily Top Music Videos - Global", 50)
        charts = [card("playlist", p["playlistId"], p["title"].replace(" Music Videos", ""), "Chart", p.get("thumbnails"))
                  for p in lists.values() if "Live Performances" not in p["title"]]
        albums = [card("album", a["browseId"], a.get("title"), a.get("subscribers") or "", a.get("thumbnails"))
                  for a in us.get("artists") or [] if (a.get("browseId") or "").startswith("MPRE")]
        artists = [card("artist", a["browseId"], a.get("title"), "Artist", a.get("thumbnails"))
                   for a in gl.get("artists") or [] if (a.get("browseId") or "").startswith("UC")]
        genres = [card("playlist", p["playlistId"], p["title"].replace("Top 50 ", "").replace(" Music Videos United States", ""),
                       "US genre chart", p.get("thumbnails")) for p in us.get("genres") or []]
        moods = []
        try:
            for group, items in _anon.get_mood_categories().items():
                for m in items:
                    moods.append({"type": "mood", "id": m["params"], "title": m["title"], "subtitle": group, "art": None})
        except Exception as e:
            log("moods", e)
        return trending, us_top, world_top, charts, albums, artists, genres, moods

    trending, us_top, world_top, charts, albums, artists, genres, moods = cached("popular", 3 * 3600, load)
    mine = {t["artist"].split(",")[0].strip().lower() for t in liked()}
    seen, fresh = set(), []
    for t in trending + us_top + world_top:                       # popular, but nothing you've liked comes from them
        a = t["artist"].split(",")[0].strip().lower()
        if a not in mine and t["v"] not in seen:
            seen.add(t["v"])
            fresh.append(dict(t, reason="Popular right now"))
    rows = [{"id": "pop-trending", "title": "Trending now", "items": trending},
            {"id": "pop-new", "title": "Popular, but new to you", "items": fresh[:30]},
            {"id": "pop-us", "title": "Top songs in the US today", "items": us_top[:30]},
            {"id": "pop-world", "title": "Top songs worldwide today", "items": world_top[:30]},
            {"id": "pop-charts", "title": "Charts", "items": charts},
            {"id": "pop-albums", "title": "Top albums in the US", "items": albums[:30]},
            {"id": "pop-artists", "title": "Top artists worldwide", "items": artists[:30]},
            {"id": "pop-genres", "title": "Genre charts", "items": genres},
            {"id": "pop-moods", "title": "Moods and genres", "items": moods}]
    return {"rows": [r for r in rows if r["items"]]}


# ---- the living room lights follow the music (Home Assistant automation music_lights_follow, over MQTT) -------------

LIGHTS = os.path.join(DATA, "lights.json")
_lights = {"timer": None, "last": None}


def lights_enabled():
    try:
        return bool(json.load(open(LIGHTS)).get("enabled", True))
    except Exception:
        return True


def publish_lights(payload):
    import paho.mqtt.publish as mqtt
    try:
        mqtt.single("livingroom/music/lights", json.dumps(payload), hostname="127.0.0.1", port=1883)
    except Exception as e:
        log("lights:", e)


def lights_update(b):
    """From the TV: what's playing and its colours. Paused or stopped for 2 minutes: the lights go back to how they were."""
    if _lights["timer"]:
        _lights["timer"].cancel()
        _lights["timer"] = None
    if not lights_enabled():
        return
    if b.get("playing"):
        payload = {"mode": "party" if b.get("party") else "song",
                   "colors": [[int(x) for x in c][:3] for c in b.get("colors") or []][:3] or [[255, 160, 80]],
                   "accent": [int(x) for x in (b.get("accent") or [255, 160, 80])][:3]}
        if payload != _lights["last"] or b.get("force"):
            _lights["last"] = payload
            publish_lights(payload)
    else:
        def off():
            _lights["last"] = None
            publish_lights({"mode": "off"})
        _lights["timer"] = threading.Timer(0 if b.get("stopped") else 120, off)
        _lights["timer"].start()


# ---- even volume: each song's loudness (EBU R128), so the TV can play them all at about the same level ----------------

TARGET_LUFS = -14.0                                              # what YouTube, Spotify and Tidal normalise to
_gain_locks = collections.defaultdict(threading.Lock)


def loudness(v):
    """Integrated loudness of a song in LUFS, measured once with ffmpeg and kept in the database."""
    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS loudness (v TEXT PRIMARY KEY, lufs REAL)")
        row = c.execute("SELECT lufs FROM loudness WHERE v=?", (v,)).fetchone()
    if row:
        return row[0]
    with _gain_locks[v]:
        with db() as c:
            row = c.execute("SELECT lufs FROM loudness WHERE v=?", (v,)).fetchone()
        if row:
            return row[0]
        if v.startswith("plex:"):
            src = shield_copy(v[5:]) or f"{PLEX}{plex_part(v[5:])}?X-Plex-Token={open(PLEX_TOKEN_FILE).read().strip()}"
            head = []
        else:
            url, headers, _ = resolve(v)
            src = url
            head = ["-user_agent", headers.get("User-Agent", "Mozilla/5.0")]
        lufs = None
        for attempt in range(4):                                 # a fresh YouTube address can refuse for a few seconds
            r = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", *head, "-i", src, "-map", "0:a:0",
                                "-af", "ebur128=framelog=quiet", "-f", "null", "-"], capture_output=True, text=True, timeout=300)
            m = re.findall(r"I:\s+(-?[\d.]+) LUFS", r.stderr)
            if m:
                lufs = float(m[-1])
                break
            time.sleep(2)
        if lufs is None or lufs < -70:
            return None
        with db() as c:
            c.execute("INSERT OR REPLACE INTO loudness VALUES (?,?)", (v, lufs))
        return lufs


def gain(v):
    lufs = loudness(v)
    if lufs is None:
        return {"gain": 0.0}
    return {"lufs": lufs, "gain": round(max(-12.0, min(6.0, TARGET_LUFS - lufs)), 2)}


# ---- album stories (Wikipedia) --------------------------------------------------------------------------------------

_wiki_lock = threading.Lock()


def _wiki(path, **params):
    """One request at a time, a moment apart: Wikipedia asks API users to be gentle (it answers 429 otherwise)."""
    q = urllib.parse.urlencode(params)
    with _wiki_lock:
        time.sleep(0.4)
        return _wiki_get(path, q)


def _wiki_get(path, q):
    req = urllib.request.Request(f"https://en.wikipedia.org{path}{'?' + q if q else ''}",
                                 headers={"User-Agent": "LivingRoomMusic/1.0 (home TV app; mitchellv.me)"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def _wiki_summary(query, must, name):
    """The first search hit whose title is [name] (ignoring a "(... album)" note) and whose description fits [must]."""
    hits = _wiki("/w/api.php", action="query", list="search", srsearch=query, srlimit=5, format="json")
    for h in (hits.get("query") or {}).get("search") or []:
        if _norm(name) != _norm(re.sub(r"\s*\(.*\)$", "", h["title"])):
            continue
        sm = _wiki("/api/rest_v1/page/summary/" + urllib.parse.quote(h["title"].replace(" ", "_")))
        desc = (sm.get("description") or "").lower()
        if sm.get("type") == "standard" and any(m in desc for m in must) and sm.get("extract"):
            return sm
    return None


def story(album, artist, title):
    """A short story for what's playing: the album's Wikipedia summary, else the artist's. Kept for good in the database
    (found or not); a failed lookup (Wikipedia busy) isn't kept, so it's tried again next time."""
    key = f"{album}|{artist}"
    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS stories (k TEXT PRIMARY KEY, json TEXT)")
        row = c.execute("SELECT json FROM stories WHERE k=?", (key,)).fetchone()
    if row:
        return json.loads(row[0])
    try:
        got = _story(album, artist, title)
    except Exception as e:
        log("story:", e)
        return {}
    with db() as c:
        c.execute("INSERT OR REPLACE INTO stories VALUES (?,?)", (key, json.dumps(got)))
    return got


def _story(album, artist, title):
    first = (artist or "").split(",")[0].strip()
    if album and _norm(album) != _norm(title):                    # a single's "album" is the song itself: skip to the artist
        sm = _wiki_summary(f"{album} {first} album", ("album", "soundtrack", "ep by", "compilation"), album)
        if sm and _norm(first)[:5] in _norm(sm.get("extract", "") + sm.get("description", "")):
            return {"kind": "album", "title": sm.get("title"), "text": sm["extract"], "source": "Wikipedia"}
    if first:
        sm = _wiki_summary(first, ("band", "singer", "musician", "rapper", "guitarist", "group", "duo", "composer",
                                   "producer", "dj", "songwriter", "artist", "music"), first)
        if sm:
            return {"kind": "artist", "title": sm.get("title"), "text": sm["extract"], "source": "Wikipedia"}
    return {}


# ---- lyrics -----------------------------------------------------------------------------------------------------

_anon = YTMusic()                                                # timed lyrics only come back without the sign-in


def lrc(text):
    out = []
    for line in (text or "").splitlines():
        m = re.match(r"\[(\d+):(\d+(?:\.\d+)?)\]\s*(.*)", line)
        if m:
            out.append({"t": int((int(m.group(1)) * 60 + float(m.group(2))) * 1000), "text": m.group(3)})
    return out


def lrclib(title, artist, length):
    q = urllib.parse.urlencode({"track_name": title, "artist_name": artist, **({"duration": length} if length else {})})
    try:
        with urllib.request.urlopen(urllib.request.Request(f"https://lrclib.net/api/get?{q}",
                                    headers={"User-Agent": "LivingRoomMusic/1.0"}), timeout=10) as r:
            d = json.loads(r.read())
        if d.get("syncedLyrics"):
            return {"synced": True, "lines": lrc(d["syncedLyrics"]), "source": "LRCLIB"}
        if d.get("plainLyrics"):
            return {"synced": False, "lines": [{"t": 0, "text": l} for l in d["plainLyrics"].splitlines()], "source": "LRCLIB"}
    except Exception:
        pass
    return None


def yt_lyrics(v):
    b = _anon.get_watch_playlist(v, limit=1).get("lyrics")
    if not b:
        return None
    l = _anon.get_lyrics(b, timestamps=True)
    ly = l.get("lyrics")
    if isinstance(ly, list) and ly:
        return {"synced": True, "lines": [{"t": x.start_time, "text": "" if x.text == "♪" else x.text} for x in ly],
                "source": (l.get("source") or "").replace("Source: ", "")}
    if isinstance(ly, str) and ly.strip():
        return {"synced": False, "lines": [{"t": 0, "text": x} for x in ly.splitlines()], "source": (l.get("source") or "").replace("Source: ", "")}
    return None


def lyrics(v, title, artist, length):
    """Synced lyrics when anyone has them: YouTube Music's own, else LRCLIB; Plex songs try LRCLIB, then YouTube Music."""
    def load():
        first = artist.split(",")[0].strip()
        if v.startswith("plex:"):
            got = lrclib(title, first, length)
            if got and got["synced"]:
                return got
            try:
                hit = next(iter(_anon.search(f"{title} {first}", filter="songs", limit=1)), None)
                yt = yt_lyrics(hit["videoId"]) if hit else None
            except Exception:
                yt = None
            return yt or got or {"synced": False, "lines": []}
        try:
            got = yt_lyrics(v)
        except Exception as e:
            log("lyrics", v, e)
            got = None
        if got and got["synced"]:
            return got
        return lrclib(title, first, length) or got or {"synced": False, "lines": []}
    return cached(f"lyrics:{v}", 7 * 86400, load)


# ---- audio ------------------------------------------------------------------------------------------------------

_streams = {}                                                    # videoId -> (url, headers, until)
_stream_lock = threading.Lock()


def resolve(v):
    hit = _streams.get(v)
    if hit and hit[2] > time.time():
        return hit
    opts = {"format": "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio", "quiet": True, "no_warnings": True,
            "noplaylist": True, "js_runtimes": {"node": {}}}
    if YT.signed_in and os.path.exists(COOKIES):
        opts["cookiefile"] = COOKIES
    with yt_dlp.YoutubeDL(opts) as y:
        info = y.extract_info(f"https://music.youtube.com/watch?v={v}", download=False)
    exp = re.search(r"[?&]expire=(\d+)", info["url"])
    until = min(int(exp.group(1)) - 600, time.time() + 4 * 3600) if exp else time.time() + 3600
    hit = (info["url"], info.get("http_headers") or {}, until)
    with _stream_lock:
        _streams[v] = hit
        if len(_streams) > 400:
            for k in sorted(_streams, key=lambda k: _streams[k][2])[:100]:
                _streams.pop(k, None)
    return hit


# ---- HTTP -------------------------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, obj, code=200):
        if code == 200 and self.path.split("?")[0] != "/health":
            try:
                mark(obj, liked_ids())
                remember(obj)
            except Exception as e:
                log("mark", e)
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _ok(self):
        if self.headers.get("X-Hub-Secret") == SECRET:
            return True
        self._json({"error": "secret"}, 403)
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        if u.path == "/health":
            YT.get()
            return self._json({"ok": True, "signedIn": YT.signed_in, "discovery": discovery().get("built")})
        if not self._ok():
            return
        try:
            if u.path == "/home":
                return self._json(home())
            if u.path == "/list":
                return self._json(list_page(q.get("type", "playlist"), q["id"]))
            if u.path == "/artist":
                return self._json(artist_page(q["id"]))
            if u.path == "/lyrics":
                return self._json(lyrics(q["v"], q.get("title", ""), q.get("artist", ""), int(q.get("length") or 0)))
            if u.path == "/shelf":
                return self._json(shelf_page())
            if u.path == "/ytimg":
                return self._ytimg(q["v"], int(q.get("s") or 500))
            if u.path == "/gain":
                if q.get("wait") == "0":                         # only if already measured; measure in the background
                    with db() as c:
                        c.execute("CREATE TABLE IF NOT EXISTS loudness (v TEXT PRIMARY KEY, lufs REAL)")
                        known = c.execute("SELECT lufs FROM loudness WHERE v=?", (q["v"],)).fetchone()
                    if not known:
                        threading.Thread(target=lambda: _try(loudness, q["v"]), daemon=True).start()
                        return self._json({"gain": None})
                return self._json(gain(q["v"]))
            if u.path == "/story":
                return self._json(story(q.get("album", ""), q.get("artist", ""), q.get("title", "")))
            if u.path == "/wishlist":
                return self._json({"items": wishlist()})
            if u.path == "/lights/state":
                return self._json({"enabled": lights_enabled()})
            if u.path == "/popular":
                return self._json(popular_page())
            if u.path == "/mood":
                return self._json(mood_page(q["id"], q.get("title", "")))
            if u.path == "/kids":
                return self._json(kids_page())
            if u.path == "/img":
                return self._img(q["p"], int(q.get("s") or 500))
            if u.path == "/search":
                return self._json(search(q.get("q", "")))
            if u.path == "/prepare" and q.get("v", "").startswith("plex:"):
                threading.Thread(target=lambda: _try(shield_copy, q["v"][5:]), daemon=True).start()   # convert ahead
                return self._json({"ok": True})
            if u.path == "/prepare":
                threading.Thread(target=lambda: _try(resolve, q["v"]), daemon=True).start()
                return self._json({"ok": True})
            if u.path == "/stream":
                return self._stream(q["v"])
            self._json({"error": "not found"}, 404)
        except Exception as e:
            log(u.path, repr(e))
            self._json({"error": str(e)[:200]}, 502)

    def do_POST(self):
        if not self._ok():
            return
        try:
            b = self._body()
            if self.path == "/station":
                got = station(b["id"], b.get("exclude") or [], int(b.get("n") or 25))
                for t in got[:2]:                                # look up the first songs' audio now: no wait when they play
                    v = t.get("v") or ""
                    target = (lambda r=v[5:]: _try(shield_copy, r)) if v.startswith("plex:") else (lambda x=v: _try(resolve, x))
                    threading.Thread(target=target, daemon=True).start()
                return self._json({"tracks": got})
            if self.path == "/vote":
                v, vote = b["v"], b.get("vote", "none")
                with db() as c:
                    c.execute("INSERT OR REPLACE INTO votes VALUES (?,?,?)", (v, vote, int(time.time())))
                ok = True
                if v.startswith("plex:"):                         # thumbs on the shelf are Plex ratings (5 stars / 1 star / none)
                    try:
                        plex(f"/:/rate?key={v[5:]}&identifier=com.plexapp.plugins.library&rating="
                             f"{ {'like': 10, 'dislike': 2}.get(vote, -1) }", method="PUT")
                    except Exception as e:
                        log("plex rate", e)
                    forget("plex-tracks")
                    return self._json({"ok": True})
                if YT.signed_in:
                    try:
                        YT.get().rate_song(v, {"like": "LIKE", "dislike": "DISLIKE"}.get(vote, "INDIFFERENT"))
                    except Exception as e:
                        log("rate", e)
                        ok = False
                if vote == "like":
                    rebuild_soon(300)
                return self._json({"ok": ok})
            if self.path == "/lights":
                lights_update(b)
                return self._json({"ok": True, "enabled": lights_enabled()})
            if self.path == "/lights/enabled":
                json.dump({"enabled": bool(b.get("enabled"))}, open(LIGHTS, "w"))
                if not b.get("enabled"):                         # switched off: the lights go back right away
                    _lights["last"] = None
                    publish_lights({"mode": "off"})
                return self._json({"ok": True, "enabled": lights_enabled()})
            if self.path == "/played":
                if b["v"].startswith("plex:") and int(b.get("length") or 0) and int(b.get("seconds") or 0) >= 0.5 * int(b["length"]):
                    _try(plex, f"/:/scrobble?key={b['v'][5:]}&identifier=com.plexapp.plugins.library")   # Plex counts the play
                with db() as c:
                    c.execute("INSERT INTO plays VALUES (?,?,?,?,?)", (b["v"], int(time.time()), int(b.get("seconds") or 0),
                                                                       int(b.get("length") or 0), 1 if b.get("skipped") else 0))
                return self._json({"ok": True})
            self._json({"error": "not found"}, 404)
        except Exception as e:
            log(self.path, repr(e))
            self._json({"error": str(e)[:200]}, 502)

    def _img(self, thumb, size):
        if not thumb.startswith("/library/"):
            return self._json({"error": "not a library image"}, 400)
        size = max(64, min(size, 1600))
        q = urllib.parse.urlencode({"width": size, "height": size, "minSize": 1, "upscale": 1, "url": thumb,
                                    "X-Plex-Token": open(PLEX_TOKEN_FILE).read().strip()})
        with urllib.request.urlopen(f"{PLEX}/photo/:/transcode?{q}", timeout=30) as r:
            b = r.read()
            ctype = r.headers.get("Content-Type", "image/jpeg")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(b)

    def _ytimg(self, v, size):
        """A video's thumbnail as square art: the HD frame if there is one, else the small one without its black bars."""
        import io
        from PIL import Image
        if not re.fullmatch(r"[\w-]{11}", v):
            return self._json({"error": "bad id"}, 400)
        path = os.path.join(DATA, "kids", "art", f"{v}.jpg")
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            img = None
            for name, bars in (("maxresdefault", 0), ("hqdefault", 45)):
                try:
                    with urllib.request.urlopen(f"https://i.ytimg.com/vi/{v}/{name}.jpg", timeout=15) as r:
                        im = Image.open(io.BytesIO(r.read())).convert("RGB")
                    if name == "maxresdefault" and im.width < 400:   # YouTube's grey placeholder
                        continue
                    if bars:
                        im = im.crop((0, bars, im.width, im.height - bars))
                    img = im
                    break
                except Exception:
                    continue
            if img is None:
                return self._json({"error": "no thumbnail"}, 404)
            side = min(img.width, img.height)
            img = img.crop(((img.width - side) // 2, (img.height - side) // 2, (img.width + side) // 2, (img.height + side) // 2))
            img.resize((720, 720), Image.LANCZOS).save(path, quality=88)
        with Image.open(path) as im:
            buf = io.BytesIO()
            im.resize((max(64, min(size, 720)),) * 2, Image.LANCZOS).save(buf, "JPEG", quality=86)
        b = buf.getvalue()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "max-age=604800")
        self.end_headers()
        self.wfile.write(b)

    def _stream_plex(self, rk):
        """The file itself, from Plex on this machine: no transcoding, so FLAC arrives bit for bit (except files above
        48 kHz, which the Shield can't play cleanly: see shield_copy)."""
        local = shield_copy(rk)
        if local:
            return self._send_file(local, "audio/flac")
        key = plex_part(rk)
        h = {"Range": self.headers.get("Range")} if self.headers.get("Range") else {}
        req = urllib.request.Request(f"{PLEX}{key}?X-Plex-Token={open(PLEX_TOKEN_FILE).read().strip()}", headers=h)
        try:
            up = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with up:
            self.send_response(up.status)
            for k in ("Content-Type", "Content-Length", "Content-Range"):
                if up.headers.get(k):
                    self.send_header(k, up.headers[k])
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            try:
                while True:
                    chunk = up.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _send_file(self, path, ctype):
        """A local file with Range support (the TV seeks with it)."""
        size = os.path.getsize(path)
        start, end = 0, size - 1
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range") or "")
        if m:
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            elif m.group(2):
                start = max(0, size - int(m.group(2)))
        if start >= size:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(206 if m else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if m:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _stream(self, v, retry=True):
        if v.startswith("plex:"):
            return self._stream_plex(v[5:])
        url, headers, _ = resolve(v)
        h = dict(headers, **{"Accept-Encoding": "identity"})
        h["Range"] = self.headers.get("Range") or "bytes=0-"       # without a range, YouTube sends at listening speed
        up = None
        for attempt in range(5):                                 # a fresh address answers 403 for its first few seconds
            try:
                up = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=20)
                break
            except urllib.error.HTTPError as e:
                if e.code != 403 or attempt == 4:
                    err = e
                    break
                time.sleep(1.5)
        if up is None:
            e = err
            if e.code == 403 and retry:                          # address went stale: look it up again
                _streams.pop(v, None)
                return self._stream(v, retry=False)
            if e.code == 416:
                self.send_response(416)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            raise
        with up:
            whole = not self.headers.get("Range") and up.status == 206   # the TV asked for all of it: answer like it did
            self.send_response(200 if whole else up.status)
            for h in ("Content-Type", "Content-Length") + (() if whole else ("Content-Range",)):
                if up.headers.get(h):
                    self.send_header(h, up.headers[h])
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            try:
                while True:
                    chunk = up.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass                                             # the TV seeked or skipped


def _try(fn, *a):
    try:
        fn(*a)
    except Exception as e:
        log(fn.__name__, a, repr(e))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8097)
    ap.add_argument("--build", action="store_true", help="build discovery now and exit")
    args = ap.parse_args()
    os.makedirs(DATA, exist_ok=True)
    YT.get()
    if args.build:
        return build_discovery()
    threading.Thread(target=discovery_loop, daemon=True).start()
    log(f"music on :{args.port}")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
