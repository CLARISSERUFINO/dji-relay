package com.rufino.djirelay

import android.app.Application
import android.content.Context
import com.rufino.djirelay.net.LiveSender
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.storage.PhoneRecorder
import com.rufino.djirelay.stream.StreamReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.rufino.djirelay.telemetry.TelemetryCollector

/**
 * Same structure as SampleCode-V5's DJIAircraftApplication / DJIApplication (both are
 * classes of the sample itself, not of the SDK).
 */
class RelayApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Installs the MSDK's protected classes. Must run before any MSDK class is used.
        com.cySdkyc.clx.Helper.install(this)
    }

    /** Process-wide scope for the stream and recorder flows; lives as long as the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        SdkController.init(this)
        StreamReceiver.start(appScope)
        PhoneRecorder.init(this)
        LiveSender.init(this)
        // Telemetry keys are subscribed once the app is registered (spec 7.2 step 6).
        appScope.launch {
            SdkController.state.first { it.registered }
            TelemetryCollector.start(appScope)
        }
    }
}
