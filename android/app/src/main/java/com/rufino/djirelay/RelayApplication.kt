package com.rufino.djirelay

import android.app.Application
import android.content.Context
import com.rufino.djirelay.sdk.SdkController

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

    override fun onCreate() {
        super.onCreate()
        SdkController.init(this)
    }
}
