# Patches for `insta360ctl`

These patches add verified Insta360 X5 support and multi-camera control to
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl).

The upstream project has no license file; therefore this repository ships
**patches** instead of a vendored copy of the code.

## Apply

```bash
git clone https://github.com/xaionaro-go/insta360ctl.git
cd insta360ctl
git am /path/to/insta360-x5-toolkit/patches/*.patch
go build -o bin/insta360ctl ./cmd/insta360ctl/
```

If a patch fails to apply (upstream moved), try:

```bash
git am --3way patches/0001-*.patch   # etc.
```

## What each patch does

| Patch | Content |
|---|---|
| `0001` | `multi run` / `multi shell`: single-process, multi-camera synchronized dispatch with a shared barrier; reports per-camera dispatch offsets, RTTs and the dispatch spread |
| `0002` | X5 network-framed BLE protocol (`ProtoFormatNetwork`): framing, sync handshake, keep-alives, reply matching, fragment reassembly; `cmd/x5probe`; unit tests built on real captured vectors |
| `0003` | `stop` prints the recorded file path (X5 ACK body) — proof of recording and a second-level clock-offset readout |
| `0004` | `multi serve`: local web console (Record/Stop/Photo/Marker buttons, timing, clock-diff) |
| `0005` | Demote per-notification D-Bus sniffer logs from INFO to DEBUG |

## Verified behavior

With these patches, two X5s record simultaneously:

```
=== record start ===
  X5 ...  dispatch +0s      rtt 104.7ms   ok
  X5 ...  dispatch +0s      rtt  86.9ms   ok
  -- cameras: 2, failed: 0, dispatch spread: 0s, max rtt: 104.7ms
```

See [../docs/x5-ble-protocol.md](../docs/x5-ble-protocol.md) for the protocol
details and [../docs/sync-verification.md](../docs/sync-verification.md) for
timecode alignment and frame-offset verification.
