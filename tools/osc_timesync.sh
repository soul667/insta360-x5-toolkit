#!/usr/bin/env bash
# Set both cameras' clocks to this computer's local time via the camera OSC API.
#
# This is the software path for aligning the X5 Timecode feature
# ("Sync system time" uses the camera clock as the timecode reference).
# The official and most reliable alternative is calibrating through the
# Insta360 app; see docs/sync-verification.md.
#
#   ./osc_timesync.sh
#   X5_AP_PASSWORD=xxxx ./osc_timesync.sh
#
# WARNING: temporarily switches this computer's WiFi to the camera hotspot.
# The original connection is restored on exit (including Ctrl+C).
set -uo pipefail

CTL="${CTL:-insta360ctl}"
PY="${PY:-python3}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OSC_SETTIME="${OSC_SETTIME:-$HERE/osc_settime.py}"
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

# 1) Open the camera hotspots over BLE (no ACK -> timeout expected).
echo "== Opening camera WiFi over BLE =="
( echo "raw 21"; sleep 900 ) | timeout 920 "$CTL" multi shell \
  --all --scan-window 6s --command-timeout 8s >/dev/null 2>&1 &
KEEPALIVE_PID=$!
sleep 5

# 2) Find the hotspots.
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
  exit 1
fi
echo "Found: ${SSIDS[*]}"

# 3) Connect to each hotspot and set its clock.
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

  echo "-- setting the camera clock --"
  "$PY" "$OSC_SETTIME" --set || echo "  OSC clock set failed (is _dateTime writable on this firmware?)"
done

kill "$KEEPALIVE_PID" 2>/dev/null || true

echo
echo "Done. Re-read both cameras with osc_settime.py to measure the residual offset."
