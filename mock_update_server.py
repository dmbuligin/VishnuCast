
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
import argparse
import os
import time
import json
import mimetypes

DEFAULT_JSON = {
    "versionName": "v1.70000-mock",
    "body": "Mock release for testing the in-app updater. Includes fake notes and changelog.",
    "htmlUrl": "http://localhost/release-notes",
    "downloadUrl": "http://192.168.24.1:8000/apk",
    "assetName": "VishnuCast-1.7-mock.apk"
 }

class MockHandler(BaseHTTPRequestHandler):
    apk_path = None
    slow_chunk = 64 * 1024  # 64 KB
    slow_delay = 0.2        # seconds between chunks

    def _send_headers(self, code=200, length=None, ctype="application/octet-stream", extra_headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        if length is not None:
            self.send_header("Content-Length", str(length))
        if self.path.startswith("/apk"):
            filename = os.path.basename(self.apk_path) if self.apk_path else "VishnuCast-latest.apk"
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        if extra_headers:
            for k, v in (extra_headers or {}).items():
                self.send_header(k, v)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        route = parsed.path

        if route == "/releases/latest.json":
            data = DEFAULT_JSON.copy()
            if self.apk_path:
                data["assetName"] = os.path.basename(self.apk_path)
            body = json.dumps(data).encode("utf-8")
            self._send_headers(200, length=len(body), ctype="application/json")
            self.wfile.write(body)
            return

        if route == "/apk-redirect":
            self._send_headers(302, extra_headers={"Location": "/apk"})
            return

        if route == "/apk-404":
            self._send_headers(404, length=0)
            return

        if route == "/apk-500":
            body = b"Internal Server Error (simulated)"
            self._send_headers(500, length=len(body), ctype="text/plain; charset=utf-8")
            self.wfile.write(body)
            return

        if route in ("/apk", "/apk-slow"):
            if not self.apk_path or not os.path.isfile(self.apk_path):
                body = b"APK file not found on server. Start the script with --apk <path-to-apk>"
                self._send_headers(500, length=len(body), ctype="text/plain; charset=utf-8")
                self.wfile.write(body)
                return

            ctype = mimetypes.guess_type(self.apk_path)[0] or "application/vnd.android.package-archive"
            if "android" not in ctype:
                ctype = "application/vnd.android.package-archive"

            size = os.path.getsize(self.apk_path)
            if route == "/apk":
                self._send_headers(200, length=size, ctype=ctype)
                with open(self.apk_path, "rb") as f:
                    while True:
                        chunk = f.read(256 * 1024)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                return

            if route == "/apk-slow":
                self._send_headers(200, length=size, ctype=ctype)
                with open(self.apk_path, "rb") as f:
                    while True:
                        chunk = f.read(self.slow_chunk)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        self.wfile.flush()
                        time.sleep(self.slow_delay)
                return

        body = b"Mock update server is running.\\n" \
               b"Endpoints: /apk, /apk-slow, /apk-redirect, /apk-404, /apk-500, /releases/latest.json\\n"
        self._send_headers(200, length=len(body), ctype="text/plain; charset=utf-8")
        self.wfile.write(body)

def main():
    parser = argparse.ArgumentParser(description="Mock HTTP server for testing VishnuCast updater")
    parser.add_argument("--apk", required=False, help="Path to APK file to serve")
    parser.add_argument("--port", type=int, default=8000, help="Port to listen on (default 8000)")
    parser.add_argument("--slow-chunk", type=int, default=64*1024, help="Chunk size for /apk-slow in bytes")
    parser.add_argument("--slow-delay", type=float, default=0.2, help="Delay between chunks for /apk-slow (seconds)")
    args = parser.parse_args()

    MockHandler.apk_path = args.apk
    MockHandler.slow_chunk = args.slow_chunk
    MockHandler.slow_delay = args.slow_delay

    httpd = HTTPServer(("0.0.0.0", args.port), MockHandler)
    print(f"Mock update server listening on http://0.0.0.0:{args.port}")
    if args.apk:
        print(f"Serving APK from: {args.apk}")
        print("Endpoints: /apk (normal), /apk-slow (throttled), /apk-redirect, /apk-404, /apk-500")
    else:
        print("WARNING: No --apk supplied. /apk endpoints will return 500.")
    httpd.serve_forever()

if __name__ == "__main__":
    main()
