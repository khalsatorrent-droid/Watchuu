"""Live Fantastic API contract check: python3 Fantastic/tests/smoke.py."""
import json
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


BASE = "https://music.1412.tech/qq/"
HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": BASE.removesuffix("qq/")}


def get(path):
    with urlopen(Request(BASE + path, headers=HEADERS), timeout=90) as response:
        return json.load(response)


class FantasticSmoke(unittest.TestCase):
    def test_flac_and_range(self):
        song = get("search?" + urlencode({"q": "Hello Adele", "type": "song", "limit": 1}))["songs"][0]
        path = f"song/{song['id']}"
        qualities = get(path + "/qualities?basic=1")["qualities"]
        self.assertTrue(any(row["quality"] == "SQ" and row["available"] for row in qualities))
        for byte_range in ("bytes=0-1023", "bytes=1024-2047"):
            with urlopen(Request(BASE + path + "/download?quality=SQ",
                                 headers={**HEADERS, "Range": byte_range}), timeout=90) as audio:
                prefix = audio.read(1024)
                self.assertEqual(audio.headers["Content-Type"], "audio/flac")
                self.assertIn(audio.status, (200, 206))
                if audio.status == 200 or byte_range == "bytes=0-1023":
                    self.assertEqual(prefix[:4], b"fLaC")
                if audio.status == 206:
                    self.assertTrue(audio.headers.get("Content-Range", "").startswith(
                        "bytes " + byte_range.removeprefix("bytes=") + "/"))
                print(f"FLAC Range {byte_range}: HTTP {audio.status}, "
                      f"Content-Range={audio.headers.get('Content-Range')}, signature={prefix[:4]!r}", flush=True)

    def test_home_and_playlist_pagination(self):
        for genre in ("Mandopop", "Pop", "Rock", "Classical"):
            result = get("search?" + urlencode({"q": genre, "type": "song", "limit": 30}))
            self.assertTrue(result["songs"])
            print(f"home {genre}: {len(result['songs'])} songs", flush=True)
        playlist = get("search?" + urlencode({"q": "Hello", "type": "playlist", "limit": 1}))["playlists"][0]
        first = get(f"playlist/{playlist['id']}?limit=1&offset=0")
        self.assertTrue(first["songs"])
        if first["more"]:
            second = get(f"playlist/{playlist['id']}?limit=1&offset=1")
            self.assertTrue(second["songs"])
            self.assertNotEqual(first["songs"][0]["id"], second["songs"][0]["id"])
            print("playlist pagination: distinct consecutive tracks OK", flush=True)

    def test_search_details_and_audio(self):
        for kind in ("song", "album", "playlist", "artist"):
            with self.subTest(kind=kind):
                results = get("search?" + urlencode({"q": "Hello", "type": kind, "limit": 1}))
                item = results[kind + "s"][0]
                item_id = item["mid" if kind in ("album", "artist") else "id"]
                detail = get(f"{kind}/{item_id}")
                self.assertTrue(detail["name"] if kind == "song" else detail["songs"])
                print(f"{kind}: search -> detail OK", flush=True)
                if kind != "song":
                    continue
                qualities = get(f"song/{item_id}/qualities?basic=1")["qualities"]
                self.assertTrue(any(q["quality"] == "HQ" and q["available"] for q in qualities))
                request = Request(BASE + f"song/{item_id}/download?quality=HQ",
                                  headers={"Range": "bytes=0-1023", "Referer": BASE.removesuffix("qq/"),
                                           "User-Agent": "Mozilla/5.0"})
                with urlopen(request, timeout=90) as audio:
                    self.assertIn(audio.status, (200, 206))
                    self.assertIn("audio/", audio.headers["Content-Type"])
                    prefix = audio.read(1024)
                    self.assertTrue(prefix.startswith(b"ID3") or prefix[0] == 255)
                    print(f"audio: HTTP {audio.status}, {audio.headers['Content-Type']}, "
                          f"{len(prefix)} bytes, signature={prefix[:3]!r}", flush=True)

    def test_empty_results(self):
        result = get("search?" + urlencode({"q": "Hello", "type": "song", "limit": 1, "offset": 10000}))
        self.assertEqual(result["songs"], [])
        print("empty search: [] OK", flush=True)

    def test_invalid_query(self):
        with self.assertRaises(HTTPError) as error:
            get("search?q=")
        self.assertEqual(error.exception.code, 400)
        print("invalid query: HTTP 400 OK", flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
