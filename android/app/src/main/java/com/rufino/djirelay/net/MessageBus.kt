package com.rufino.djirelay.net

import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Builds the JSON messages of spec 7.7 (sent on UDP 5601, docs/protocol.md) and hands
 * each one to the subscribers: the live message sender (while live streaming) and the
 * phone recording (while recording, RF10: every message).
 *
 * Common fields: `type`, `seq` (per type, from 0), `t_ph_ns` (elapsedRealtimeNanos
 * when the message is built), `t_ph_utc_ms` (for humans only), `phone_session`.
 * Clock beacons are not built here: their `t_ph_ns` is taken right before sendto (7.8).
 */
object MessageBus {

    fun interface Subscriber {
        fun onMessage(type: String, json: String)
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val seqByType = HashMap<String, Long>()

    /** UTC start of the current live streaming session (or of the app, before any). */
    @Volatile
    var phoneSession: String = utcStamp()
        private set

    fun newPhoneSession(): String {
        phoneSession = utcStamp()
        return phoneSession
    }

    fun subscribe(s: Subscriber) {
        subscribers.addIfAbsent(s)
    }

    fun unsubscribe(s: Subscriber) {
        subscribers.remove(s)
    }

    /** Builds a message with the common fields; [fill] adds the specific ones. */
    fun publish(type: String, fill: (JSONObject) -> Unit) {
        if (subscribers.isEmpty()) return
        val o = JSONObject()
        o.put("type", type)
        o.put("seq", nextSeq(type))
        o.put("t_ph_ns", SystemClock.elapsedRealtimeNanos())
        o.put("t_ph_utc_ms", System.currentTimeMillis())
        o.put("phone_session", phoneSession)
        fill(o)
        val json = o.toString()
        for (s in subscribers) s.onMessage(type, json)
    }

    /** `event` message (spec 7.7): `event` = name, plus the given fields. */
    fun event(name: String, fill: (JSONObject) -> Unit = {}) = publish("event") { o ->
        o.put("event", name)
        fill(o)
    }

    @Synchronized
    private fun nextSeq(type: String): Long {
        val s = seqByType.getOrDefault(type, 0L)
        seqByType[type] = s + 1
        return s
    }

    private fun utcStamp(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
