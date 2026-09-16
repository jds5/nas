#!/usr/bin/env python3
"""只读 LAN APK 下载站；仅提供页面、固定 APK 和校验和，不暴露工作目录。"""
import argparse
import hashlib
import html
import ipaddress
import re
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class DownloadServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, allowed, apk, version, template):
        self.allowed = ipaddress.ip_network(allowed)
        bind = ipaddress.ip_address(address[0])
        private = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")]
        if not any(self.allowed.subnet_of(n) for n in private) or bind not in self.allowed:
            raise ValueError("只允许绑定明确的 LAN / loopback IPv4 地址与来源网段")
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,32}", version):
            raise ValueError("版本号格式无效")
        if not 0 < apk.stat().st_size <= 64 * 1024 * 1024:
            raise ValueError("APK 大小无效")
        blob = apk.read_bytes()
        digest = hashlib.sha256(blob).hexdigest()
        filename = f"nas-remote-{version}.apk"
        page = template.read_text(encoding="utf-8")
        values = {
            "VERSION": version, "SIZE": f"{len(blob) / 1024 / 1024:.1f} MB",
            "SHA256": digest, "DATE": datetime.fromtimestamp(apk.stat().st_mtime, timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M %Z"),
        }
        for key, value in values.items():
            page = page.replace("@" + key + "@", html.escape(value))
        self.routes = {
            "/": (page.encode(), "text/html; charset=utf-8", None),
            "/app.apk": (blob, "application/vnd.android.package-archive", filename),
            "/SHA256SUMS": (f"{digest}  {filename}\n".encode(), "text/plain; charset=utf-8", "SHA256SUMS"),
        }
        super().__init__(address, DownloadHandler)
        self.hosts = {address[0], f"{address[0]}:{self.server_port}"}

    def verify_request(self, request, client_address):
        return ipaddress.ip_address(client_address[0]) in self.allowed

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(10)
        return sock, address


class DownloadHandler(BaseHTTPRequestHandler):
    server_version = "NasDownload"
    sys_version = ""

    def do_GET(self):
        self.respond(False)

    def do_HEAD(self):
        self.respond(True)

    def respond(self, head):
        # Reject DNS rebinding / unintended reverse proxy hostnames.
        if self.headers.get("Host", "") not in self.server.hosts:
            self.send_error(403)
            return
        route = self.server.routes.get(urlsplit(self.path).path)
        if route is None:
            self.send_error(404)
            return
        body, kind, filename = route
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()
        if not head:
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True)
    parser.add_argument("--allow", required=True, help="允许的客户端 IPv4 CIDR")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    server = DownloadServer((args.bind, args.port), args.allow, args.apk, args.version, Path(__file__).with_name("index.html"))
    print(f"LAN download: http://{args.bind}:{server.server_port}/ ({args.version})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
