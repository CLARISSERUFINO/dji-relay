package com.rufino.djirelay.telemetry

import android.util.Log
import com.rufino.djirelay.net.MessageBus
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.sdk.SdkController.TAG
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.base.DJIValue
import dji.sdk.keyvalue.value.camera.CameraStorageInfo
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Drone telemetry (spec 7.7, RF5): subscribes to the keys of the spec, all checked with
 * javap on dji-sdk-v5-aircraft-provided 5.20.1 (declared in DJIFlightControllerKey /
 * FlightControllerKey, DJIGimbalKey, DJICameraKey / CameraKey, DJIBatteryKey and
 * DJIAirlinkKey, reached through the public subclasses), and publishes one `telemetry`
 * message per update: `{key, value, t_ph_ns}`.
 *
 * Every 10 s a `key_rates` message reports the measured update rate of each key (V7).
 * Values: DJIValue -> toJson(); enums -> name; numbers, booleans, strings as they are;
 * CameraStorageInfos -> built from its getters.
 */
object TelemetryCollector {

    private class Entry(val name: String, val info: DJIKeyInfo<*>)

    private val keys = listOf(
        // Flight controller
        Entry("KeyAircraftAttitude", FlightControllerKey.KeyAircraftAttitude),
        Entry("KeyAircraftVelocity", FlightControllerKey.KeyAircraftVelocity),
        Entry("KeyAircraftLocation3D", FlightControllerKey.KeyAircraftLocation3D),
        Entry("KeyUltrasonicHeight", FlightControllerKey.KeyUltrasonicHeight),
        Entry("KeyTakeoffLocationAltitude", FlightControllerKey.KeyTakeoffLocationAltitude),
        Entry("KeyGPSSatelliteCount", FlightControllerKey.KeyGPSSatelliteCount),
        Entry("KeyGPSSignalLevel", FlightControllerKey.KeyGPSSignalLevel),
        Entry("KeyFlightMode", FlightControllerKey.KeyFlightMode),
        Entry("KeyAreMotorsOn", FlightControllerKey.KeyAreMotorsOn),
        Entry("KeyIsFlying", FlightControllerKey.KeyIsFlying),
        Entry("KeyWindSpeed", FlightControllerKey.KeyWindSpeed),
        Entry("KeyWindDirection", FlightControllerKey.KeyWindDirection),
        Entry("KeyWindWarning", FlightControllerKey.KeyWindWarning),
        Entry("KeyIsLowBatteryWarning", FlightControllerKey.KeyIsLowBatteryWarning),
        Entry("KeyIsSeriousLowBatteryWarning", FlightControllerKey.KeyIsSeriousLowBatteryWarning),
        // Gimbal
        Entry("KeyGimbalAttitude", GimbalKey.KeyGimbalAttitude),
        Entry("KeyYawRelativeToAircraftHeading", GimbalKey.KeyYawRelativeToAircraftHeading),
        // Camera
        Entry("KeyIsRecording", CameraKey.KeyIsRecording),
        Entry("KeyRecordingTime", CameraKey.KeyRecordingTime),
        Entry("KeyCameraStorageInfos", CameraKey.KeyCameraStorageInfos),
        Entry("KeyNewlyGeneratedMediaFile", CameraKey.KeyNewlyGeneratedMediaFile),
        // Battery
        Entry("KeyChargeRemainingInPercent", BatteryKey.KeyChargeRemainingInPercent),
        Entry("KeyVoltage", BatteryKey.KeyVoltage),
        // Air link
        Entry("KeyFrequencyBand", AirLinkKey.KeyFrequencyBand),
        Entry("KeyUpLinkQuality", AirLinkKey.KeyUpLinkQuality),
        Entry("KeyDownLinkQuality", AirLinkKey.KeyDownLinkQuality),
    )

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var started = false

    /**
     * Subscribes after registration, and again each time the aircraft connects: keys
     * subscribed before the drone is connected only report later changes, so values that
     * do not change on the bench (flight mode, battery %, band...) would never be sent.
     * Re-subscribing with getOnce = true sends every key's current value on each connection.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        for (e in keys) counts[e.name] = AtomicLong(0)
        subscribeAll("registered")

        scope.launch {
            SdkController.state
                .map { it.aircraftConnected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        subscribeAll("aircraft connected")
                        snapshot("aircraft connected")
                    }
                }
        }

        scope.launch {
            var last = keys.associate { it.name to 0L }
            var lastNs = System.nanoTime()
            while (true) {
                delay(10_000)
                val nowNs = System.nanoTime()
                val dt = (nowNs - lastNs) / 1e9
                val now = keys.associate { it.name to (counts[it.name]?.get() ?: 0L) }
                MessageBus.publish("key_rates") { o ->
                    val rates = JSONObject()
                    for ((k, v) in now) rates.put(k, Math.round((v - (last[k] ?: 0L)) / dt * 100) / 100.0)
                    o.put("window_s", Math.round(dt * 1000) / 1000.0)
                    o.put("rates_hz", rates)
                }
                last = now
                lastNs = nowNs
            }
        }
    }

    /**
     * Reads every key's current value with KeyManager.getValue and publishes it as a
     * `telemetry` message with `"snapshot": true` (listen with getOnce only delivers values
     * the SDK already has cached, so on the bench most keys never reported). Called when
     * the aircraft connects and when live streaming or the phone recording starts, so each
     * session begins with every key's value. Snapshot reads are not counted in key_rates.
     * A key that fails is logged once with its error.
     */
    fun snapshot(reason: String) {
        if (!started) return
        @Suppress("UNCHECKED_CAST")
        for (e in keys) read(e.name, e.info as DJIKeyInfo<Any>)
        Log.i(TAG, "telemetry: snapshot of ${keys.size} keys requested ($reason)")
    }

    // Generic so that only getValue(DJIKey<T>, CompletionCallbackWithParam<T>) applies
    // (with T = Any the getValue(DJIKey<R>, R defaultValue) overload would match too).
    private fun <T> read(name: String, info: DJIKeyInfo<T>) {
        val key = KeyTools.createKey(info)
        KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                MessageBus.publish("telemetry") { o ->
                    o.put("key", name)
                    o.put("value", toJsonValue(value))
                    o.put("snapshot", true)
                }
            }

            override fun onFailure(error: IDJIError) {
                if (failedOnce.add(name)) {
                    Log.w(TAG, "telemetry: getValue($name) failed: ${error.errorCode()} ${error.description()}")
                }
            }
        })
    }

    private val failedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun subscribeAll(reason: String) {
        KeyManager.getInstance().cancelListen(this)
        for (e in keys) listen(e)
        Log.i(TAG, "telemetry: subscribed to ${keys.size} keys ($reason)")
    }

    private fun listen(e: Entry) {
        @Suppress("UNCHECKED_CAST")
        val key = KeyTools.createKey(e.info as DJIKeyInfo<Any>)
        KeyManager.getInstance().listen(key, this, true) { _, newValue ->
            counts[e.name]?.incrementAndGet()
            MessageBus.publish("telemetry") { o ->
                o.put("key", e.name)
                o.put("value", toJsonValue(newValue))
            }
        }
    }

    /** JSON value of a key value (see the class comment). JSONObject.NULL for null. */
    fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is DJIValue -> v.toJson()
        is Enum<*> -> v.name
        is Number, is Boolean, is String -> v
        is CameraStorageInfos -> JSONObject()
            .put("current_type", v.currentStorageType?.name ?: JSONObject.NULL)
            .put("storages", JSONArray().apply { v.cameraStorageInfoList?.forEach { put(storage(it)) } })
        is List<*> -> JSONArray().apply { v.forEach { put(toJsonValue(it)) } }
        else -> v.toString()
    }

    private fun storage(s: CameraStorageInfo): JSONObject = JSONObject()
        .put("type", s.storageType?.name ?: JSONObject.NULL)
        .put("state", s.storageState?.name ?: JSONObject.NULL)
        .put("capacity", s.storageCapacity ?: JSONObject.NULL)
        .put("left_capacity", s.storageLeftCapacity ?: JSONObject.NULL)
        .put("available_video_duration", s.availableVideoDuration ?: JSONObject.NULL)
}
