# Insta360 X5 — BLE Direct-Control Protocol (Reverse-Engineered)

*Verified against real X5 hardware (firmware as of 2026-10) by observing and replaying
traffic on the BE80 GATT service. This document exists for interoperability with
hardware we own. Not affiliated with Insta360.*

> 中文摘要：X5 的 BLE 直控不使用旧机型（X3/X4）的 16 字节头格式，而是与相机
> WiFi/TCP 控制协议（端口 6666）相同的"网络帧"：`[u32 总长][包类型][12 字节消息头][protobuf]`。
> 旧格式命令会被相机静默忽略。本文档给出完整帧格式、握手、心跳、已实测命令与真实抓包。

## 1. Transport

| Item | Value |
|---|---|
| Service | `0xBE80` (`0000be80-0000-1000-8000-00805f9b34fb`) |
| Write (host → camera) | `0xBE81` |
| Notify (camera → host) | `0xBE82` |
| Standard services | `0x1801` (GAP), `0x180F` (Battery) also present |
| BLE name | `X5 <serial-suffix>` |

Unlike X3/X4, the X5 does **not** accept the bare 16-byte Header16 framing: such writes
produce no reaction at all (verified: START/STOP commands, 6 s, zero responses, only the
1 Hz keep-alive stream on BE82).

## 2. Frame format

Every BLE notification (and every write, for this protocol) is a length-prefixed
"network frame", identical in spirit to the camera's WiFi TCP control channel:

```
+----------------------------+--------------------------------+
| total_len : uint32 LE      | payload                        |
| (INCLUDES these 4 bytes)   | (type byte + packet body)      |
+----------------------------+--------------------------------+
```

`payload[0]` is the packet type:

| Type | Name | Payload |
|---|---|---|
| `0x04` | MESSAGE | 12-byte message header + protobuf body |
| `0x05` | KEEPALIVE | `05 00 00` |
| `0x06` | SYNC | `06 00 00` + `"syNceNdinS"` (10 ASCII bytes) |
| `0x09` | (unknown) | observed once after sync: `0A 00 00 00 09 00 00 01 02 01` |

### MESSAGE header (12 bytes, after the length prefix)

```
Offset  Size  Field
0       1     packet type = 0x04
1-2     2     reserved (0x00 0x00)
3-4     2     message code (uint16 LE)  — command / response status / notification code
5       1     content type = 0x02 (protobuf)
6-8     3     sequence number (uint24 LE) — echoes the request; notifications use a
              per-session constant (see §6)
9       1     flags: 0x80 in host→camera commands and responses;
              0xF4 observed in camera notifications (semantics unknown)
10-11   2     reserved (0x00 0x00)
12+     N     protobuf body (may be empty)
```

Command codes share the numbering of the WiFi/TCP protocol (`messagecode` enum from
`libOne.so`), e.g. `0x04 START_CAPTURE`, `0x05 STOP_CAPTURE`, `0x03 TAKE_PICTURE`,
`0x0F GET_CURRENT_CAPTURE_STATUS`, `0x21 OPEN_CAMERA_WIFI`, `0x22 CLOSE_CAMERA_WIFI`.

## 3. Handshake

Immediately after connecting/subscribing, the host may send a SYNC frame. The camera
echoes it verbatim:

```
host  → camera:  11 00 00 00 06 00 00 73 79 4E 63 65 4E 64 69 6E 53
                 |len = 17 | type  | "syNceNdinS"
camera → host:   11 00 00 00 06 00 00 73 79 4E 63 65 4E 64 69 6E 53   (identical echo)
```

Note: commands have been observed to work **without** the sync handshake on current
firmware; performing it is recommended for compatibility with stricter firmware.

## 4. Keep-alive

The camera sends a KEEPALIVE frame about once per second:

```
07 00 00 00 05 00 00
```

The host should also send its own KEEPALIVE every ~2 s (as the official app does on the
TCP protocol). A host can stay silent for tens of seconds without being disconnected in
our tests, but periodic keep-alives are the safe behavior.

## 5. Verified commands

| Code | Name | Result |
|---|---|---|
| `0x04` | START_CAPTURE | `200 OK` ACK (seq echoed); camera starts recording |
| `0x05` | STOP_CAPTURE | `200 OK` ACK; **body contains the recorded file path** (protobuf) |
| `0x0F` | GET_CURRENT_CAPTURE_STATUS | `200 OK` + `CameraCaptureStatus` protobuf |
| `0x21` | OPEN_CAMERA_WIFI | No ACK; camera WiFi AP appears (SSID `<name>.OSC`) |
| `0x22` | CLOSE_CAMERA_WIFI | `200 OK` ACK |

Requests rejected with `400` when sent with an empty body (they need a protobuf request):
`0x08 GET_OPTIONS`, `0x37 GET_SYNC_CAPTURE_MODE`, `0x68 GET_BUTTON_PRESS_PARAM`.
Battery / storage / device-info queries and mode/HDR settings are **not mapped yet** for
this protocol (see §8).

### 5.1 START_CAPTURE (full bytes)

```
host  → camera:  10 00 00 00  04 00 00  04 00  02  01 00 00  80  00 00
                 |len = 16  |        | code  | ct | seq     | fl | rs |
camera → host:   10 00 00 00  04 00 00  C8 00  02  01 00 00  80  00 00
                 response code = 0x00C8 (200), seq echoed
```

### 5.2 STOP_CAPTURE ACK carries the file name

```
42 00 00 00
04 00 00 C8 00 02 03 00 00 80 00 00          <- 200 OK, seq = 3
0A 30                                        <- protobuf field 1 (len 0x30)
   0A 2E                                     <-   nested field 1 (len 0x2E)
   "./DCIM/Camera01/VID_20261009_144131_00_054.insv"
```

The file name embeds the camera clock at recording start
(`VID_YYYYMMDD_HHMMSS`), which makes it a free, second-resolution clock reading —
comparing two cameras' names reveals their clock offset without downloading anything.

### 5.3 Capture status query

```
host  → camera:  GET_CURRENT_CAPTURE_STATUS (0x0F)
camera → host:   04 00 00 C8 00 02 03 00 00 80 00 00
                 0A 06 08 00 10 00 50 00
                 ^^^^^ field 1: submessage { field1=0, field2=0, field10=0 }  (idle)
```

While recording, the camera pushes `0x2010` notifications with `08 01 ...`
(field 1 = 1 during recording, 0 after stop in our captures).

## 6. Notifications observed on BE82

| Code | Name (official enum) | Body example | Notes |
|---|---|---|---|
| `0x2010` | CURRENT_CAPTURE_STATUS | `08 01 10 00 38 00` | `08 01` while recording, `08 00` idle |
| `0x2040` | SUPPORT_TAKE_PHOTO_ON_REC | `08 01` / `08 00` | follows recording start/stop |
| `0x2006` | STORAGE_UPDATE | `08 00 10 00` | emitted after STOP (new file written) |
| `0x2017` | CAM_WIFI_START | (empty) | right after connect |
| `0x2062` | (not in enum) | varint fields | emitted after STOP; possibly counters/uptime |

Notifications carry a **per-session constant** in the sequence field
(e.g. `FF 8A 43` → 4426495 in our captures) and flag byte `0xF4`.

## 7. Minimal example (Python, `bleak`)

```python
import asyncio, struct
from bleak import BleakClient

BE81 = "0000be81-0000-1000-8000-00805f9b34fb"

def net_frame(payload: bytes) -> bytes:
    return struct.pack("<I", 4 + len(payload)) + payload

def message(code: int, seq: int, body: bytes = b"") -> bytes:
    return net_frame(bytes([0x04, 0, 0]) + struct.pack("<H", code) + b"\x02"
                     + seq.to_bytes(3, "little") + b"\x80\x00\x00" + body)

async def main(addr: str):
    async with BleakClient(addr) as c:
        # recommended: sync handshake
        await c.write_gatt_char(BE81, net_frame(b"\x06\x00\x00" + b"syNceNdinS"))
        await asyncio.sleep(1)
        await c.write_gatt_char(BE81, message(0x04, 1))   # START_CAPTURE
        await asyncio.sleep(5)
        await c.write_gatt_char(BE81, message(0x05, 2))   # STOP_CAPTURE

asyncio.run(main("AA:BB:CC:DD:EE:FF"))
```

Notes:

- BlueZ negotiates the MTU automatically; large frames should be split into ≤20-byte
  BLE writes (the receiver reassembles using the length prefix), matching the upstream
  implementation.
- Response matching: match on the echoed 24-bit sequence number; status codes are
  `0x00C8` (200 OK), `0x0190` (400), `0x01F4` (500), `0x01F5` (501).

## 8. Open questions / TODO

- Battery, storage, device info: which command/body combination returns them
  (`GET_OPTIONS` needs a protobuf request body; schema unknown).
- Correct command for switching capture mode (legacy `0x0C` is officially `DELETE_FILES`
  — do **not** send it blindly).
- Timecode calibration command (the app's "sync system time" path).
- Semantics of flag byte `0xF4` in notifications and of frame type `0x09`.
- Whether large writes are fragmented by the camera the same way as the TCP protocol.

## 9. Legal

This is an independent interoperability effort based on observing a device we own.
No Insta360 code or firmware was decompiled for this work (command names come from
publicly documented enum values in the community). Not affiliated with Insta360.
