package com.rufino.djirelay.rtp

import com.rufino.djirelay.stream.Frame

/**
 * H.264 RTP packetizer, RFC 6184 (spec 7.5; the wire format is in docs/protocol.md).
 *
 * - 12-byte header: V=2, PT=96, 16-bit sequence, 90 kHz timestamp, SSRC fixed for the
 *   streaming session, marker bit on the last packet of each access unit.
 * - Single NAL unit packets; FU-A (type 28) for NAL units larger than [maxPayload].
 *   No STAP-A. AUD units are dropped.
 * - RTP timestamp = `pts_ms` × 90 (decided from E2), kept strictly increasing: if a new
 *   segment starts with a `pts_ms` that would not increase it, the timestamp is
 *   re-anchored one frame (3000 ticks) after the previous one.
 * - Header extension (RFC 8285, one-byte form, 0xBEDE) on the first packet of each
 *   access unit: ID 1 `t_rx_ns` (8 bytes), ID 2 `pts_ms` (8), ID 3 `frame_idx` (uint32)
 *   + `segment` (uint16). 32 bytes in total including padding.
 * - SPS/PPS are cached and inserted before an IDR that arrives without them.
 *
 * No allocation per packet: every packet is written into [packet] and handed to
 * [emit] (buffer, length) before the next one is built. Not thread-safe: one sender
 * thread owns it.
 */
class RtpPacketizer(
    private val ssrc: Int,
    initialSequence: Int,
    private val maxPayload: Int = DEFAULT_MAX_PAYLOAD,
) {
    val packet = ByteArray(RTP_HEADER + EXT_BYTES + maxPayload + 2)

    private var sequence = initialSequence and 0xFFFF
    private var lastTs = Long.MIN_VALUE
    private var tsOffset = 0L
    private var lastSegment = -1

    private val parser = NalParser()
    private val sps = ByteArray(512)
    private var spsLen = 0
    private val pps = ByteArray(512)
    private var ppsLen = 0

    /** Counters for the status panel. */
    var packetsBuilt = 0L
        private set
    var frames = 0L
        private set
    var noStartCode = 0L
        private set
    var insertedParameterSets = 0L
        private set
    var timestampReanchors = 0L
        private set

    /** The RTP timestamp of the last access unit (unwrapped, before the 32-bit mask). */
    val lastTimestamp: Long get() = lastTs

    /** Sequence numbers of the first and last packet of the last access unit. */
    var lastSeqFirst = 0
        private set
    var lastSeqLast = 0
        private set
    /** NAL types sent for the last access unit (inserted SPS/PPS included), in order. */
    val lastNalTypes = IntArray(64)
    var lastNalCount = 0
        private set

    fun interface Emit {
        fun send(buf: ByteArray, len: Int)
    }

    /** Packetizes one access unit (one callback) and calls [emit] for each packet. */
    fun packetize(frame: Frame, segment: Int, emit: Emit) {
        val data = frame.data
        parser.parse(data, frame.length)
        if (!parser.startsWithStartCode) noStartCode++
        if (parser.count == 0) return

        val ts = timestamp(frame.ptsMs, segment)
        frames++

        // Cache parameter sets and check whether the IDR needs them inserted.
        var hasSps = false
        var hasPps = false
        var hasIdr = false
        for (i in 0 until parser.count) {
            val s = parser.nalStart[i]
            val len = parser.nalEnd[i] - s
            when (NalParser.type(data, s)) {
                NalParser.TYPE_SPS -> if (len <= sps.size) { System.arraycopy(data, s, sps, 0, len); spsLen = len; hasSps = true }
                NalParser.TYPE_PPS -> if (len <= pps.size) { System.arraycopy(data, s, pps, 0, len); ppsLen = len; hasPps = true }
                NalParser.TYPE_IDR -> hasIdr = true
            }
        }

        // Count the NAL units that will actually be sent, to find the last one (marker).
        val insertSps = hasIdr && !hasSps && spsLen > 0
        val insertPps = hasIdr && !hasPps && ppsLen > 0
        if (insertSps || insertPps) insertedParameterSets++
        var lastIndex = -1
        for (i in 0 until parser.count) {
            if (NalParser.type(data, parser.nalStart[i]) != NalParser.TYPE_AUD) lastIndex = i
        }
        if (lastIndex < 0) return

        lastSeqFirst = sequence
        lastNalCount = 0
        var first = true
        if (insertSps) {
            sendNal(sps, 0, spsLen, ts, last = false, ext = if (first) frame else null, segment, emit); first = false
        }
        if (insertPps) {
            sendNal(pps, 0, ppsLen, ts, last = false, ext = if (first) frame else null, segment, emit); first = false
        }
        for (i in 0..lastIndex) {
            val s = parser.nalStart[i]
            if (NalParser.type(data, s) == NalParser.TYPE_AUD) continue
            sendNal(data, s, parser.nalEnd[i] - s, ts, last = i == lastIndex, ext = if (first) frame else null, segment, emit)
            first = false
        }
        lastSeqLast = (sequence - 1) and 0xFFFF
    }

    /** `pts_ms` × 90, strictly increasing within the streaming session. */
    private fun timestamp(ptsMs: Long, segment: Int): Long {
        var ts = ptsMs * 90 + tsOffset
        if (lastTs != Long.MIN_VALUE && ts <= lastTs) {
            // New segment (or a pts that does not advance): re-anchor one frame later.
            val target = lastTs + if (segment != lastSegment) 3000 else 1
            tsOffset += target - ts
            ts = target
            timestampReanchors++
        }
        lastTs = ts
        lastSegment = segment
        return ts
    }

    private fun sendNal(
        src: ByteArray, start: Int, len: Int, ts: Long, last: Boolean,
        ext: Frame?, segment: Int, emit: Emit,
    ) {
        if (lastNalCount < lastNalTypes.size) lastNalTypes[lastNalCount++] = src[start].toInt() and 0x1F
        var extFrame = ext
        val firstLimit = if (extFrame != null) maxPayload - EXT_BYTES else maxPayload
        if (len <= firstLimit) {
            // Single NAL unit packet.
            val h = header(ts, marker = last, ext = extFrame, segment = segment)
            System.arraycopy(src, start, packet, h, len)
            emit.send(packet, h + len)
            packetsBuilt++
            return
        }

        // FU-A: indicator = F|NRI of the NAL header + type 28; header = S|E|R|type.
        val nalHeader = src[start].toInt() and 0xFF
        val indicator = (nalHeader and 0xE0) or 28
        val type = nalHeader and 0x1F
        var pos = start + 1
        val end = start + len
        var firstFragment = true
        while (pos < end) {
            val limit = (if (extFrame != null) maxPayload - EXT_BYTES else maxPayload) - 2
            val chunk = minOf(limit, end - pos)
            val lastFragment = pos + chunk >= end
            val h = header(ts, marker = last && lastFragment, ext = extFrame, segment = segment)
            packet[h] = indicator.toByte()
            packet[h + 1] = ((if (firstFragment) 0x80 else 0) or (if (lastFragment) 0x40 else 0) or type).toByte()
            System.arraycopy(src, pos, packet, h + 2, chunk)
            emit.send(packet, h + 2 + chunk)
            packetsBuilt++
            pos += chunk
            firstFragment = false
            extFrame = null
        }
    }

    /** Writes the RTP header (and the extension if [ext] is set); returns the payload offset. */
    private fun header(ts: Long, marker: Boolean, ext: Frame?, segment: Int): Int {
        val p = packet
        p[0] = (0x80 or (if (ext != null) 0x10 else 0)).toByte()       // V=2, P=0, X, CC=0
        p[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        p[2] = (sequence ushr 8).toByte()
        p[3] = sequence.toByte()
        sequence = (sequence + 1) and 0xFFFF
        val t = ts and 0xFFFFFFFFL
        p[4] = (t ushr 24).toByte()
        p[5] = (t ushr 16).toByte()
        p[6] = (t ushr 8).toByte()
        p[7] = t.toByte()
        p[8] = (ssrc ushr 24).toByte()
        p[9] = (ssrc ushr 16).toByte()
        p[10] = (ssrc ushr 8).toByte()
        p[11] = ssrc.toByte()
        if (ext == null) return RTP_HEADER

        var o = RTP_HEADER
        p[o++] = 0xBE.toByte(); p[o++] = 0xDE.toByte()
        p[o++] = 0; p[o++] = EXT_WORDS.toByte()                          // length in 32-bit words
        p[o++] = ((1 shl 4) or 7).toByte(); o = putLong(p, o, ext.tRxNs)  // ID 1, 8 bytes
        p[o++] = ((2 shl 4) or 7).toByte(); o = putLong(p, o, ext.ptsMs)  // ID 2, 8 bytes
        p[o++] = ((3 shl 4) or 5).toByte()                                // ID 3, 6 bytes
        val idx = ext.frameIdx
        p[o++] = (idx ushr 24).toByte(); p[o++] = (idx ushr 16).toByte()
        p[o++] = (idx ushr 8).toByte(); p[o++] = idx.toByte()
        p[o++] = (segment ushr 8).toByte(); p[o++] = segment.toByte()
        while (o < RTP_HEADER + EXT_BYTES) p[o++] = 0                     // padding
        return o
    }

    private fun putLong(p: ByteArray, at: Int, v: Long): Int {
        var o = at
        for (shift in 56 downTo 0 step 8) p[o++] = (v ushr shift).toByte()
        return o
    }

    companion object {
        const val RTP_HEADER = 12
        const val DEFAULT_MAX_PAYLOAD = 1200
        const val PAYLOAD_TYPE = 96
        /** 4-byte 0xBEDE header + 25 bytes of elements + 3 padding = 32 bytes. */
        const val EXT_WORDS = 7
        const val EXT_BYTES = 4 + EXT_WORDS * 4
    }
}
