#!/usr/bin/env python3
"""Read or set the camera clock over OSC (HTTP; camera WiFi AP).

Insta360 X-series cameras expose an OSC API on their WiFi access point
(http://192.168.42.1). The X5's Timecode feature with "Sync system time"
uses the camera clock as the timecode reference, so setting both cameras'
clocks to the same value is the software-only way to align their timecode
tracks (the official app does the same thing with a network-time reference).

IMPORTANT: this must be run with the computer connected to the CAMERA's
WiFi AP. Joining the camera AP disconnects this computer from other
networks (including any remote session over WiFi).

Typical two-camera workflow (with `nmcli`):

    nmcli dev wifi connect "X5 ABCDEF.OSC" password 88888888   # camera A hotspot
    python osc_settime.py --set
    nmcli dev wifi connect "X5 GHIJKL.OSC" password 88888888   # camera B hotspot
    python osc_settime.py --set
    nmcli connection up "<your normal network>"

Both cameras then hold the same clock (difference = setting latency,
typically well under 100 ms; measure it by re-reading both).

Usage:
    python osc_settime.py                  # read-only report
    python osc_settime.py --set            # set clock to this computer's local time, then verify
    python osc_settime.py --host 192.168.42.1
"""

import argparse
import datetime
import json
import sys
import time
import urllib.request

OPTIONS = ["_dateTime", "_dateTimeZone", "_sysTimestamp"]

# The camera lives on a private network. Never route these requests through
# an HTTP proxy: a local proxy (e.g. Clash on 127.0.0.1:7897) answers 502
# Bad Gateway for the unreachable camera IP, and Python's no_proxy matching
# does not understand CIDR entries like "192.168.0.0/16".
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def post(host: str, name: str, parameters: dict, timeout: float = 10.0) -> dict:
    body = json.dumps({"name": name, "parameters": parameters}).encode()
    req = urllib.request.Request(
        f"http://{host}/osc/commands/execute",
        data=body,
        headers={"Content-Type": "application/json;charset=utf-8"},
    )
    with _OPENER.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def get(host: str, path: str, timeout: float = 10.0) -> dict:
    with _OPENER.open(f"http://{host}{path}", timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def read_options(host: str) -> dict:
    resp = post(host, "camera.getOptions", {"optionNames": OPTIONS})
    return resp.get("results", {}).get("options", {}), resp


def fmt_opt(value) -> str:
    if isinstance(value, (int, float)) and value > 10**12:
        try:
            return f"{value} ({datetime.datetime.fromtimestamp(value / 1e6).isoformat(sep=' ', timespec='milliseconds')})"
        except Exception:
            return str(value)
    return str(value)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="192.168.42.1", help="camera IP (default 192.168.42.1)")
    ap.add_argument("--set", action="store_true", help="set the clock to this computer's local time")
    ap.add_argument("--timeout", type=float, default=10.0)
    args = ap.parse_args()

    try:
        info = get(args.host, "/osc/info", args.timeout)
    except Exception as exc:  # noqa: BLE001
        sys.exit(
            f"cannot reach http://{args.host}/osc/info: {exc}\n"
            "Is this computer connected to the camera's WiFi AP?"
        )

    print(f"camera:  {info.get('model', '?')}  firmware {info.get('firmwareVersion', '?')}")

    opts, raw = read_options(args.host)
    for key in OPTIONS:
        if key in opts:
            print(f"before:  {key} = {fmt_opt(opts[key])}")
    if not opts:
        print("before:  (no options returned)", json.dumps(raw)[:200])

    if not args.set:
        print("\nread-only mode; use --set to set the clock to this computer's local time")
        return

    value = datetime.datetime.now().strftime("%Y:%m:%d %H:%M:%S")
    resp = post(args.host, "camera.setOptions", {"options": {"_dateTime": value}}, args.timeout)
    print(f"\nset _dateTime = {value} -> {json.dumps(resp, ensure_ascii=False)}")

    time.sleep(1.0)
    opts2, raw2 = read_options(args.host)
    for key in OPTIONS:
        if key in opts2:
            print(f"after:   {key} = {fmt_opt(opts2[key])}")

    first = raw2.get("results")
    if resp.get("state") != "done" and not first:
        print("\nNOTE: the camera did not confirm the set; _dateTime may be read-only on this model/firmware.")


if __name__ == "__main__":
    main()
