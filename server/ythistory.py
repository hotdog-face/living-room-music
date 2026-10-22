"""YouTube (the video app) watch history, through the same sign-in as YouTube Music. Each entry: videoId, title, channel."""
import os, json, time, hashlib, urllib.request, re

C = os.path.expanduser("~/.config/living-room-tv/")
ORIGIN = "https://www.youtube.com"


def _req(body, h):
    cookie = h["cookie"]
    sap = re.search(r"(?:^|; )(?:__Secure-3PAPISID|SAPISID)=([^;]+)", cookie).group(1)
    ts = str(int(time.time()))
    auth = "SAPISIDHASH " + ts + "_" + hashlib.sha1(f"{ts} {sap} {ORIGIN}".encode()).hexdigest()
    r = urllib.request.Request(f"{ORIGIN}/youtubei/v1/browse?prettyPrint=false", data=json.dumps(body).encode(), headers={
        "Cookie": cookie, "Authorization": auth, "X-Goog-AuthUser": h.get("x-goog-authuser", "0"), "Origin": ORIGIN,
        "X-Origin": ORIGIN, "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"})
    return json.loads(urllib.request.urlopen(r, timeout=30).read())


def _walk(o, out, conts):
    if isinstance(o, dict):
        lk = o.get("lockupViewModel")
        if lk and lk.get("contentId"):
            meta = (lk.get("metadata") or {}).get("lockupMetadataViewModel") or {}
            title = (meta.get("title") or {}).get("content", "")
            rows = ((meta.get("metadata") or {}).get("contentMetadataViewModel") or {}).get("metadataRows") or []
            texts = [p.get("text", {}).get("content", "") for r in rows for p in r.get("metadataParts") or []]
            out.append({"id": lk["contentId"], "title": title, "channel": texts[0] if texts else ""})
            return
        t = o.get("continuationCommand", {}).get("token")
        if t:
            conts.append(t)
        for v in o.values():
            _walk(v, out, conts)
    elif isinstance(o, list):
        for v in o:
            _walk(v, out, conts)


def history(pages=20):
    h = {k.lower(): v for k, v in json.load(open(C + "ytmusic-browser.json")).items()}
    ctx = {"client": {"clientName": "WEB", "clientVersion": "2.20261020.00.00", "hl": "en"}}
    out, conts = [], []
    _walk(_req({"context": ctx, "browseId": "FEhistory"}, h), out, conts)
    for _ in range(pages - 1):
        if not conts:
            break
        tok = conts.pop()
        conts.clear()
        _walk(_req({"context": ctx, "continuation": tok}, h), out, conts)
    return out


if __name__ == "__main__":
    import collections, sys
    hist = history(int(sys.argv[1]) if len(sys.argv) > 1 else 20)
    json.dump(hist, open("/srv/music/kids/history.json", "w"))
    c = collections.Counter((e["id"], e["title"], e["channel"]) for e in hist)
    print(len(hist), "entries,", len(c), "videos")
    for (i, t, ch), n in c.most_common(150):
        print(n, "|", t, "|", ch, "|", i)
