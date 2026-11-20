"""Live Fantastic API contract check: python3 Fantastic/tests/smoke.py."""
import json
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


BASE = "https://music.1412.tech/qq/"


def get(path):
    with urlopen(Request(BASE + path, headers={"User-Agent": "Mozilla/5.0"}), timeout=90) as response:
        return json.load(response)


class FantasticSmoke(unittest.TestCase):
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
