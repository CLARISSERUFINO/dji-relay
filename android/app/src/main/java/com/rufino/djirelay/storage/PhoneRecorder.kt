package com.rufino.djirelay.storage

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.rufino.djirelay.BuildConfig
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.sdk.SdkController.TAG
import com.rufino.djirelay.stream.Frame
import com.rufino.djirelay.stream.FrameQueue
import com.rufino.djirelay.stream.StreamReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Phone recording (spec 7.10, RF10): saves the received stream on the phone, from the
 * user's Start until Stop. It never interrupts the live stream: it reads from its own
 * [FrameQueue], written to by a dedicated thread, and if that queue fills up only the
 * recording drops frames (until the next keyframe) and writes a `drop` event.
 *
 * Each recording goes in `<external files dir>/sessions/<UTC yyyyMMdd'T'HHmmss'Z'>/`:
 * - `stream.h264`: the callback bytes, exactly as received, one after the other;
 * - `messages.jsonl`: one `frame` message per callback (with `file_offset` into
 *   `stream.h264`) and the events, one JSON object per line.
 *
 * Copy to the PC with `adb pull /sdcard/Android/data/com.rufino.djirelay/files/sessions/<name>`.
 */
object PhoneRecorder {

    /** Recording refuses to start below this free space, and stops itself below [MIN_FREE_RUNNING]. */
    private const val MIN_FREE_TO_START = 2L * 1024 * 1024 * 1024
    private const val MIN_FREE_RUNNING = 512L * 1024 * 1024

    /** About 1 s at 30 fps (spec 7.3). */
    private const val QUEUE_CAPACITY = 30

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val pendingEvents = ConcurrentLinkedQueue<Pair<String, String>>()

    @Volatile
    private var running = false
    private var writerThread: Thread? = null
    private var queue: FrameQueue? = null
    private var sessionsDir: File? = null

    /** Call once, from the Application. */
    fun init(context: Context) {
        sessionsDir = File(context.getExternalFilesDir(null), "sessions")
        StreamReceiver.addEventListener { name, detail ->
            if (running) pendingEvents.add(name to detail)
        }
        refreshFreeSpace()
    }

    fun refreshFreeSpace() {
        _state.update { it.copy(freeBytes = freeBytes()) }
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val root = sessionsDir ?: return false
        root.mkdirs()
        val free = freeBytes()
        if (free < MIN_FREE_TO_START) {
            val msg = "not enough free space (${free / (1024 * 1024)} MB)"
            Log.w(TAG, "phone recording: $msg")
            _state.update { it.copy(error = msg, freeBytes = free) }
            return false
        }

        val name = utcStamp()
        val dir = File(root, name)
        if (!dir.mkdirs()) {
            val msg = "cannot create ${dir.absolutePath}"
            Log.e(TAG, "phone recording: $msg")
            _state.update { it.copy(error = msg) }
            return false
        }

        val q = FrameQueue(QUEUE_CAPACITY)
        queue = q
        pendingEvents.clear()
        running = true
        _state.update {
            RecorderState(recording = true, sessionName = name, freeBytes = free)
        }
        val t = Thread({ writerLoop(dir, name, q) }, "PhoneRecorder").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
        writerThread = t
        t.start()
        // Added after the thread starts, so the first frame offered already has a reader.
        StreamReceiver.addConsumer(q)
        Log.i(TAG, "phone recording: started, ${dir.absolutePath}")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        queue?.let { StreamReceiver.removeConsumer(it) }
        running = false
        writerThread?.join(5000)
        writerThread = null
        queue = null
        _state.update { it.copy(recording = false) }
        refreshFreeSpace()
        Log.i(TAG, "phone recording: stopped")
    }

    private fun writerLoop(dir: File, name: String, q: FrameQueue) {
        val stream = BufferedOutputStream(FileOutputStream(File(dir, "stream.h264")), 1 shl 20)
        val jsonl = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "messages.jsonl")), Charsets.UTF_8), 1 shl 16)
        val session = Session(name, jsonl)

        var fileOffset = 0L
        var frames = 0L
        var lastMime: String? = null
        var lastW = 0
        var lastH = 0
        var lastFps = 0
        var lastFlushMs = SystemClock.elapsedRealtime()
        var stopReason = "user"

        try {
            val sdk = SdkController.state.value
            session.event(
                "session_start",
                JSONObject()
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("msdk_version", sdk.sdkVersion)
                    .put("product", sdk.productType)
                    .put("aircraft_firmware", sdk.aircraftFirmware)
                    .put("rc_firmware", sdk.rcFirmware)
                    .put("camera_index", StreamReceiver.stats.value.cameraIndex.name)
                    .put("file", "stream.h264"),
            )

            while (running || q.size() > 0) {
                drainEvents(session)
                val dropped = q.takeDropped()
                if (dropped > 0) {
                    session.event("drop", JSONObject().put("count", dropped).put("consumer", "phone_recording"))
                    _state.update { it.copy(drops = it.drops + dropped) }
                }

                val frame = q.poll(100) ?: continue
                try {
                    stream.write(frame.data, 0, frame.length)

                    val mime = frame.mime?.name
                    val changed = mime != lastMime || frame.width != lastW ||
                        frame.height != lastH || frame.frameRate != lastFps
                    if (mime != lastMime && lastMime != null) {
                        session.event("mime_changed", JSONObject().put("from", lastMime).put("to", mime))
                    }
                    session.frame(frame, fileOffset, withFormat = frame.keyFrame || changed)
                    lastMime = mime
                    lastW = frame.width
                    lastH = frame.height
                    lastFps = frame.frameRate
                    fileOffset += frame.length
                    frames++
                } finally {
                    frame.release()
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastFlushMs >= 1000) {
                    lastFlushMs = now
                    stream.flush()
                    jsonl.flush()
                    val free = freeBytes()
                    _state.update { it.copy(frames = frames, bytes = fileOffset, freeBytes = free) }
                    if (free < MIN_FREE_RUNNING) {
                        stopReason = "low_free_space"
                        session.event("error", JSONObject().put("message", "free space below ${MIN_FREE_RUNNING / (1024 * 1024)} MB, recording stopped"))
                        Log.w(TAG, "phone recording: low free space, stopping")
                        StreamReceiver.removeConsumer(q)
                        running = false
                    }
                }
            }
            drainEvents(session)
            val dropped = q.takeDropped()
            if (dropped > 0) {
                session.event("drop", JSONObject().put("count", dropped).put("consumer", "phone_recording"))
            }
            session.event(
                "session_stop",
                JSONObject()
                    .put("reason", stopReason)
                    .put("frames", frames)
                    .put("bytes", fileOffset)
                    .put("drops", q.droppedTotal.get()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "phone recording: write failed", e)
            _state.update { it.copy(error = "write failed: ${e.message}") }
            runCatching { session.event("error", JSONObject().put("message", "write failed: ${e.message}")) }
            StreamReceiver.removeConsumer(q)
            running = false
        } finally {
            q.clear()
            runCatching { stream.close() }
            runCatching { jsonl.close() }
            _state.update { it.copy(recording = false, frames = frames, bytes = fileOffset) }
            Log.i(TAG, "phone recording: $name closed, $frames frames, $fileOffset bytes, reason=$stopReason")
        }
    }

    private fun drainEvents(session: Session) {
        while (true) {
            val (name, detail) = pendingEvents.poll() ?: return
            session.event(name, JSONObject().put("camera_index", detail))
        }
    }

    private fun freeBytes(): Long {
        val dir = sessionsDir ?: return 0
        dir.mkdirs()
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0)
    }

    private fun utcStamp(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * Writes the messages of one recording. Field names follow spec 7.7, so F3 reuses
     * them; the RTP fields are added in F3.
     */
    private class Session(private val phoneSession: String, private val out: BufferedWriter) {
        private val seqByType = HashMap<String, Long>()

        fun frame(f: Frame, fileOffset: Long, withFormat: Boolean) {
            val o = common("frame")
                .put("frame_idx", f.frameIdx)
                .put("t_rx_ns", f.tRxNs)
                .put("pts_ms", f.ptsMs)
                .put("key", f.keyFrame)
                .put("size", f.length)
                .put("file_offset", fileOffset)
                .put("cb_ns", f.callbackNs)
            if (f.seiTypes.isNotEmpty()) {
                val types = JSONArray()
                val data = JSONArray()
                for (i in f.seiTypes.indices) {
                    types.put(f.seiTypes[i])
                    data.put(Base64.encodeToString(f.seiPayloads[i], Base64.NO_WRAP))
                }
                o.put("sei", JSONObject().put("count", f.seiTypes.size).put("types", types).put("data", data))
            }
            if (withFormat) {
                o.put("w", f.width).put("h", f.height).put("fps", f.frameRate).put("mime", f.mime?.name)
            }
            write(o)
        }

        fun event(name: String, fields: JSONObject) {
            val o = common("event").put("event", name)
            val keys = fields.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.put(k, fields.get(k))
            }
            write(o)
        }

        private fun common(type: String): JSONObject {
            val seq = seqByType.getOrDefault(type, 0L)
            seqByType[type] = seq + 1
            return JSONObject()
                .put("type", type)
                .put("seq", seq)
                .put("t_ph_ns", SystemClock.elapsedRealtimeNanos())
                .put("t_ph_utc_ms", System.currentTimeMillis())
                .put("phone_session", phoneSession)
        }

        private fun write(o: JSONObject) {
            out.write(o.toString())
            out.newLine()
        }
    }
}

/** Phone recording state for the UI. */
data class RecorderState(
    val recording: Boolean = false,
    val sessionName: String? = null,
    val frames: Long = 0,
    val bytes: Long = 0,
    val drops: Long = 0,
    val freeBytes: Long = 0,
    val error: String? = null,
)
