package com.rufino.djirelay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Live video for the pilot (RF9): a SurfaceView handed to the MSDK's own decoder with
 * putCameraStreamSurface. It does not touch the encoded stream that will be relayed.
 *
 * When the composable leaves the composition (pilot view switched off), the SurfaceView
 * is detached, surfaceDestroyed runs and the surface is removed from the MSDK.
 */
@Composable
fun PilotView(cameraIndex: ComponentIndexType, modifier: Modifier = Modifier) {
    val binder = remember { StreamSurfaceBinder() }
    DisposableEffect(cameraIndex) {
        binder.setCameraIndex(cameraIndex)
        onDispose { }
    }
    AndroidView(
        factory = { context -> SurfaceView(context).also { it.holder.addCallback(binder) } },
        modifier = modifier,
    )
}

/** Keeps the surface, its size and the camera index together; called on the main thread. */
private class StreamSurfaceBinder : SurfaceHolder.Callback {
    private val manager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private var cameraIndex = ComponentIndexType.UNKNOWN
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var attached = false

    fun setCameraIndex(index: ComponentIndexType) {
        if (index == cameraIndex) return
        detach()
        cameraIndex = index
        attach()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        this.width = width
        this.height = height
        attach()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detach()
        surface = null
    }

    private fun attach() {
        val s = surface ?: return
        if (cameraIndex == ComponentIndexType.UNKNOWN || width == 0 || height == 0) return
        // Called again on size changes, as the sample does.
        manager.putCameraStreamSurface(cameraIndex, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
        attached = true
    }

    private fun detach() {
        val s = surface ?: return
        if (!attached) return
        manager.removeCameraStreamSurface(s)
        attached = false
    }
}
