package com.rufino.djirelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rufino.djirelay.sdk.SdkState
import dji.sdk.keyvalue.value.common.ComponentIndexType

private val Ok = Color(0xFF81C784)
private val Bad = Color(0xFFE57373)
private val Neutral = Color(0xFFB0BEC5)

/** Phase F1 screen: status panel on the left, pilot view on the right. */
@Composable
fun StatusScreen(
    state: SdkState,
    locationGranted: Boolean,
    pilotViewOn: Boolean,
    onPilotViewChange: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Row(Modifier.fillMaxSize()) {
            StatusPanel(
                state = state,
                locationGranted = locationGranted,
                pilotViewOn = pilotViewOn,
                onPilotViewChange = onPilotViewChange,
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF15191C))
                    .padding(12.dp),
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val canShow = state.aircraftConnected && state.cameraIndex != ComponentIndexType.UNKNOWN
                when {
                    !pilotViewOn -> Placeholder("Pilot view off")
                    !canShow -> Placeholder("Waiting for the drone camera")
                    else -> PilotView(cameraIndex = state.cameraIndex, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(
    state: SdkState,
    locationGranted: Boolean,
    pilotViewOn: Boolean,
    onPilotViewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DJI Relay", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))

        Section("SDK")
        Item("Init", state.initEvent ?: "not started", if (state.initEvent == "INITIALIZE_COMPLETE") Ok else Neutral)
        Item(
            "Registration",
            when {
                state.registered -> "SDK registered"
                state.registerError != null -> "failed: ${state.registerError}"
                else -> "pending"
            },
            when {
                state.registered -> Ok
                state.registerError != null -> Bad
                else -> Neutral
            },
        )
        Item("MSDK", state.sdkVersion ?: "-")
        Item("Location permission", if (locationGranted) "granted" else "not granted", if (locationGranted) Ok else Bad)

        Section("Drone")
        Item(
            "Connection",
            if (state.aircraftConnected) "connected" else "disconnected",
            if (state.aircraftConnected) Ok else Bad,
        )
        Item("Product", state.productType ?: "-")
        Item("Product id", state.productId?.toString() ?: "-")
        Item("Firmware", state.aircraftFirmware ?: "-")

        Section("Remote controller")
        Item("Connection", if (state.rcConnected) "connected" else "disconnected", if (state.rcConnected) Ok else Bad)
        Item("Firmware", state.rcFirmware ?: "-")
        Item("Firmware target", state.rcFirmwareTarget ?: "-")

        Section("Camera stream")
        Item("Available", if (state.availableCameras.isEmpty()) "-" else state.availableCameras.joinToString())
        Item("Using", state.cameraIndex.name)

        Section("Controls")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Pilot view", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = pilotViewOn, onCheckedChange = onPilotViewChange)
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    Text(title.uppercase(), color = Neutral, fontSize = 11.sp)
    HorizontalDivider(color = Color(0xFF37474F))
}

@Composable
private fun Item(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Neutral, fontSize = 13.sp, modifier = Modifier.weight(0.45f))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(text, color = Neutral)
}
