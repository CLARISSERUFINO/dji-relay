package com.rufino.djirelay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rufino.djirelay.sdk.SdkController
import com.rufino.djirelay.storage.PhoneRecorder
import com.rufino.djirelay.stream.StreamReceiver

class MainActivity : ComponentActivity() {

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private var locationGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationGranted = hasLocationPermission()
        Log.i(SdkController.TAG, "location permission result: $result")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The screen stays on for the whole session (RF9, spec 7.11).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // SampleCode-V5 requests location before using the SDK.
        locationGranted = hasLocationPermission()
        if (!locationGranted) permissionLauncher.launch(locationPermissions)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by SdkController.state.collectAsStateWithLifecycle()
                val stream by StreamReceiver.stats.collectAsStateWithLifecycle()
                val recorder by PhoneRecorder.state.collectAsStateWithLifecycle()
                var pilotViewOn by rememberSaveable { mutableStateOf(true) }
                StatusScreen(
                    state = state,
                    stream = stream,
                    recorder = recorder,
                    locationGranted = locationGranted,
                    pilotViewOn = pilotViewOn,
                    onPilotViewChange = { on ->
                        Log.i(SdkController.TAG, "pilot view ${if (on) "on" else "off"}")
                        pilotViewOn = on
                    },
                    onPhoneRecordingChange = { start ->
                        Log.i(SdkController.TAG, "phone recording ${if (start) "start" else "stop"} requested")
                        if (start) PhoneRecorder.start() else PhoneRecorder.stop()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneRecorder.refreshFreeSpace()
    }

    private fun hasLocationPermission() = locationPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
