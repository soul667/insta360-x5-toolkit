#!/usr/bin/env bash
# Download the latest clips from every reachable Insta360 camera over WiFi/OSC.
#
#   ./fetch_clips.sh [count_per_camera]      # default: 2
#   X5_AP_PASSWORD=xxxx ./fetch_clips.sh 3   # if the camera AP uses another password
#
# Requirements: NetworkManager (nmcli), curl, python3, and the enhanced
# insta360ctl on PATH (or set CTL=/path/to/insta360ctl).
#
# What it does:
#   1. Holds a BLE session to all Insta360 cameras (keeps them awake) and sends
#      OPEN_CAMERA_WIFI (0x21)
#   2. Waits for the "*.OSC" hotspots and connects (default password 88888888)
#   3. Lists the newest videos via the camera OSC API and downloads them
#      into ./clips/<hotspot>/
#   4. Restores the original WiFi connection
#
# Notes:
#   - Cameras auto power off after a few idle minutes and cannot be woken
#     remotely: power them on before running this script.
#   - .insv files require Insta360 Studio for playback/export.
set -uo pipefail

CTL="${CTL:-insta360ctl}"
PY="${PY:-python3}"
COUNT="${1:-2}"
DEST="${DEST:-$(pwd)/clips}"
PASS="${X5_AP_PASSWORD:-88888888}"
IFACE="${WIFI_IFACE:-$(nmcli -t -f DEVICE,STATE device 2>/dev/null | awk -F: '$2=="connected" && $1 ~ /^wl/ {print $1; exit}')}"
if [[ -z "$IFACE" ]]; then
  echo "ERROR: no connected WiFi interface found; set WIFI_IFACE=wlan0" >&2
  exit 1
fi

ORIG_CON="$(nmcli -t -f NAME,DEVICE connection show --active 2>/dev/null | awk -F: -v i="$IFACE" '$2==i{print $1; exit}')"
echo "Original WiFi connection: ${ORIG_CON:-<none>}"
restore() {
  [[ -n "${ORIG_CON:-}" ]] || return 0
  echo "== Restoring network: $ORIG_CON =="
  timeout 60 nmcli --wait 45 connection up "$ORIG_CON" >/dev/null 2>&1 || true
}
trap restore EXIT
trap 'echo; echo "== Interrupted, restoring network =="; exit 130' INT TERM

mkdir -p "$DEST"

# 1) Hold a BLE session (keeps cameras awake) and open their WiFi.
#    The command has no ACK, so a timeout here is expected.
echo "== Opening camera WiFi over BLE (holding the session) =="
( echo "raw 21"; sleep 900 ) | timeout 920 "$CTL" multi shell \
  --all --scan-window 6s --command-timeout 8s >/dev/null 2>&1 &
KEEPALIVE_PID=$!
sleep 5

# 2) Wait for the camera hotspots.
list_ssids() {
  nmcli -t -f SSID dev wifi list 2>/dev/null | sed 's/^ *//; s/ *$//' | grep -i '\.OSC$' | sort -u
}
mapfile -t SSIDS < <(list_ssids)
for attempt in 1 2 3; do
  [[ ${#SSIDS[@]} -gt 0 ]] && break
  echo "  hotspot not seen yet (attempt $attempt), rescanning..."
  nmcli dev wifi rescan >/dev/null 2>&1 || true
  sleep 6
  mapfile -t SSIDS < <(list_ssids)
done
if [[ ${#SSIDS[@]} -eq 0 ]]; then
  echo "ERROR: no '*.OSC' hotspot found."
  echo "  - Are the cameras powered on? (X5 auto-powers-off when idle)"
  echo "  - Is WiFi enabled on the camera screen?"
  exit 1
fi
echo "Found: ${SSIDS[*]}"

# 3) Connect and download.
#    The PSK is written into a stored profile (psk-flags 0) so NetworkManager
#    never pops up a desktop password prompt.
connect_ssid() {
  local ssid="$1"
  if nmcli -t -f NAME connection show | grep -qxF "$ssid"; then
    nmcli connection modify "$ssid" wifi-sec.psk "$PASS" wifi-sec.psk-flags 0 >/dev/null 2>&1 || true
  else
    nmcli connection add type wifi con-name "$ssid" ssid "$ssid" \
      wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$PASS" \
      ipv4.never-default yes ipv4.route-metric 30000 ipv6.method disabled >/dev/null 2>&1
  fi
  timeout 95 nmcli --wait 60 connection up "$ssid"
}

for ssid in "${SSIDS[@]}"; do
  echo
  echo "== Connecting to $ssid (up to 60s) =="
  out=""
  if ! out="$(connect_ssid "$ssid" 2>&1)"; then
    echo "$out" | sed 's/^/    /'
    echo "  -> failed; if it is a password error, retry with X5_AP_PASSWORD=xxxx"
    continue
  fi
  echo "  connected"

  ready=0
  code=""
  for _ in $(seq 1 30); do
    code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 2 "http://192.168.42.1/osc/info" || true)
    if [[ "$code" == "200" ]]; then ready=1; break; fi
    sleep 1
  done
  if [[ $ready -ne 1 ]]; then
    echo "  OSC not ready (last HTTP $code); skipping"
    continue
  fi

  outdir="$DEST/${ssid// /_}"
  mkdir -p "$outdir"
  echo "-- downloading the newest $COUNT video(s) into $outdir --"
  "$PY" - "$COUNT" "$outdir" << 'PYEOF'
import json
import os
import sys
import urllib.request

count = int(sys.argv[1])
dest = sys.argv[2]
base = "http://192.168.42.1"
# Direct connection: never route camera-network requests through an HTTP proxy.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def post(name, params):
    req = urllib.request.Request(
        base + "/osc/commands/execute",
        data=json.dumps({"name": name, "parameters": params}).encode(),
        headers={"Content-Type": "application/json;charset=utf-8"},
    )
    with opener.open(req, timeout=20) as resp:
        return json.loads(resp.read().decode())

resp = post("camera.listFiles", {
    "fileType": "video",
    "entryCount": count,
    "maxThumbSize": 0,
    "_detail": True,
})
entries = (resp.get("results") or {}).get("entries") or []
if not entries:
    print("  OSC returned no file list:", json.dumps(resp)[:300])
    sys.exit(2)

for entry in entries[:count]:
    url = entry.get("fileUrl") or entry.get("_localFileUrl") or ""
    name = entry.get("name") or os.path.basename(url)
    if url.startswith("/"):
        url = base + url
    out = os.path.join(dest, name)
    print(f"  downloading {name} ({entry.get('size', '?')} bytes)")
    with opener.open(url, timeout=300) as src, open(out, "wb") as dst:
        total = 0
        while True:
            chunk = src.read(1 << 20)
            if not chunk:
                break
            dst.write(chunk)
            total += len(chunk)
    print(f"    -> {out} ({total} bytes)")
PYEOF
done

kill "$KEEPALIVE_PID" 2>/dev/null || true

echo
echo "Done. Files are in: $DEST"
echo "Note: .insv requires Insta360 Studio for playback/export."
