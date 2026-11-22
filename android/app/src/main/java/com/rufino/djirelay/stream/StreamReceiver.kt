package com.rufino.djirelay.stream

import android.os.SystemClock
import android.util.Log
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.sdk.SdkController.TAG
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives the encoded live stream with ICameraStreamManager.addReceiveStreamListener
 * (spec 7.3, RF2) and hands each callback to the active consumers.
 *
 * The listener is attached while the aircraft is connected and a camera index is known
 * (from the V11 listener in [SdkController]; never hard-coded), and removed on
 * disconnection. The pilot view (putCameraStreamSurface) is independent of it.
 *
 * Callback rules (spec 7.3, NFR2): stamp elapsedRealtimeNanos, copy into a pooled
 * buffer, offer it to each consumer's own [FrameQueue], return. No I/O, no large
 * allocation, the first callback is never skipped.
 */
object StreamReceiver {

    /** 64 × 384 KiB ≈ 24 MiB. Enough for the queues (about 1 s each) plus frames in flight. */
    private val pool = FramePool(count = 64, bufferSize = 384 * 1024)

    private val consumers = CopyOnWriteArrayList<FrameQueue>()
    private val eventListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    @Volatile
    private var attachedIndex = ComponentIndexType.UNKNOWN
    /** Times the watchdog re-added the listener (see start()). */
    @Volatile private var rearms = 0

    // Written by the callback thread only; read by the 1 s stats ticker.
    private val frameCounter = AtomicLong(0)
    private val byteCounter = AtomicLong(0)
    private val keyCounter = AtomicLong(0)
    @Volatile private var lastMime: ICameraStreamManager.MimeType? = null
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0
    @Volatile private var lastFps = 0
    /** Longest callback in the current 1 s window and since start (ns). */
    private val callbackMaxWindowNs = AtomicLong(0)
    private val callbackMaxNs = AtomicLong(0)
    /** First bytes of the very first callback, logged once (hint for E1: Annex B or AVCC). */
    private val firstBytes = ByteArray(16)
    @Volatile private var firstBytesLen = -1

    /** Starts following the connection and camera index. Call once, from the Application. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            SdkController.state
                .map { if (it.aircraftConnected) it.cameraIndex else ComponentIndexType.UNKNOWN }
                .distinctUntilChanged()
                .collect { index ->
                    detach()
                    if (index != ComponentIndexType.UNKNOWN) attach(index)
                }
        }
        scope.launch {
            var lastFrames = 0L
            var lastBytes = 0L
            var loggedFirst = false
            var idleSeconds = 0
            var lastRearmMs = 0L
            while (true) {
                delay(1000)
                val frames = frameCounter.get()
                val bytes = byteCounter.get()

                // Watchdog: after a drone reconnection (F3) the re-added listener got no
                // callbacks although the pilot view showed video. While attached and
                // without callbacks for 5 s, remove and add the listener again (at most
                // every 5 s). Same index, so no stream_detached/attached events.
                val index = attachedIndex
                idleSeconds = if (index != ComponentIndexType.UNKNOWN && frames == lastFrames) idleSeconds + 1 else 0
                val nowMs = SystemClock.elapsedRealtime()
                if (idleSeconds >= 5 && nowMs - lastRearmMs >= 5000) {
                    lastRearmMs = nowMs
                    rearms++
                    Log.w(TAG, "stream: no callbacks for ${idleSeconds}s on $index, re-adding the listener (#$rearms)")
                    cameraStreamManager.removeReceiveStreamListener(listener)
                    cameraStreamManager.addReceiveStreamListener(index, listener)
                }
                if (!loggedFirst && firstBytesLen >= 0) {
                    loggedFirst = true
                    val hex = firstBytes.take(firstBytesLen).joinToString(" ") { "%02x".format(it) }
                    Log.i(TAG, "stream: first callback, first bytes = $hex")
                }
                _stats.value = _stats.value.copy(
                    attached = attachedIndex != ComponentIndexType.UNKNOWN,
                    cameraIndex = attachedIndex,
                    frames = frames,
                    keyFrames = keyCounter.get(),
                    bytes = bytes,
                    framesPerSecond = (frames - lastFrames).toInt(),
                    bytesPerSecond = bytes - lastBytes,
                    mime = lastMime?.name,
                    width = lastWidth,
                    height = lastHeight,
                    frameRate = lastFps,
                    callbackMaxWindowUs = callbackMaxWindowNs.getAndSet(0) / 1000,
                    callbackMaxUs = callbackMaxNs.get() / 1000,
                    poolExhausted = pool.exhausted.get(),
                    poolGrown = pool.grown.get(),
                )
                lastFrames = frames
                lastBytes = bytes
            }
        }
    }

    /** Adds a consumer queue. Frames are offered to it from the next callback on. */
    fun addConsumer(queue: FrameQueue) {
        consumers.addIfAbsent(queue)
    }

    fun removeConsumer(queue: FrameQueue) {
        consumers.remove(queue)
    }

    /** Receives `stream_attached` / `stream_detached` events (name, detail). */
    fun addEventListener(listener: (String, String) -> Unit) {
        eventListeners.addIfAbsent(listener)
    }

    private fun attach(index: ComponentIndexType) {
        cameraStreamManager.addReceiveStreamListener(index, listener)
        attachedIndex = index
        Log.i(TAG, "stream: addReceiveStreamListener($index)")
        eventListeners.forEach { it("stream_attached", index.name) }
    }

    private fun detach() {
        val index = attachedIndex
        if (index == ComponentIndexType.UNKNOWN) return
        cameraStreamManager.removeReceiveStreamListener(listener)
        attachedIndex = ComponentIndexType.UNKNOWN
        Log.i(TAG, "stream: removeReceiveStreamListener ($index)")
        eventListeners.forEach { it("stream_detached", index.name) }
    }

    private val listener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
        onStream(data, offset, length, info)
    }

    private fun onStream(data: ByteArray, offset: Int, length: Int, info: StreamInfo) {
        val tRx = SystemClock.elapsedRealtimeNanos()
        val idx = frameCounter.getAndIncrement()
        byteCounter.addAndGet(length.toLong())
        val key = info.isKeyFrame
        if (key) keyCounter.incrementAndGet()

        if (firstBytesLen < 0) {
            val n = minOf(firstBytes.size, length)
            System.arraycopy(data, offset, firstBytes, 0, n)
            firstBytesLen = n
        }

        val mime = info.mimeType
        val w = info.width
        val h = info.height
        val fps = info.frameRate
        lastMime = mime
        lastWidth = w
        lastHeight = h
        lastFps = fps

        if (consumers.isEmpty()) {
            noteCallbackTime(tRx)
            return
        }

        val frame = pool.acquire()
        if (frame == null) {
            // Every buffer is held by consumers: this frame is lost for all of them.
            // Each consumer then skips to the next keyframe on its own (FrameQueue).
            noteCallbackTime(tRx)
            return
        }

        // SEI entries: small, copied so the SDK can reuse its objects.
        val sei = info.seiList
        val types: IntArray
        val payloads: Array<ByteArray>
        if (sei.isNullOrEmpty()) {
            types = Frame.EMPTY_INTS
            payloads = Frame.EMPTY_PAYLOADS
        } else {
            types = IntArray(sei.size) { sei[it].seiType }
            payloads = Array(sei.size) { sei[it].mSeiData?.copyOf() ?: ByteArray(0) }
        }

        frame.fill(data, offset, length, idx, tRx, info.presentationTimeMs, key, mime, w, h, fps, types, payloads)
        frame.callbackNs = SystemClock.elapsedRealtimeNanos() - tRx
        for (q in consumers) q.offer(frame)
        frame.releaseProducer()
        noteCallbackTime(tRx)
    }

    private fun noteCallbackTime(tRx: Long) {
        val d = SystemClock.elapsedRealtimeNanos() - tRx
        if (d > callbackMaxWindowNs.get()) callbackMaxWindowNs.set(d)
        if (d > callbackMaxNs.get()) callbackMaxNs.set(d)
    }
}

/** Snapshot of the stream counters, refreshed once per second for the UI. */
data class StreamStats(
    val attached: Boolean = false,
    val cameraIndex: ComponentIndexType = ComponentIndexType.UNKNOWN,
    val frames: Long = 0,
    val keyFrames: Long = 0,
    val bytes: Long = 0,
    val framesPerSecond: Int = 0,
    val bytesPerSecond: Long = 0,
    val mime: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 0,
    /** Longest callback in the last second and since start (NFR2). */
    val callbackMaxWindowUs: Long = 0,
    val callbackMaxUs: Long = 0,
    val poolExhausted: Long = 0,
    val poolGrown: Int = 0,
)
