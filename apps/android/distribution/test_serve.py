import hashlib
import http.client
import tempfile
import threading
import unittest
from pathlib import Path
from serve import DownloadServer


class DownloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.apk = Path(self.temp.name) / "preview.apk"
        self.blob = b"test-apk-content"
        self.apk.write_bytes(self.blob)
        self.server = DownloadServer(("127.0.0.1", 0), "127.0.0.0/8", self.apk, "0.1.1", Path(__file__).with_name("index.html"))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.temp.cleanup()

    def request(self, path, method="GET", headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        conn.request(method, path, headers=headers or {})
        response = conn.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        conn.close()
        return result

    def test_artifact_and_checksum_match(self):
        status, headers, body = self.request("/app.apk")
        self.assertEqual((status, body), (200, self.blob))
        self.assertIn("attachment", headers["Content-Disposition"])
        self.assertIn(hashlib.sha256(body).hexdigest().encode(), self.request("/SHA256SUMS")[2])
        self.assertEqual(self.request("/app.apk", "HEAD")[2], b"")
        self.assertIn(b"0.1.1", self.request("/")[2])

    def test_only_allowlisted_routes_and_methods(self):
        for path in ("/../README.md", "/%2e%2e/.ssh/id_ed25519", "/serve.py", "/.git/config"):
            self.assertEqual(self.request(path)[0], 404)
        self.assertEqual(self.request("/", "POST")[0], 501)
        self.assertEqual(self.request("/", headers={"Host": "attacker.example"})[0], 403)

    def test_source_and_bind_boundaries(self):
        self.assertFalse(self.server.verify_request(None, ("198.51.100.1", 1000)))
        self.assertTrue(self.server.verify_request(None, ("127.0.0.1", 1000)))
        with self.assertRaises(ValueError):
            DownloadServer(("0.0.0.0", 0), "0.0.0.0/0", self.apk, "x", Path(__file__).with_name("index.html"))


if __name__ == "__main__":
    unittest.main()
