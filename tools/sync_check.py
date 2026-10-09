#!/usr/bin/env python3
"""Measure the real start offset between two clips and report it in ms/frames.

Typical use: record the same sharp event (a clap, a door slam, an LED flash
with a beep) in the field of view / microphone range of both Insta360 X5s,
then run:

    python sync_check.py camA.mp4 camB.mp4 --fps 30

The tool extracts the audio with ffmpeg, finds the first sharp transient
(the clap) in both clips and prints the offset. Positive offset means B
started later than A (B's clap is a later position in its own file, i.e.
B's recording began earlier than A's relative to the event).

Requires: numpy and an ffmpeg binary (system ffmpeg, or `pip install
imageio-ffmpeg` which bundles one).
"""

import argparse
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np


def find_ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        pass
    sys.exit("ffmpeg not found: install it (apt install ffmpeg) or `pip install imageio-ffmpeg`")


def extract_wav(ffmpeg: str, media: Path, out: Path, rate: int) -> None:
    cmd = [
        ffmpeg, "-y", "-loglevel", "error",
        "-i", str(media),
        "-vn", "-ac", "1", "-ar", str(rate), "-acodec", "pcm_s16le",
        str(out),
    ]
    subprocess.run(cmd, check=True)


def load_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        rate = w.getframerate()
        frames = w.readframes(w.getnframes())
    data = np.frombuffer(frames, dtype=np.int16).astype(np.float64)
    return data, rate


def transient_onset(sig: np.ndarray, rate: int, search_s: float) -> int:
    """Return the sample index of the first sharp transient (e.g. a clap).

    Uses a short-window RMS envelope and triggers on the first window that
    rises well above the noise floor. This is robust for a deliberate single
    loud event at the start of the clip.
    """
    n_search = min(len(sig), int(search_s * rate))
    seg = np.abs(sig[:n_search])

    win = max(1, int(0.005 * rate))  # 5 ms envelope
    kernel = np.ones(win) / win
    env = np.convolve(seg, kernel, mode="same")

    peak = env.max()
    if peak < 1e-6:
        sys.exit("no audible event found (silent clip?)")

    # Trigger at 30% of the peak envelope, but stay above 8x the noise floor.
    noise = np.median(env)
    threshold = max(0.3 * peak, 8 * noise, 50.0)
    above = np.nonzero(env > threshold)[0]
    if len(above) == 0:
        sys.exit("no audible event found above the noise floor")

    # Refine: walk back from the trigger to where the raw amplitude starts
    # rising (crossing 15% of the local peak).
    trig = int(above[0])
    local_peak = seg[trig:trig + win].max() if trig + win <= len(seg) else seg[trig:].max()
    level = max(0.15 * local_peak, 30.0)
    idx = trig
    while idx > 0 and seg[idx - 1] > level:
        idx -= 1
    return idx


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clip_a", type=Path)
    ap.add_argument("clip_b", type=Path)
    ap.add_argument("--fps", type=float, default=30.0, help="frame rate for the frames report (default 30)")
    ap.add_argument("--search", type=float, default=15.0, help="seconds to search for the event (default 15)")
    ap.add_argument("--sample-rate", type=int, default=48000)
    args = ap.parse_args()

    ffmpeg = find_ffmpeg()
    with tempfile.TemporaryDirectory(prefix="sync_check_") as tmp:
        wav_a, wav_b = Path(tmp) / "a.wav", Path(tmp) / "b.wav"
        extract_wav(ffmpeg, args.clip_a, wav_a, args.sample_rate)
        extract_wav(ffmpeg, args.clip_b, wav_b, args.sample_rate)

        sig_a, rate_a = load_wav(wav_a)
        sig_b, rate_b = load_wav(wav_b)
        assert rate_a == rate_b == args.sample_rate

    onset_a = transient_onset(sig_a, rate_a, args.search)
    onset_b = transient_onset(sig_b, rate_b, args.search)

    ms = (onset_b - onset_a) / rate_a * 1000.0
    frames = ms * args.fps / 1000.0

    print(f"event position  A: {onset_a / rate_a:.4f}s   B: {onset_b / rate_b:.4f}s")
    print(f"offset (B-A): {ms:+.2f} ms  =  {frames:+.2f} frames @ {args.fps:g}fps")
    if abs(ms) < 1:
        print("=> both clips are effectively aligned (sub-millisecond)")
    else:
        later = "B" if ms > 0 else "A"
        print(f"=> {later} shows the event later in its own file; trim {abs(frames):.2f} frames "
              f"from the other clip (or shift {'B' if ms > 0 else 'A'} earlier) in post")
    print()
    print("Note: verify once per camera pair. If the offset is stable across takes,")
    print("apply it in post; if it drifts, measure each take.")


if __name__ == "__main__":
    main()
