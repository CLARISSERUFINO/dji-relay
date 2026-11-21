package com.rufino.djirelay.stream

import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One stream callback: a copy of `data[offset, offset+length)` plus the StreamInfo
 * fields and the phone receive time (spec 7.3, callback rules).
 *
 * The buffer is shared by every consumer that accepted the frame (the phone recording
 * now, the live sender in F3). It goes back to the pool when the last one calls
 * [release], so the callback copies the bytes once, whatever the number of consumers.
 */
class Frame internal constructor(private val pool: FramePool, capacity: Int) {
    var data: ByteArray = ByteArray(capacity)
        private set
    var length: Int = 0
        private set

    /** Index of the callback since the app started. Gaps are frames dropped before any consumer. */
    var frameIdx: Long = 0
        private set
    /** SystemClock.elapsedRealtimeNanos() at the start of the callback. */
    var tRxNs: Long = 0
        private set
    /** Time spent inside the callback, filled in just before it returns. */
    var callbackNs: Long = 0
        internal set
    var ptsMs: Long = 0
        private set
    var keyFrame: Boolean = false
        private set
    var mime: ICameraStreamManager.MimeType? = null
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var frameRate: Int = 0
        private set
    /** SEI entries of this callback (StreamInfo.getSeiList), copied. Empty if none. */
    var seiTypes: IntArray = EMPTY_INTS
        private set
    var seiPayloads: Array<ByteArray> = EMPTY_PAYLOADS
        private set

    private val refs = AtomicInteger(0)

    internal fun fill(
        src: ByteArray, offset: Int, len: Int, idx: Long, rxNs: Long,
        pts: Long, key: Boolean, mimeType: ICameraStreamManager.MimeType?,
        w: Int, h: Int, fps: Int, types: IntArray, payloads: Array<ByteArray>,
    ) {
        if (data.size < len) {
            // Rare: a frame larger than the pre-allocated size. Grow this buffer once.
            data = ByteArray(len + len / 4)
            pool.grown.incrementAndGet()
        }
        System.arraycopy(src, offset, data, 0, len)
        length = len
        frameIdx = idx
        tRxNs = rxNs
        ptsMs = pts
        keyFrame = key
        mime = mimeType
        width = w
        height = h
        frameRate = fps
        seiTypes = types
        seiPayloads = payloads
        callbackNs = 0
    }

    /** Called by the producer once per consumer that accepted the frame, before handing it over. */
    internal fun retain() {
        refs.incrementAndGet()
    }

    /** Called by each consumer when it is done with the frame. */
    fun release() {
        if (refs.decrementAndGet() == 0) pool.recycle(this)
    }

    /** Producer side: drops the producer's own reference (taken in [FramePool.acquire]). */
    internal fun releaseProducer() = release()

    internal fun resetRefs() {
        refs.set(1)
    }

    companion object {
        val EMPTY_INTS = IntArray(0)
        val EMPTY_PAYLOADS = emptyArray<ByteArray>()
    }
}

/**
 * Pre-allocated buffers for the callback (spec 7.3: no large allocations in the
 * callback). [count] buffers of [bufferSize] bytes; a buffer grows if a frame is larger.
 */
class FramePool(count: Int, bufferSize: Int) {
    private val free = ArrayBlockingQueue<Frame>(count)

    /** Callbacks that found no free buffer (every buffer held by a consumer). */
    val exhausted = AtomicLong(0)
    /** Buffers that had to grow for a large frame. */
    val grown = AtomicInteger(0)

    init {
        repeat(count) { free.add(Frame(this, bufferSize)) }
    }

    /** A free buffer with one reference held by the producer, or null if none is free. */
    fun acquire(): Frame? {
        val f = free.poll()
        if (f == null) {
            exhausted.incrementAndGet()
            return null
        }
        f.resetRefs()
        return f
    }

    internal fun recycle(frame: Frame) {
        free.offer(frame)
    }

    fun freeCount(): Int = free.size
}
