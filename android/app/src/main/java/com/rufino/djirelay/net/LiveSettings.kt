package com.rufino.djirelay.net

import android.content.Context

/** Persisted settings of the live stream (spec 7.6: the PC IP is entered in the app and persisted). */
class LiveSettings(context: Context) {
    private val prefs = context.getSharedPreferences("live_stream", Context.MODE_PRIVATE)

    var pcIp: String
        get() = prefs.getString(KEY_PC_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PC_IP, value.trim()).apply()

    companion object {
        private const val KEY_PC_IP = "pc_ip"
        const val VIDEO_PORT = 5600

        /** Dotted IPv4 literal check (no DNS lookup). */
        fun isValidIpv4(s: String): Boolean {
            val parts = s.trim().split('.')
            if (parts.size != 4) return false
            return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
        }
    }
}
