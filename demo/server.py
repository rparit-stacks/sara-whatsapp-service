#!/usr/bin/env python3
"""Local demo for the Meta App Review video.

Serves one page on http://127.0.0.1:8099 with a button that sends a template message through the
WhatsApp Cloud API. The token stays on the server side and never reaches the browser. It is meant
for the screen recording only: it listens on localhost and is not part of the service.
"""
import json
import os
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

HERE = Path(__file__).parent


def load_env():
    values = {}
    env_file = HERE / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    for key in ("DEMO_PHONE_NUMBER_ID", "DEMO_ACCESS_TOKEN", "DEMO_GRAPH_VERSION", "DEMO_TEMPLATE", "DEMO_LANGUAGE"):
        if os.environ.get(key):
            values[key] = os.environ[key]
    return values


CONFIG = load_env()
PHONE_NUMBER_ID = CONFIG.get("DEMO_PHONE_NUMBER_ID", "")
TOKEN = CONFIG.get("DEMO_ACCESS_TOKEN", "")
VERSION = CONFIG.get("DEMO_GRAPH_VERSION", "v24.0")
TEMPLATE = CONFIG.get("DEMO_TEMPLATE", "hello_world")
LANGUAGE = CONFIG.get("DEMO_LANGUAGE", "en_US")


def build_payload(to):
    return {
        "messaging_product": "whatsapp",
        "to": to,
        "type": "template",
        "template": {"name": TEMPLATE, "language": {"code": LANGUAGE}},
    }


def send(to):
    url = f"https://graph.facebook.com/{VERSION}/{PHONE_NUMBER_ID}/messages"
    payload = build_payload(to)
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status, body = response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read().decode()
    except Exception as error:  # network problem
        status, body = 0, json.dumps({"error": str(error)})
    try:
        parsed = json.loads(body)
    except ValueError:
        parsed = {"raw": body}
    return {"request": {"url": url, "body": payload}, "status": status, "response": parsed}


class Handler(BaseHTTPRequestHandler):
    def _reply(self, status, content_type, body):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self._reply(200, "text/html; charset=utf-8", (HERE / "index.html").read_bytes())
        elif self.path == "/logo.png" and (HERE / "logo.png").exists():
            self._reply(200, "image/png", (HERE / "logo.png").read_bytes())
        else:
            self._reply(404, "text/plain", "not found")

    def do_POST(self):
        if self.path != "/send":
            self._reply(404, "text/plain", "not found")
            return
        if not PHONE_NUMBER_ID or not TOKEN:
            self._reply(500, "application/json", json.dumps(
                {"error": "Set DEMO_PHONE_NUMBER_ID and DEMO_ACCESS_TOKEN in demo/.env first"}))
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            to = "".join(ch for ch in json.loads(self.rfile.read(length) or b"{}").get("to", "") if ch.isdigit())
        except ValueError:
            to = ""
        if not 8 <= len(to) <= 15:
            self._reply(400, "application/json", json.dumps({"error": "Enter the number with country code, digits only"}))
            return
        self._reply(200, "application/json", json.dumps(send(to)))

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 8099), Handler)
    print("Demo running on http://127.0.0.1:8099  (Ctrl+C to stop)")
    server.serve_forever()
