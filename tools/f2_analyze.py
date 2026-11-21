#!/usr/bin/env python3
"""Analyse a phone recording (spec 7.10) for tests E1 and E2.

Usage:
    python3 tools/f2_analyze.py <session folder>

The session folder is the one copied from the phone with
    adb pull /sdcard/Android/data/com.rufino.djirelay/files/sessions/<name>
and holds stream.h264 + messages.jsonl.

E1 (stream format): Annex B or AVCC, NAL layout per callback, whether one callback is
one access unit, SPS/PPS before every IDR, codec, SEI entries, and whether FFmpeg
decodes the file (V2, V4, V10, V14).
E2 (PTS reference): what pts_ms is measured from, its step, and its relation to the
phone receive time t_rx_ns (V3).

Only the Python standard library is used, plus ffprobe/ffmpeg if they are installed.
"""

import argparse
import base64
import collections
import json
import mmap
import os
import shutil
import statistics
import subprocess
import sys

H264_NAMES = {1: "non-IDR", 5: "IDR", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD", 12: "filler"}
H265_NAMES = {0: "TRAIL_N", 1: "TRAIL_R", 19: "IDR_W_RADL", 20: "IDR_N_LP", 21: "CRA",
              32: "VPS", 33: "SPS", 34: "PPS", 35: "AUD", 39: "SEI_PREFIX", 40: "SEI_SUFFIX"}


def load_messages(path):
    frames, events = [], []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                m = json.loads(line)
            except json.JSONDecodeError:
                print(f"warning: messages.jsonl line {n} is not valid JSON (truncated file?)")
                continue
            (frames if m.get("type") == "frame" else events).append(m)
    return frames, events


def split_annexb(buf):
    """NAL units of an Annex B buffer, as (start, end) of the payload after the start code."""
    starts = []
    i, n = 0, len(buf)
    while i + 3 <= n:
        if buf[i] == 0 and buf[i + 1] == 0:
            if buf[i + 2] == 1:
                starts.append((i, i + 3))
                i += 3
                continue
            if i + 4 <= n and buf[i + 2] == 0 and buf[i + 3] == 1:
                starts.append((i, i + 4))
                i += 4
                continue
        i += 1
    nals = []
    for k, (_, payload) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else n
        nals.append((payload, end))
    return nals


def split_avcc(buf, length_size=4):
    """NAL units of a length-prefixed buffer, or None if the lengths do not add up."""
    nals, i, n = [], 0, len(buf)
    while i < n:
        if i + length_size > n:
            return None
        size = int.from_bytes(buf[i:i + length_size], "big")
        i += length_size
        if size == 0 or i + size > n:
            return None
        nals.append((i, i + size))
        i += size
    return nals


def starts_with_start_code(b):
    return b[:3] == b"\x00\x00\x01" or b[:4] == b"\x00\x00\x00\x01"


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    k = min(len(s) - 1, max(0, int(round(p / 100 * (len(s) - 1)))))
    return s[k]


def linfit(xs, ys):
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return 0.0, my
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    return slope, my - slope * mx


def analyse(folder):
    stream_path = os.path.join(folder, "stream.h264")
    msg_path = os.path.join(folder, "messages.jsonl")
    for p in (stream_path, msg_path):
        if not os.path.isfile(p):
            sys.exit(f"missing {p}")

    frames, events = load_messages(msg_path)
    if not frames:
        sys.exit("no frame messages")
    size = os.path.getsize(stream_path)

    print(f"# Phone recording analysis: {os.path.basename(os.path.normpath(folder))}\n")

    # ---- Session and events
    ev_count = collections.Counter(e.get("event") for e in events)
    start = next((e for e in events if e.get("event") == "session_start"), None)
    stop = next((e for e in events if e.get("event") == "session_stop"), None)
    print("## Session")
    if start:
        for k in ("app_version", "msdk_version", "product", "aircraft_firmware", "rc_firmware", "camera_index"):
            print(f"- {k}: {start.get(k)}")
    if stop:
        print(f"- stop reason: {stop.get('reason')}, frames {stop.get('frames')}, bytes {stop.get('bytes')}, drops {stop.get('drops')}")
    print(f"- events: {dict(ev_count)}")
    drops = sum(int(e.get("count", 0)) for e in events if e.get("event") == "drop")
    idx = [f["frame_idx"] for f in frames]
    gaps = sum(b - a - 1 for a, b in zip(idx, idx[1:]) if b - a > 1)
    print(f"- frames: {len(frames)}, frame_idx {idx[0]}..{idx[-1]}, missing indices {gaps}, recording drops {drops}")
    dur_s = (frames[-1]["t_rx_ns"] - frames[0]["t_rx_ns"]) / 1e9
    print(f"- duration (t_rx): {dur_s:.2f} s, {size / 1e6:.1f} MB, {size * 8 / max(dur_s, 1e-9) / 1e6:.2f} Mbit/s\n")

    # ---- File consistency
    print("## File consistency")
    expected = 0
    bad_offsets = 0
    for f in frames:
        if f["file_offset"] != expected:
            bad_offsets += 1
        expected = f["file_offset"] + f["size"]
    print(f"- sum of sizes {expected} vs file size {size}: {'OK' if expected == size else 'MISMATCH'}")
    print(f"- non-contiguous file_offset: {bad_offsets}\n")

    # ---- Format (V2, V10)
    mimes = collections.Counter(f.get("mime") for f in frames if f.get("mime"))
    fmt = collections.Counter((f.get("w"), f.get("h"), f.get("fps")) for f in frames if f.get("w") is not None)
    codec = (mimes.most_common(1)[0][0] if mimes else "H264") or "H264"
    h265 = codec.upper() == "H265"
    print("## Codec and format (V10)")
    print(f"- StreamInfo mime: {dict(mimes)}")
    print(f"- StreamInfo w×h@fps: {dict(fmt)}\n")

    with open(stream_path, "rb") as fh:
        buf = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ) if size else b""

        def nal_type(b0, b1):
            return (b0 >> 1) & 0x3F if h265 else b0 & 0x1F

        vcl = (lambda t: t <= 31) if h265 else (lambda t: t in (1, 5))
        idr = (lambda t: t in (19, 20, 21)) if h265 else (lambda t: t == 5)
        params = (lambda t: t in (32, 33, 34)) if h265 else (lambda t: t in (7, 8))
        names = H265_NAMES if h265 else H264_NAMES
        hdr = 2 if h265 else 1

        layout = collections.Counter()
        per_cb_types = collections.Counter()
        au_starts_per_cb = collections.Counter()
        idr_cbs = idr_with_params = key_flag_mismatch = 0
        seen_params = False
        idr_without_any_params = 0
        sei_nals = 0
        first_layout = None

        for f in frames:
            chunk = buf[f["file_offset"]:f["file_offset"] + f["size"]]
            if starts_with_start_code(chunk):
                kind, nals = "annexb", split_annexb(chunk)
            else:
                nals = split_avcc(chunk)
                kind = "avcc" if nals is not None else "unknown"
                nals = nals or []
            layout[kind] += 1
            if first_layout is None:
                first_layout = (kind, chunk[:16].hex(" "))

            types = []
            au_starts = 0
            for s, e in nals:
                if s + hdr > e:
                    continue
                t = nal_type(chunk[s], chunk[s + 1] if s + 1 < e else 0)
                types.append(t)
                if vcl(t) and s + hdr < e:
                    # H.264: first_mb_in_slice == 0  <=> first ue(v) bit is 1.
                    # H.265: first_slice_segment_in_pic_flag is the first bit after the header.
                    if chunk[s + hdr] & 0x80:
                        au_starts += 1
            per_cb_types[" ".join(names.get(t, str(t)) for t in types)] += 1
            au_starts_per_cb[au_starts] += 1
            sei_nals += sum(1 for t in types if t in ((39, 40) if h265 else (6,)))

            has_idr = any(idr(t) for t in types)
            has_params = any(params(t) for t in types)
            if has_params:
                seen_params = True
            if has_idr:
                idr_cbs += 1
                if has_params:
                    idr_with_params += 1
                if not seen_params:
                    idr_without_any_params += 1
            if bool(f.get("key")) != has_idr:
                key_flag_mismatch += 1

        print("## Bitstream layout (V2)")
        print(f"- per callback: {dict(layout)}")
        if first_layout:
            print(f"- first callback: {first_layout[0]}, first bytes {first_layout[1]}")
        print(f"- VCL access-unit starts per callback: {dict(sorted(au_starts_per_cb.items()))}"
              f"  (all 1 => one callback = one access unit)")
        print("- NAL sequences per callback (most common):")
        for seq, n in per_cb_types.most_common(8):
            print(f"  - {n:6d} × [{seq}]")
        print()

        print("## SPS/PPS and keyframes (V4)")
        print(f"- callbacks with an IDR: {idr_cbs}; with SPS/PPS in the same callback: {idr_with_params}")
        print(f"- IDR before any SPS/PPS was seen: {idr_without_any_params}")
        print(f"- StreamInfo.isKeyFrame disagrees with 'has IDR': {key_flag_mismatch}")
        keys = [f for f in frames if f.get("key")]
        if len(keys) > 1:
            gop = [(b["t_rx_ns"] - a["t_rx_ns"]) / 1e9 for a, b in zip(keys, keys[1:])]
            print(f"- keyframe interval: median {statistics.median(gop):.2f} s (min {min(gop):.2f}, max {max(gop):.2f})")
        print()

        if size:
            buf.close()

    # ---- SEI (V14)
    print("## SEI (V14)")
    sei_frames = [f for f in frames if "sei" in f]
    print(f"- SEI NAL units in the bitstream: {sei_nals}")
    print(f"- callbacks with StreamInfo.getSeiList entries: {len(sei_frames)} of {len(frames)}")
    if sei_frames:
        types = collections.Counter(t for f in sei_frames for t in f["sei"]["types"])
        lens = collections.Counter(len(base64.b64decode(d)) for f in sei_frames for d in f["sei"]["data"])
        print(f"- seiType counts: {dict(types)}")
        print(f"- payload sizes (bytes: count): {dict(sorted(lens.items()))}")
        print("- first payloads (hex):")
        for f in sei_frames[:5]:
            for t, d in zip(f["sei"]["types"], f["sei"]["data"]):
                print(f"  - frame {f['frame_idx']} type {t}: {base64.b64decode(d)[:48].hex(' ')}")
    print()

    # ---- E2: pts_ms (V3)
    print("## E2: pts_ms reference (V3)")
    pts = [f["pts_ms"] for f in frames]
    rx_ms = [f["t_rx_ns"] / 1e6 for f in frames]
    utc = [f["t_ph_utc_ms"] for f in frames]
    dpts = [b - a for a, b in zip(pts, pts[1:])]
    nonmono = sum(1 for d in dpts if d <= 0)
    print(f"- pts_ms first {pts[0]}, last {pts[-1]}, span {(pts[-1] - pts[0]) / 1000:.2f} s vs t_rx span {dur_s:.2f} s")
    if dpts:
        print(f"- pts step: median {statistics.median(dpts)} ms, min {min(dpts)}, max {max(dpts)}, non-increasing {nonmono}")
        print(f"- pts step histogram: {dict(collections.Counter(dpts).most_common(6))}")
    off_rt = rx_ms[0] - pts[0]
    off_utc = utc[0] - pts[0]
    print(f"- t_rx(elapsedRealtime) − pts at start: {off_rt:.1f} ms; t_ph_utc − pts: {off_utc:.1f} ms")
    if abs(off_rt) < 2000:
        guess = "close to the phone's elapsedRealtime clock"
    elif abs(off_utc) < 2000:
        guess = "close to UTC (epoch ms)"
    elif pts[0] < 10 * 60 * 1000:
        guess = "small at the start: likely relative to the stream or the drone's boot"
    else:
        guess = "an unrelated clock (probably the drone's)"
    print(f"- reading: pts_ms is {guess}")
    if len(pts) > 2 and pts[-1] != pts[0]:
        slope, _ = linfit(pts, rx_ms)
        print(f"- fit t_rx = a·pts + b: a = {slope:.6f} ({(slope - 1) * 1e6:+.0f} ppm)")
        d = [r - p for r, p in zip(rx_ms, pts)]
        floor = min(d)
        print(f"- arrival delay over the lower envelope (t_rx − pts − min): "
              f"p50 {pct([x - floor for x in d], 50):.1f} ms, p95 {pct([x - floor for x in d], 95):.1f} ms, "
              f"max {max(d) - floor:.1f} ms (includes drift)")
    print()

    # ---- NFR2: callback time
    cb = [f.get("cb_ns", 0) / 1000 for f in frames]
    print("## Callback time (NFR2, copy + enqueue)")
    print(f"- p50 {pct(cb, 50):.0f} µs, p95 {pct(cb, 95):.0f} µs, max {max(cb):.0f} µs\n")

    # ---- FFmpeg
    print("## FFmpeg")
    if shutil.which("ffprobe") is None:
        print("- ffprobe not installed\n")
        return
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-count_frames", "-f", "hevc" if h265 else "h264",
         "-show_entries", "stream=codec_name,profile,width,height,pix_fmt,nb_read_frames",
         "-of", "json", stream_path],
        capture_output=True, text=True)
    try:
        info = json.loads(probe.stdout).get("streams", [{}])[0]
    except json.JSONDecodeError:
        info = {}
    print(f"- ffprobe: {info}")
    if probe.stderr.strip():
        print(f"- ffprobe messages: {len(probe.stderr.strip().splitlines())} lines; first: {probe.stderr.strip().splitlines()[0]}")
    dec = subprocess.run(["ffmpeg", "-v", "error", "-f", "hevc" if h265 else "h264", "-i", stream_path, "-f", "null", "-"],
                         capture_output=True, text=True)
    errs = [l for l in dec.stderr.splitlines() if l.strip()]
    print(f"- ffmpeg decode to null: exit {dec.returncode}, {len(errs)} error lines")
    for l in errs[:5]:
        print(f"  - {l}")
    print()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", help="session folder with stream.h264 and messages.jsonl")
    analyse(ap.parse_args().folder)


if __name__ == "__main__":
    main()
