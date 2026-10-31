package com.garmentcolour.app.ui.capture

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * Preview plus image capture, JPEG or RAW (camera LLD §1, D23). `LifecycleCameraController`
 * cannot select an output format, so the use cases are bound by hand.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraBinding(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    /** Null until a bind has asked the camera. */
    var rawSupported: Boolean? = null
        private set

    /** Whether the bound capture writes DNGs. */
    var rawActive: Boolean = false
        private set

    suspend fun bind(owner: LifecycleOwner, view: PreviewView, wantRaw: Boolean, flashOn: Boolean) {
        val p = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        p.unbindAll()
        if (wantRaw && rawSupported == true && tryBind(p, owner, selector, preview, raw = true, flashOn)) return

        val jpeg = imageCapture(raw = false, flashOn)
        val camera = p.bindToLifecycle(owner, selector, preview, jpeg)
        capture = jpeg
        rawActive = false
        rawSupported = ImageCapture.getImageCaptureCapabilities(camera.cameraInfo)
            .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
        if (wantRaw && rawSupported == true) {
            p.unbind(jpeg)
            if (!tryBind(p, owner, selector, preview, raw = true, flashOn)) {
                // The camera lists RAW but refused this stream combination: stay on JPEG and
                // say so, rather than leave the switch promising something that cannot work.
                rawSupported = false
                p.bindToLifecycle(owner, selector, preview, jpeg)
                capture = jpeg
            }
        }
    }

    private fun tryBind(
        p: ProcessCameraProvider, owner: LifecycleOwner, selector: CameraSelector, preview: Preview,
        raw: Boolean, flashOn: Boolean,
    ): Boolean = try {
        val ic = imageCapture(raw, flashOn)
        p.bindToLifecycle(owner, selector, preview, ic)
        capture = ic
        rawActive = raw
        true
    } catch (e: IllegalArgumentException) {
        Log.w("CameraBinding", "could not bind ${if (raw) "RAW" else "JPEG"} capture", e)
        false
    }

    private fun imageCapture(raw: Boolean, flashOn: Boolean): ImageCapture {
        val b = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
        if (raw) {
            b.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
            // So DngCreator writes the lens-shading gain map into the DNG (raw LLD §2).
            Camera2Interop.Extender(b).setCaptureRequestOption(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        }
        return b.build()
    }

    fun setFlash(on: Boolean) {
        capture?.flashMode = if (on) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    /** Writes the photo to [file] (a `.dng` when [rawActive]). */
    fun take(file: File, executor: Executor, callback: ImageCapture.OnImageSavedCallback) {
        val ic = capture
        if (ic == null) {
            callback.onError(ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "the camera is not ready", null))
            return
        }
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, callback)
    }
}
