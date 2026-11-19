import json, re, urllib.request, urllib.parse, urllib.error
UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
def req(url, method="GET", body=None, headers=None, show=600):
    h = {"Accept": "application/json, text/html;q=0.9, */*;q=0.8", "User-Agent": UA}; h.update(headers or {})
    data = json.dumps(body).encode() if body is not None else None
    if data: h["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h, method=method), timeout=25) as r:
            code, text, final = r.status, r.read().decode("utf-8", "replace"), r.geturl()
    except urllib.error.HTTPError as e: code, text, final = e.code, e.read().decode("utf-8", "replace"), url
    except Exception as e:
        print(f"  {method} {url}\n  !! {type(e).__name__}: {e}"); return None, ""
    print(f"  {method} {url} -> {code}" + (f" (final {final})" if final != url else ""))
    if show: print("  " + text[:show].replace("\n", " "))
    return code, text
def js(t):
    try: return json.loads(t)
    except Exception: return None
qe = urllib.parse.urlencode

print("== Deezer ISRC")
c, t = req("https://api.deezer.com/search?" + qe({"q": "Believer Imagine Dragons", "limit": 1}), show=0)
did = js(t)["data"][0]["id"]
c, t = req(f"https://api.deezer.com/track/{did}", show=0); isrc = js(t)["isrc"]; print("  isrc", isrc)
req("https://dzr.tabs-vs-spaces.wtf/", show=1500)
for p in (f"/track/?isrc={isrc}", f"/track?isrc={isrc}", f"/track/?isrc={isrc}&quality=FLAC", f"/track/?isrc={isrc}&format=FLAC"):
    req("https://dzr.tabs-vs-spaces.wtf" + p, show=1500)

print("== LastBabe page")
c, t = req("https://lastbabe.velixir.run/", show=0)
print("  scripts:", re.findall(r'<script[^>]*src="([^"]+)"', t))
for m in sorted(set(re.findall(r'''(?:fetch\(|['"`])(/[a-zA-Z0-9_/\-\.]{2,60})''', t))): print("  path:", m)
inline = re.findall(r"<script[^>]*>(.*?)</script>", t, re.S)
for s in inline: print("  INLINE JS:", s[:4000].replace("\n", " "))
print("  TEXT:", re.sub(r"<[^>]+>", " ", re.sub(r"<(script|style)[^>]*>.*?</\1>", "", t, flags=re.S)).split()[:150])

print("== Qobuz-DL instances")
for base in ("https://qobuz.squid.wtf", "https://eu.qobuz.squid.wtf", "https://us.qobuz.squid.wtf", "https://qobuz-dll.foreyou.workers.dev"):
    c, t = req(base + "/api/get-music?" + qe({"q": "Believer Imagine Dragons", "offset": 0}), show=300)
    j = js(t)
    try:
        tid = j["data"]["tracks"]["items"][0]["id"]
        req(base + "/api/download-music?" + qe({"track_id": tid, "quality": 27}), show=500)
    except Exception: pass

print("== Instable / Arcod")
req("https://instable.arcod.xyz/", show=800)
req("https://instable.arcod.xyz/api/auth/login", "POST", {"email": "", "password": ""}, show=400)
req("https://player.arcod.xyz/api/player/search?" + qe({"q": "Believer", "limit": 2, "offset": 0}), show=300)

print("== Eljohnzc host")
req("https://eljohnzc-addon.de.deplexo.com/", show=300)
