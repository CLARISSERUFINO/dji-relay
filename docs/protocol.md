# Phone → PC protocol

The phone app sends and `drone_logger` (in `ssl-aggregator`) receives. Data flows
from the phone to the PC only: the PC never sends anything to the phone.

| Port | Content | Phase |
|---|---|---|
| UDP 5600 | Live video: RTP, H.264 (this document) | F3 |
| UDP 5601 | JSON messages: `frame`, `telemetry`, `event`, `clock` (spec 7.7, 7.8) | F4 |

Unicast to the PC's IP, entered in the app. One RTP stream per streaming session (from
"Live streaming" on to off).

## Video: RTP over UDP 5600

RFC 3550 header with an H.264 payload (RFC 6184), packetization mode 1 (single NAL unit
and FU-A). Every field is big-endian.

### RTP header (12 bytes)

| Field | Value |
|---|---|
| V | 2 |
| P | 0 |
| X | 1 on the first packet of each access unit (header extension present), 0 otherwise |
| CC | 0 |
| M | 1 on the last packet of each access unit |
| PT | 96 |
| Sequence | 16 bits, random start, +1 per packet, wraps. Continues across segments |
| Timestamp | 32 bits, 90 kHz: **`pts_ms` × 90** (see below), the same for every packet of an access unit |
| SSRC | Random, fixed for the streaming session |

**Timestamp.** `pts_ms` is `StreamInfo.getPresentationTimeMs()`: UTC epoch milliseconds,
monotonic (E2, F2). The timestamp is `pts_ms × 90 + offset`, taken modulo 2³². The offset
starts at 0. If an access unit would not increase the timestamp (for example at the
start of a new segment), the offset is raised so that the timestamp is 3000 ticks (one
frame at 30 fps) after the previous one in a new segment, or 1 tick after it within a
segment. The timestamp is therefore strictly increasing for the whole session, modulo
2³². The raw `pts_ms` is always available in the header extension.

### Payload (RFC 6184)

- The phone receives one access unit per callback, in Annex B (E1, F2). Start codes are
  removed; each NAL unit is sent either:
  - in a **single NAL unit packet**, if it fits in the payload limit; or
  - in **FU-A** fragments (type 28): FU indicator = `F|NRI` of the NAL header + type 28;
    FU header = `S|E|0|type`. The NAL header byte is not repeated in the fragments.
- No STAP-A. AUD units (type 9) are dropped.
- If an IDR arrives without SPS and PPS in the same access unit, the last SPS and PPS
  seen are sent first, with the same timestamp. (E1 found SPS/PPS before every IDR.)
- Payload limit: 1200 bytes (UDP payload at most 12 + 1200 bytes). In the first packet
  of an access unit, the 32-byte extension is counted inside the limit.

### Header extension (first packet of each access unit)

RFC 8285 one-byte form. 32 bytes in total, placed right after the 12-byte header:

| Bytes | Content |
|---|---|
| 0–1 | `0xBE 0xDE` |
| 2–3 | length = 7 (32-bit words of elements that follow) |
| 4 | `0x17`: ID 1, length 8 |
| 5–12 | `t_rx_ns`: phone receive time of the callback, `SystemClock.elapsedRealtimeNanos()`, int64 |
| 13 | `0x27`: ID 2, length 8 |
| 14–21 | `pts_ms`: raw `presentationTimeMs`, int64 |
| 22 | `0x35`: ID 3, length 6 |
| 23–26 | `frame_idx`: callback index since the app started, uint32 (gaps = frames not sent) |
| 27–28 | `segment`: uint16, +1 each time the drone stream is attached again in the session |
| 29–31 | padding, `0x00` |

`rtph264depay` ignores the extension.

### Receiver rules (`drone_logger`)

- An access unit is the set of packets with the same timestamp, ending with the marker
  bit. The extension is read from its first packet.
- Gaps in the sequence number are lost packets. An access unit with a lost packet is
  incomplete.
- A new SSRC means a new streaming session. A new `segment` value means the drone stream
  was re-attached; neither requires restarting `drone_logger`.

## Messages: JSON over UDP 5601 (F4)

Defined in spec 7.7 and 7.8 and fixed here in F4.
