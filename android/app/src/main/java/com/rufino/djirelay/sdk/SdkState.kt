package com.rufino.djirelay.sdk

import dji.sdk.keyvalue.value.common.ComponentIndexType

/** Snapshot of the MSDK state shown in the UI. Immutable; replaced on every change. */
data class SdkState(
    /** Last DJISDKInitEvent name, or null before SDKManager.init reports anything. */
    val initEvent: String? = null,
    val registered: Boolean = false,
    /** errorCode / description of the last onRegisterFailure, cleared on success. */
    val registerError: String? = null,
    /** From onProductConnect / onProductDisconnect. */
    val productConnected: Boolean = false,
    val productId: Int? = null,
    /** FlightControllerKey.KeyConnection (spec 7.2 step 5). */
    val aircraftConnected: Boolean = false,
    /** ProductKey.KeyProductType, e.g. DJI_MINI_3_PRO. */
    val productType: String? = null,
    /** ProductKey.KeyFirmwareVersion: the aircraft firmware package version. */
    val aircraftFirmware: String? = null,
    /** RemoteControllerKey.KeyConnection. */
    val rcConnected: Boolean = false,
    /** RemoteControllerKey.KeyFirmwareVersion. */
    val rcFirmware: String? = null,
    /** RemoteControllerKey.KeyFirmwareMatchType: the aircraft family the RC firmware is built for, e.g. DJI_MINI_3_PRO. */
    val rcFirmwareTarget: String? = null,
    /** From ICameraStreamManager.AvailableCameraUpdatedListener (open item V11). */
    val availableCameras: List<ComponentIndexType> = emptyList(),
    /** Camera used for the stream: never hard-coded, chosen from availableCameras. */
    val cameraIndex: ComponentIndexType = ComponentIndexType.UNKNOWN,
    val sdkVersion: String? = null,
)
