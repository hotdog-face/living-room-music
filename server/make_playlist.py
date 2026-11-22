#!/usr/bin/env python3
"""Makes a living-room playlist (/srv/music/playlists/<slug>.json + <slug>.jpg) from a list of songs, one per line:
`Artist — Song — Album` (the album is optional, a hint for which version; `#` lines are comments). Each song is found on YouTube Music
(signed out is fine) by artist + title; songs you own in Plex are taken from Plex instead, so they play from your files.
Run with the server's venv on the music server:

    /srv/music/venv/bin/python make_playlist.py <slug> "<Title>" "<subtitle>" songs.txt [--dry]

Prints what it matched and what it couldn't, so a bad match can be fixed in the list (`Artist — Song @ <videoId>`
forces a YouTube Music video id).
"""
import io
import json
import os
import re
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import music  # noqa: E402  (the server module: YT client, track(), Plex index)


def norm(s):
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower().replace("&", "and"))


def parse(path):
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        force = None
        if " @ " in line:
            line, force = [x.strip() for x in line.rsplit(" @ ", 1)]
        parts = [p.strip() for p in re.split(r"\s+[—–]\s+", line)]
        if len(parts) >= 2:
            yield parts[0], parts[1], (parts[2] if len(parts) > 2 else None), force


def plex_match(artist, title, owned):
    return owned.get(music.song_key(title, artist))


def loose(s):
    """Romanizations differ (Omura/Ohmura, Ohnuki/Onuki, Ohashi/Oohashi): fold long vowels."""
    s = norm(s)
    for a, b in (("ou", "o"), ("oh", "o"), ("oo", "o"), ("uu", "u")):
        s = s.replace(a, b)
    return s


def yt_match(yt, artist, title, album=None):
    """The song by that artist (never a cover by someone else): the album version if the list names the album, else
    the first studio take."""
    want_a = loose(artist.split("&")[0])
    want_t = loose(re.sub(r"\(.*?\)", "", title))
    found = []
    for q in ([f"{artist} {title} {album}"] if album else []) + [f"{artist} {title}"]:
        try:
            res = yt.search(q, filter="songs", limit=10)
        except Exception as e:
            music.log("search", q, e)
            continue
        for r in res:
            names = loose(" ".join(a.get("name", "") for a in r.get("artists") or []))
            t = loose(re.sub(r"\(.*?\)", "", r.get("title", "")))
            if want_t and t and (want_t in t or t in want_t) and want_a in names and r not in found:
                found.append(r)
    if not found:
        return None
    alb = loose(album or "")
    studio = [r for r in found if not re.search(r"\blive\b", (r.get("title", "") + str((r.get("album") or {}).get("name"))).lower())]
    return (next((r for r in found if alb and alb in loose((r.get("album") or {}).get("name"))), None)
            or (studio[0] if studio else found[0]))


def cover(tracks, slug, title):
    """A 2x2 of four different album covers, with the playlist's name on a frosted band."""
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
    arts, seen = [], set()
    for t in tracks:
        a, alb = t.get("art"), t.get("album") or t["title"]
        if not a or alb in seen or not a.startswith("http"):
            continue
        try:
            arts.append(Image.open(io.BytesIO(urllib.request.urlopen(a, timeout=20).read())).convert("RGB"))
            seen.add(alb)
        except Exception:
            continue
        if len(arts) == 4:
            break
    S, h = 1000, 500
    im = Image.new("RGB", (S, S), (24, 20, 18))
    for i, a in enumerate(arts):
        im.paste(a.resize((h, h), Image.LANCZOS), ((i % 2) * h, (i // 2) * h))
    band = (0, 380, S, 620)
    im.paste(Image.blend(im.crop(band).filter(ImageFilter.GaussianBlur(26)), Image.new("RGB", (S, 240), (12, 10, 10)), 0.5), band[:2])
    d = ImageDraw.Draw(im)
    f = next((ImageFont.truetype(p, 84) for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",) if os.path.exists(p)),
             ImageFont.load_default())
    words = title.split()
    lines = [" ".join(words[:len(words) // 2 or 1]), " ".join(words[len(words) // 2 or 1:])] if len(title) > 16 else [title]
    y = 500 - 50 * len(lines)
    for ln in lines:
        d.text(((S - d.textlength(ln, font=f)) / 2, y), ln, font=f, fill=(255, 255, 255))
        y += 100
    path = os.path.join(music.PLAYLISTS, slug + ".jpg")
    im.save(path, "JPEG", quality=90)
    return f"/plart?n={slug}"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry" in sys.argv
    slug, title, subtitle, src = args
    yt = music.YT.get()
    owned = music.plex_index()
    out, missing, seen = [], [], set()
    for artist, song, album, force in parse(src):
        x = None
        p = plex_match(artist, song, owned)
        if p:
            x = music.p_track(p)
            how = "plex"
        elif force:
            try:
                thumbs = yt.get_song(force)["videoDetails"]["thumbnail"]["thumbnails"]
            except Exception:
                thumbs = None
            x = music.track({"videoId": force, "title": song, "artists": [{"name": artist}], "thumbnails": thumbs,
                             "album": {"name": album} if album else None})
            how = "forced"
        else:
            r = yt_match(yt, artist, song, album)
            x = music.track(r) if r else None
            how = "yt"
        if not x or x["v"] in seen:
            missing.append(f"{artist} — {song}")
            print(f"  MISSING  {artist} — {song}")
            continue
        seen.add(x["v"])
        out.append(x)
        print(f"  {how:6} {artist} — {song}  =>  {x['artist']} — {x['title']} ({x.get('album') or ''})")
    print(f"{len(out)} songs, {len(missing)} not found")
    if dry:
        return
    os.makedirs(music.PLAYLISTS, exist_ok=True)
    doc = {"title": title, "subtitle": subtitle, "tracks": out}
    try:
        doc["art"] = cover(out, slug, title)
    except Exception as e:
        print("cover:", e)
        doc["art"] = next((t["art"] for t in out if t.get("art")), None)
    tmp = os.path.join(music.PLAYLISTS, slug + ".json.tmp")
    json.dump(doc, open(tmp, "w"), ensure_ascii=False, indent=1)
    os.replace(tmp, os.path.join(music.PLAYLISTS, slug + ".json"))
    print("wrote", os.path.join(music.PLAYLISTS, slug + ".json"))


if __name__ == "__main__":
    main()
