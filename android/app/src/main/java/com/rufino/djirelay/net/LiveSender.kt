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
import com.rufino.djirelay.rtp.RtpPacketizer
import com.rufino.djirelay.sdk.SdkController.TAG
import com.rufino.djirelay.stream.FrameQueue
import com.rufino.djirelay.stream.StreamReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        val target = InetSocketAddress(InetAddress.getByName(ip), LiveSettings.VIDEO_PORT)

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
        val t = Thread({ sendLoop(q, packetizer, target) }, "LiveSender")
        thread = t
        t.start()
        StreamReceiver.addConsumer(q)
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
                            framesSent++
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
            network = n
            socket = s
            Log.i(TAG, "live: socket bound to Wi-Fi network $n, traffic class 0x%02x".format(s.trafficClass))
        } catch (e: IOException) {
            Log.e(TAG, "live: cannot open socket on $n", e)
            _state.update { it.copy(error = "socket: ${e.message}") }
        }
    }

    private fun closeSocket() {
        socket?.close()
        socket = null
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
