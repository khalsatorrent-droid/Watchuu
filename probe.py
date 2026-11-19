import json, sys, hashlib, urllib.request, urllib.parse, urllib.error

UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

def req(url, method="GET", body=None, headers=None, show=700):
    h = {"Accept": "application/json", "User-Agent": UA}
    h.update(headers or {})
    data = json.dumps(body).encode() if body is not None else None
    if data: h["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            code, text, final = resp.status, resp.read().decode("utf-8", "replace"), resp.geturl()
    except urllib.error.HTTPError as e:
        code, text, final = e.code, e.read().decode("utf-8", "replace"), url
    except Exception as e:
        print(f"  {method} {url}\n  !! {type(e).__name__}: {e}")
        return None, None
    print(f"  {method} {url} -> {code}" + (f" (final {final})" if final != url else ""))
    print("  " + text[:show].replace("\n", " "))
    try: return code, json.loads(text)
    except Exception: return code, None

def q(d): return urllib.parse.urlencode(d)

Q = "Believer Imagine Dragons"

print("== Qobuz-DL")
base = "https://qobuz-dll.foreyou.workers.dev"
c, j = req(f"{base}/api/get-music?{q({'q': Q, 'offset': '0'})}")
tid = None
try: tid = j["data"]["tracks"]["items"][0]["id"]
except Exception: pass
if tid:
    for qual in ("27", "7", "6"):
        req(f"{base}/api/download-music?{q({'track_id': tid, 'quality': qual})}")

print("== Instable")
base = "https://instable.arcod.xyz"
ih = {"Referer": base + "/", "Origin": base, "Accept-Language": "en-US,en;q=0.9"}
c, j = req(f"{base}/api/search?{q({'q': Q})}", headers=ih)
tid = None
try: tid = j["data"]["tracks"]["items"][0]["id"]
except Exception: pass
if tid:
    req(f"{base}/api/player/stream/{tid}?{q({'quality': '27'})}", headers=ih)

for name, base in (("Deezer", "https://dzr.tabs-vs-spaces.wtf"),
                   ("Eljohnzc", "https://eljohnzc-addon.de.deplexo.com/4433c7caff7fd070309e")):
    print("== " + name)
    req(f"{base}/manifest.json", show=400)
    c, j = req(f"{base}/search?{q({'q': Q, 'quality': 'LOSSLESS', 'atmos': 'auto'})}")
    tid = None
    try:
        items = j["tracks"] if isinstance(j, dict) else j
        tid = items[0]["id"]
    except Exception: pass
    if tid:
        req(f"{base}/stream/{urllib.parse.quote(str(tid))}?{q({'quality': 'LOSSLESS', 'atmos': 'auto'})}")

print("== LastBabe")
root = "https://lastbabe.velixir.run/"
c, ch = req(root + "challenge")
if isinstance(ch, dict) and isinstance(ch.get("challenge"), str):
    d = ch.get("difficulty") if isinstance(ch.get("difficulty"), int) and 1 <= ch.get("difficulty") <= 8 else 4
    n = 0
    while True:
        n += 1
        if hashlib.sha256((ch["challenge"] + str(n)).encode()).hexdigest().startswith("0" * d): break
    print(f"  solved difficulty {d} nonce {n}")
    c, m = req(root + "mint", "POST", {"challenge": ch["challenge"], "nonce": str(n)})
    mu = isinstance(m, dict) and (m.get("manifestUrl") or m.get("addonUrl"))
    if mu:
        b = mu.removesuffix("/manifest.json").rstrip("/")
        c, j = req(f"{b}/search?{q({'q': Q})}")
        try:
            t = j["tracks"][0]; tid = t.get("trackId") or t.get("id")
            req(f"{b}/stream/{urllib.parse.quote(str(tid))}?{q({'quality': 'LOSSLESS'})}")
        except Exception as e: print("  no track", e)
