package com.rufino.djirelay.rtp

/**
 * Splits one stream callback into H.264 NAL units (spec 7.4).
 *
 * E1 (F2) showed that the live stream is Annex B and that one callback is one access
 * unit (`[non-IDR]` or `[SPS PPS IDR]`). The parser still checks that the callback
 * begins with a start code ([startsWithStartCode]) so a change is noticed.
 *
 * Results are written into pre-allocated arrays (no allocation per frame): NAL unit
 * `i` is `data[nalStart[i], nalEnd[i])`, without its start code.
 */
class NalParser(maxNals: Int = 64) {
    val nalStart = IntArray(maxNals)
    val nalEnd = IntArray(maxNals)
    var count = 0
        private set
    var startsWithStartCode = false
        private set
    /** NAL units beyond [maxNals] in the last callback (they are merged into the last one). */
    var overflow = 0
        private set

    fun parse(data: ByteArray, length: Int) {
        count = 0
        overflow = 0
        startsWithStartCode = length >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (length >= 4 && data[2].toInt() == 0 && data[3].toInt() == 1))

        var i = 0
        var payload = -1
        while (i + 3 <= length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val sc = when {
                    data[i + 2].toInt() == 1 -> 3
                    i + 4 <= length && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (sc > 0) {
                    if (payload >= 0) close(payload, i)
                    payload = i + sc
                    i += sc
                    continue
                }
            }
            i++
        }
        if (payload >= 0) close(payload, length)
    }

    private fun close(start: Int, end: Int) {
        if (end <= start) return
        if (count < nalStart.size) {
            nalStart[count] = start
            nalEnd[count] = end
            count++
        } else {
            nalEnd[count - 1] = end
            overflow++
        }
    }

    companion object {
        const val TYPE_NON_IDR = 1
        const val TYPE_IDR = 5
        const val TYPE_SEI = 6
        const val TYPE_SPS = 7
        const val TYPE_PPS = 8
        const val TYPE_AUD = 9

        fun type(data: ByteArray, start: Int): Int = data[start].toInt() and 0x1F
    }
}
