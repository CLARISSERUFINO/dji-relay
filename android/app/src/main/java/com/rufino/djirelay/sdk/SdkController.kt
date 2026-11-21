package com.rufino.djirelay.sdk

import android.content.Context
import android.util.Log
import com.rufino.djirelay.BuildConfig
import dji.open.pilot.client.manager.ServerState
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.common.register.InitSettings
import dji.v5.common.register.SDKLinKMode
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the MSDK lifecycle: initialisation, registration, product connection, the
 * connection/identity keys and the available-camera list (spec 7.2, 7.3).
 *
 * The flow follows SampleCode-V5's MSDKManagerVM.initMobileSDK: init with
 * SDKLinKMode.LOCAL, registerApp() on INITIALIZE_COMPLETE, and registerApp() again when
 * DJINetworkManager reports the network back while the app is not registered.
 *
 * Every state change is logged with the tag [TAG], so phase F1 results (V11, firmware
 * versions) can be read with `adb logcat -s DjiRelay`.
 */
object SdkController {
    const val TAG = "DjiRelay"

    private val _state = MutableStateFlow(SdkState())
    val state: StateFlow<SdkState> = _state.asStateFlow()

    private val initCalled = AtomicBoolean(false)
    private val initComplete = AtomicBoolean(false)
    private val keysStarted = AtomicBoolean(false)

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    fun init(context: Context) {
        if (!initCalled.compareAndSet(false, true)) return
        Log.i(TAG, "app ${BuildConfig.VERSION_NAME}: SDKManager.init (SDKLinKMode.LOCAL)")

        SDKManager.getInstance().init(context, InitSettings(SDKLinKMode.LOCAL), sdkCallback)

        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            val registered = SDKManager.getInstance().isRegistered
            Log.i(TAG, "network available=$isAvailable (initComplete=${initComplete.get()}, registered=$registered)")
            if (initComplete.get() && isAvailable && !registered) {
                Log.i(TAG, "network back and not registered: registerApp()")
                SDKManager.getInstance().registerApp()
            }
        }
    }

    private val sdkCallback = object : SDKManagerCallback {
        override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
            Log.i(TAG, "onInitProcess: event=$event, totalProcess=$totalProcess")
            _state.update { it.copy(initEvent = event.name) }
            if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                initComplete.set(true)
                val version = SDKManager.getInstance().sdkVersion
                Log.i(TAG, "MSDK version $version; registerApp()")
                _state.update { it.copy(sdkVersion = version) }
                SDKManager.getInstance().registerApp()
            }
        }

        override fun onRegisterSuccess() {
            Log.i(TAG, "onRegisterSuccess")
            _state.update { it.copy(registered = true, registerError = null) }
            startKeysAndCameraListener()
        }

        override fun onRegisterFailure(error: IDJIError) {
            val text = "${error.errorCode()}: ${error.description()}"
            Log.e(TAG, "onRegisterFailure: $error")
            _state.update { it.copy(registered = false, registerError = text) }
        }

        override fun onProductConnect(productId: Int) {
            Log.i(TAG, "onProductConnect: productId=$productId")
            _state.update { it.copy(productConnected = true, productId = productId) }
        }

        override fun onProductDisconnect(productId: Int) {
            Log.i(TAG, "onProductDisconnect: productId=$productId")
            _state.update { it.copy(productConnected = false, productId = productId) }
        }

        override fun onProductChanged(productId: Int) {
            Log.i(TAG, "onProductChanged: productId=$productId")
            _state.update { it.copy(productId = productId) }
        }

        @Deprecated("Deprecated in MSDK")
        override fun onDatabaseDownloadProgress(current: Long, total: Long) {
            Log.d(TAG, "onDatabaseDownloadProgress: $current/$total")
        }

        // Open Pilot (plugin) callbacks: not used by this app, logged only.
        override fun onRemoteServerStateUpdated(serverState: ServerState) {
            Log.d(TAG, "onRemoteServerStateUpdated: $serverState")
        }

        override fun onRemoteServerCompatibleError(error: IDJIError) {
            Log.w(TAG, "onRemoteServerCompatibleError: $error")
        }
    }

    /**
     * Connection and identity keys, and the camera list. They stay subscribed for the
     * whole process lifetime, so they also report disconnections and reconnections.
     */
    private fun startKeysAndCameraListener() {
        if (!keysStarted.compareAndSet(false, true)) return

        listen(FlightControllerKey.KeyConnection) { v ->
            Log.i(TAG, "FlightControllerKey.KeyConnection = $v")
            _state.update { it.copy(aircraftConnected = v == true) }
        }
        listen(ProductKey.KeyProductType) { v ->
            Log.i(TAG, "ProductKey.KeyProductType = $v")
            _state.update { it.copy(productType = v?.name) }
        }
        listen(ProductKey.KeyFirmwareVersion) { v ->
            Log.i(TAG, "firmware: aircraft (ProductKey.KeyFirmwareVersion) = $v")
            _state.update { it.copy(aircraftFirmware = v) }
        }
        listen(RemoteControllerKey.KeyConnection) { v ->
            Log.i(TAG, "RemoteControllerKey.KeyConnection = $v")
            _state.update { it.copy(rcConnected = v == true) }
        }
        listen(RemoteControllerKey.KeyFirmwareVersion) { v ->
            Log.i(TAG, "firmware: remote controller (RemoteControllerKey.KeyFirmwareVersion) = $v")
            _state.update { it.copy(rcFirmware = v) }
        }
        listen(RemoteControllerKey.KeyFirmwareMatchType) { v ->
            Log.i(TAG, "firmware: remote controller target (RemoteControllerKey.KeyFirmwareMatchType) = $v")
            _state.update { it.copy(rcFirmwareTarget = v?.name) }
        }

        cameraStreamManager.addAvailableCameraUpdatedListener(
            object : ICameraStreamManager.AvailableCameraUpdatedListener {
                override fun onAvailableCameraUpdated(availableCameraList: List<ComponentIndexType>) {
                    val chosen = chooseCamera(availableCameraList)
                    // V11: the camera index of the Mini 3 Pro.
                    Log.i(TAG, "V11: available cameras = $availableCameraList; using $chosen")
                    _state.update { it.copy(availableCameras = availableCameraList, cameraIndex = chosen) }
                }

                // Declared `default` in the provided jar, but abstract in the runtime
                // classes: without this override the MSDK throws AbstractMethodError.
                // Rule: implement every method of MSDK interfaces, defaults included.
                override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: Map<ComponentIndexType, Boolean>) {
                    Log.i(TAG, "camera stream enabled = $cameraStreamEnableMap")
                }
            }
        )
    }

    /** LEFT_OR_MAIN is the expected value (spec 7.3, [VERIFY]); otherwise the first one. */
    private fun chooseCamera(list: List<ComponentIndexType>): ComponentIndexType {
        val usable = list.filter { it != ComponentIndexType.UNKNOWN }
        return when {
            ComponentIndexType.LEFT_OR_MAIN in usable -> ComponentIndexType.LEFT_OR_MAIN
            usable.isNotEmpty() -> usable.first()
            else -> ComponentIndexType.UNKNOWN
        }
    }

    /** KeyManager.listen with getOnce = true: also delivers the current value at once. */
    private fun <T> listen(info: DJIKeyInfo<T>, onValue: (T?) -> Unit) {
        val key: DJIKey<T> = KeyTools.createKey(info)
        KeyManager.getInstance().listen(key, this, true) { _, newValue -> onValue(newValue) }
    }
}
