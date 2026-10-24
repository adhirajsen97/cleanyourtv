#!/usr/bin/env python3
"""A stand-in for the cove bridge, for trying the TV's HTTP commands before the bridge exists.

Usage: python3 -I lights/stub-bridge.py [--port 8765] [--config OUT.json]

Answers like ESPHome's web server: POST /button/<id>/press is 200 for the buttons a bridge for the
cove would have (cove_on, cove_off, cove_brighter, cove_dimmer, cove_warm, cove_neutral, cove_cool)
and 404 for anything else, which shows how a failure reads on the TV's status line. Every request
is printed with the sender's address. --config writes a lights.json to push to the TV with
lights/push-config.sh: the built-in list, plus Cove commands pointing at this computer and one,
"bad", that the stub rejects.
"""
import argparse
import json
import os
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BUTTONS = ["cove_on", "cove_off", "cove_brighter", "cove_dimmer", "cove_warm", "cove_neutral", "cove_cool"]
COMMANDS = {"on": "cove_on", "off": "cove_off", "brighter": "cove_brighter", "dimmer": "cove_dimmer",
            "warm": "cove_warm", "neutral": "cove_neutral", "cool": "cove_cool"}


def lan_address():
    """The address of this computer on the TV's network: nothing is sent, the route decides."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((os.environ.get("TV_HOST", "192.168.1.26"), 9))
        return s.getsockname()[0]
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        name = self.path[len("/button/"):-len("/press")] if self.path.startswith("/button/") and self.path.endswith("/press") else ""
        status = 200 if name in BUTTONS else 404
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()
        print(f"{self.command} {self.path} from {self.client_address[0]} -> {status}" + (f"  body {body!r}" if body else ""), flush=True)

    do_GET = do_POST
    do_PUT = do_POST

    def log_message(self, *args):
        pass


def write_config(path, base):
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "app", "src", "main", "assets", "lights.json"), encoding="utf-8") as f:
        config = json.load(f)
    for light in config["lights"]:
        if light["id"] == "cove":
            light["commands"] = {command: [{"url": f"{base}/button/{button}/press"}] for command, button in COMMANDS.items()}
            light["commands"]["bad"] = [{"url": f"{base}/button/no_such_button/press"}]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
        f.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--config", help="write a lights.json whose Cove commands point at this stub")
    args = parser.parse_args()
    base = f"http://{lan_address()}:{args.port}"
    if args.config:
        write_config(args.config, base)
        print(f"wrote {args.config}: Cove commands point at {base}", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"stub bridge at {base}; Ctrl-C stops it", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
