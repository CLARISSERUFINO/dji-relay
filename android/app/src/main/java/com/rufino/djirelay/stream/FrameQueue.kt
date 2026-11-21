package com.rufino.djirelay.stream

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The bounded queue of one stream consumer (spec 7.3 rule 3). Each consumer has its
 * own, so a slow consumer never delays the callback or the other consumers.
 *
 * When the queue is full, this consumer drops frames until the next keyframe (spec
 * 7.3), so what it receives stays decodable. The consumer reads [takeDropped] to emit
 * its `drop` event.
 */
class FrameQueue(capacity: Int) {
    private val queue = ArrayBlockingQueue<Frame>(capacity)

    @Volatile
    private var skippingToKeyframe = false
    private val droppedSinceRead = AtomicLong(0)
    val droppedTotal = AtomicLong(0)

    /** Producer side (callback thread). Never blocks. */
    fun offer(frame: Frame) {
        if (skippingToKeyframe) {
            if (!frame.keyFrame) {
                countDrop()
                return
            }
            skippingToKeyframe = false
        }
        frame.retain()
        if (!queue.offer(frame)) {
            frame.release()
            skippingToKeyframe = true
            countDrop()
        }
    }

    /** Consumer side. Returns null after [timeoutMs] without a frame. */
    fun poll(timeoutMs: Long): Frame? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Frames dropped since the last call (for the consumer's `drop` event). */
    fun takeDropped(): Long = droppedSinceRead.getAndSet(0)

    /** Releases everything still queued (consumer stopping). */
    fun clear() {
        while (true) {
            val f = queue.poll() ?: break
            f.release()
        }
        skippingToKeyframe = false
    }

    fun size(): Int = queue.size

    private fun countDrop() {
        droppedSinceRead.incrementAndGet()
        droppedTotal.incrementAndGet()
    }
}
