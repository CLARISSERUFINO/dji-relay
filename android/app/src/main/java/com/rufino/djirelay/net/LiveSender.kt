package com.rufino.djirelay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.rufino.djirelay.BuildConfig
import com.rufino.djirelay.rtp.RtpPacketizer
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.telemetry.TelemetryCollector
import com.rufino.djirelay.sdk.SdkController.TAG
import com.rufino.djirelay.stream.FrameQueue
import com.rufino.djirelay.stream.StreamReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Live stream to the PC (spec 7.5, 7.6, RF2): RTP/UDP unicast to `PC_IP:5600`, with no
 * decoding or re-encoding. Switched on and off by the user ("Live streaming"); off when
 * the app starts.
 *
 * - A stream consumer with its own [FrameQueue] (spec 7.3): the callback never waits
 *   for it, and the phone recording and the pilot view are independent of it.
 * - The socket is bound to the Wi-Fi network with Network.bindSocket (never
 *   bindProcessToNetwork) and marked DSCP AF41 (traffic class 0x88). A low-latency
 *   Wi-Fi lock is held while streaming.
 * - If the network goes away, frames keep arriving from the drone and are counted as
 *   not sent; sending resumes on its own when the Wi-Fi network is back (NFR4).
 * - `segment` increases by one each time the drone stream is attached again after a
 *   detach (reconnection) within the streaming session.
 */
object LiveSender {

    private const val QUEUE_CAPACITY = 30
    private const val DSCP_AF41 = 0x88
    private const val LATENCY_RING = 512

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private lateinit var appContext: Context
    lateinit var settings: LiveSettings
        private set

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var network: Network? = null
    @Volatile private var segment = 0
    @Volatile private var sawDetach = false

    // Messages on UDP 5601 (spec 7.7, 7.8): a socket on the same Wi-Fi network, a thread
    // that sends what MessageBus publishes, and a 10 Hz clock beacon thread.
    @Volatile private var msgSocket: DatagramSocket? = null
    @Volatile private var msgRunning = false
    private val msgQueue = java.util.concurrent.ArrayBlockingQueue<String>(4096)
    private val msgDropped = java.util.concurrent.atomic.AtomicLong(0)
    private val msgSent = java.util.concurrent.atomic.AtomicLong(0)
    private val beaconsSent = java.util.concurrent.atomic.AtomicLong(0)
    private var msgThread: Thread? = null
    private var beaconThread: Thread? = null
    private val busSubscriber = MessageBus.Subscriber { _, json ->
        if (!msgQueue.offer(json)) msgDropped.incrementAndGet()
    }

    private var thread: Thread? = null
    private var queue: FrameQueue? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Call once, from the Application. */
    fun init(context: Context) {
        appContext = context.applicationContext
        settings = LiveSettings(appContext)
        _state.update { it.copy(pcIp = settings.pcIp) }
        StreamReceiver.addEventListener { name, _ ->
            if (!running) return@addEventListener
            when (name) {
                "stream_detached" -> sawDetach = true
                "stream_attached" -> if (sawDetach) {
                    sawDetach = false
                    segment++
                    Log.i(TAG, "live: stream re-attached, segment=$segment")
                    MessageBus.event("reconnect") { it.put("segment", segment) }
                }
            }
        }
    }

    fun setPcIp(ip: String) {
        settings.pcIp = ip
        _state.update { it.copy(pcIp = ip.trim()) }
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val ip = settings.pcIp
        if (!LiveSettings.isValidIpv4(ip)) {
            _state.update { it.copy(error = "invalid PC IP") }
            return false
        }
        val pc = InetAddress.getByName(ip)
        val target = InetSocketAddress(pc, LiveSettings.VIDEO_PORT)
        val msgTarget = InetSocketAddress(pc, LiveSettings.MESSAGE_PORT)

        val rnd = SecureRandom()
        val ssrc = rnd.nextInt()
        val packetizer = RtpPacketizer(ssrc = ssrc, initialSequence = rnd.nextInt(0x10000))
        val q = FrameQueue(QUEUE_CAPACITY)
        queue = q
        segment = 0
        sawDetach = false
        running = true

        acquireWifiLock()
        requestWifiNetwork()

        _state.value = LiveState(
            enabled = true, pcIp = ip, ssrc = "%08x".format(ssrc),
            network = if (socket != null) "wifi" else "waiting for Wi-Fi",
        )
        val session = MessageBus.newPhoneSession()
        msgQueue.clear()
        msgDropped.set(0)
        msgSent.set(0)
        beaconsSent.set(0)
        MessageBus.subscribe(busSubscriber)
        msgRunning = true
        msgThread = Thread({ messageLoop(msgTarget) }, "LiveMessages").also { it.start() }
        TelemetryCollector.snapshot("live streaming started")
        beaconThread = Thread({ beaconLoop(msgTarget) }, "ClockBeacon").also { it.start() }
        val st = StreamReceiver.stats.value
        MessageBus.event("session_start") { o ->
            o.put("ssrc", "%08x".format(ssrc))
            o.put("rtp_timestamp_mode", "pts_ms*90")
            o.put("payload_max", RtpPacketizer.DEFAULT_MAX_PAYLOAD)
            o.put("codec", st.mime ?: JSONObject.NULL)
            o.put("w", st.width).put("h", st.height).put("fps", st.frameRate)
            o.put("app_version", BuildConfig.VERSION_NAME)
            o.put("msdk_version", SdkController.state.value.sdkVersion ?: JSONObject.NULL)
            o.put("video_port", LiveSettings.VIDEO_PORT).put("message_port", LiveSettings.MESSAGE_PORT)
        }

        val t = Thread({ sendLoop(q, packetizer, target) }, "LiveSender")
        thread = t
        t.start()
        StreamReceiver.addConsumer(q)
        Log.i(TAG, "live: phone_session=$session, messages to ${ip}:${LiveSettings.MESSAGE_PORT}")
        Log.i(TAG, "live: started, ${ip}:${LiveSettings.VIDEO_PORT}, ssrc=%08x".format(ssrc))
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        queue?.let { StreamReceiver.removeConsumer(it) }
        running = false
        thread?.join(2000)
        thread = null
        queue = null
        val s = _state.value
        MessageBus.event("session_stop") { o ->
            o.put("frames_sent", s.framesSent).put("drops", s.drops)
            o.put("messages_sent", msgSent.get()).put("messages_dropped", msgDropped.get())
            o.put("beacons_sent", beaconsSent.get())
        }
        MessageBus.unsubscribe(busSubscriber)
        msgRunning = false
        msgThread?.join(2000)   // ends once the queue is drained (session_stop included)
        beaconThread?.join(1000)
        msgThread = null
        beaconThread = null
        releaseNetwork()
        releaseWifiLock()
        _state.update { it.copy(enabled = false, network = "off") }
        Log.i(TAG, "live: stopped")
    }

    private fun sendLoop(q: FrameQueue, packetizer: RtpPacketizer, target: InetSocketAddress) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val dgram = DatagramPacket(packetizer.packet, 0, target)
        val latencyNs = LongArray(LATENCY_RING)
        var latencyCount = 0
        var latencyMaxWindow = 0L

        var framesSent = 0L
        var framesNoNetwork = 0L
        var sendErrors = 0L
        var bytesSent = 0L
        var lastTickMs = SystemClock.elapsedRealtime()
        var lastPackets = 0L
        var lastBytes = 0L

        val emit = RtpPacketizer.Emit { buf, len ->
            val s = socket ?: return@Emit
            dgram.setData(buf, 0, len)
            try {
                s.send(dgram)
                bytesSent += len
            } catch (e: IOException) {
                sendErrors++
            }
        }

        try {
            while (running || q.size() > 0) {
                val frame = q.poll(100)
                if (frame != null) {
                    try {
                        if (socket == null) {
                            framesNoNetwork++
                        } else {
                            packetizer.packetize(frame, segment, emit)
                            val tTx = SystemClock.elapsedRealtimeNanos()
                            framesSent++
                            publishFrame(frame, packetizer, tTx)
                            // NFR1: callback -> sendto of the frame's last packet.
                            val d = SystemClock.elapsedRealtimeNanos() - frame.tRxNs
                            latencyNs[latencyCount % LATENCY_RING] = d
                            latencyCount++
                            if (d > latencyMaxWindow) latencyMaxWindow = d
                        }
                    } finally {
                        frame.release()
                    }
                }

                val dropped = q.takeDropped()
                if (dropped > 0) MessageBus.event("drop") { it.put("count", dropped).put("consumer", "live") }

                val now = SystemClock.elapsedRealtime()
                if (now - lastTickMs >= 1000) {
                    val dt = (now - lastTickMs) / 1000.0
                    lastTickMs = now
                    val n = minOf(latencyCount, LATENCY_RING)
                    val p95 = if (n == 0) 0L else latencyNs.copyOf(n).also { it.sort() }[(n * 95 / 100).coerceAtMost(n - 1)]
                    val packets = packetizer.packetsBuilt
                    _state.update {
                        it.copy(
                            network = if (socket != null) "wifi" else "waiting for Wi-Fi",
                            segment = segment,
                            framesSent = framesSent,
                            framesNoNetwork = framesNoNetwork,
                            drops = q.droppedTotal.get(),
                            sendErrors = sendErrors,
                            packetsPerSecond = ((packets - lastPackets) / dt).toInt(),
                            megabitsPerSecond = (bytesSent - lastBytes) * 8 / dt / 1e6,
                            latencyP95Us = p95 / 1000,
                            latencyMaxUs = latencyMaxWindow / 1000,
                            insertedParameterSets = packetizer.insertedParameterSets,
                            noStartCode = packetizer.noStartCode,
                            timestampReanchors = packetizer.timestampReanchors,
                            error = null,
                        )
                    }
                    lastPackets = packets
                    lastBytes = bytesSent
                    latencyMaxWindow = 0
                    latencyCount = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "live: sender failed", e)
            _state.update { it.copy(error = "sender failed: ${e.message}") }
        } finally {
            q.clear()
        }
    }

    // ---- Messages (UDP 5601)

    private var lastFormat: String? = null
    private var lastMime: String? = null

    /** `frame` message (spec 7.7), one per access unit sent. */
    private fun publishFrame(f: com.rufino.djirelay.stream.Frame, p: RtpPacketizer, tTxNs: Long) {
        val mime = f.mime?.name
        if (mime != lastMime && lastMime != null) {
            val from = lastMime
            MessageBus.event("mime_changed") { it.put("from", from).put("to", mime ?: JSONObject.NULL) }
        }
        lastMime = mime
        val format = "${f.width}x${f.height}@${f.frameRate}/$mime"
        val withFormat = f.keyFrame || format != lastFormat
        lastFormat = format
        MessageBus.publish("frame") { o ->
            o.put("frame_idx", f.frameIdx)
            o.put("segment", segment)
            o.put("rtp_ts", p.lastTimestamp and 0xFFFFFFFFL)
            o.put("rtp_seq_first", p.lastSeqFirst)
            o.put("rtp_seq_last", p.lastSeqLast)
            o.put("pts_ms", f.ptsMs)
            o.put("t_rx_ns", f.tRxNs)
            o.put("t_tx_ns", tTxNs)
            o.put("key", f.keyFrame)
            o.put("size", f.length)
            o.put("nal_types", JSONArray().apply { for (i in 0 until p.lastNalCount) put(p.lastNalTypes[i]) })
            o.put("sei", JSONObject().put("count", f.seiTypes.size)
                .put("types", JSONArray().apply { f.seiTypes.forEach { put(it) } }))
            if (withFormat) o.put("w", f.width).put("h", f.height).put("fps", f.frameRate).put("mime", mime ?: JSONObject.NULL)
        }
    }

    /** Sends what MessageBus publishes; ends when stopped and the queue is empty. */
    private fun messageLoop(target: InetSocketAddress) {
        val dgram = DatagramPacket(ByteArray(0), 0, target)
        while (msgRunning || msgQueue.isNotEmpty()) {
            val json = msgQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            val s = msgSocket ?: continue  // no Wi-Fi: the message is lost (counted by the PC as a seq gap)
            val bytes = json.toByteArray(Charsets.UTF_8)
            dgram.setData(bytes, 0, bytes.size)
            try {
                s.send(dgram)
                msgSent.incrementAndGet()
            } catch (e: IOException) {
                msgDropped.incrementAndGet()
            }
        }
    }

    /**
     * One-way clock beacons (spec 7.8): `{type:"clock", seq, t_ph_ns, phone_session}` at
     * 10 Hz, with t_ph_ns read immediately before sendto.
     */
    private fun beaconLoop(target: InetSocketAddress) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val dgram = DatagramPacket(ByteArray(0), 0, target)
        val session = MessageBus.phoneSession
        val periodNs = 100_000_000L
        var next = SystemClock.elapsedRealtimeNanos()
        var seq = 0L
        while (running) {
            next += periodNs
            val sleepMs = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000
            if (sleepMs > 0) Thread.sleep(sleepMs)
            val s = msgSocket ?: continue
            val prefix = "{\"type\":\"clock\",\"seq\":$seq,\"phone_session\":\"$session\",\"t_ph_ns\":"
            val t = SystemClock.elapsedRealtimeNanos()
            val bytes = (prefix + t + "}").toByteArray(Charsets.US_ASCII)
            dgram.setData(bytes, 0, bytes.size)
            try {
                s.send(dgram)
                seq++
                beaconsSent.incrementAndGet()
            } catch (e: IOException) {
                // counted as missing seq on the PC side
            }
        }
    }

    // ---- Wi-Fi network and socket

    private fun requestWifiNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                openSocket(n)
            }

            override fun onLost(n: Network) {
                if (n == network) {
                    Log.w(TAG, "live: Wi-Fi network lost")
                    closeSocket()
                }
            }
        }
        networkCallback = cb
        cm.requestNetwork(request, cb)
    }

    private fun openSocket(n: Network) {
        try {
            closeSocket()
            val s = DatagramSocket()
            n.bindSocket(s)
            s.trafficClass = DSCP_AF41
            s.sendBufferSize = 1 shl 20
            val m = DatagramSocket()
            n.bindSocket(m)
            network = n
            socket = s
            msgSocket = m
            Log.i(TAG, "live: socket bound to Wi-Fi network $n, traffic class 0x%02x".format(s.trafficClass))
        } catch (e: IOException) {
            Log.e(TAG, "live: cannot open socket on $n", e)
            _state.update { it.copy(error = "socket: ${e.message}") }
        }
    }

    private fun closeSocket() {
        socket?.close()
        socket = null
        msgSocket?.close()
        msgSocket = null
        network = null
    }

    private fun releaseNetwork() {
        networkCallback?.let {
            runCatching { appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        closeSocket()
    }

    // ---- Wi-Fi lock (spec 7.6; brought forward to F3 for latency)

    private fun acquireWifiLock() {
        val wm = appContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "DjiRelay:live").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "live: Wi-Fi lock acquired (mode $mode)")
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }
}

/** Live stream state for the UI. */
data class LiveState(
    val enabled: Boolean = false,
    val pcIp: String = "",
    val ssrc: String? = null,
    val network: String = "off",
    val segment: Int = 0,
    val framesSent: Long = 0,
    val framesNoNetwork: Long = 0,
    val drops: Long = 0,
    val sendErrors: Long = 0,
    val packetsPerSecond: Int = 0,
    val megabitsPerSecond: Double = 0.0,
    /** NFR1: callback -> sendto of the last packet, p95 and max over the last second. */
    val latencyP95Us: Long = 0,
    val latencyMaxUs: Long = 0,
    val insertedParameterSets: Long = 0,
    val noStartCode: Long = 0,
    val timestampReanchors: Long = 0,
    val error: String? = null,
)
