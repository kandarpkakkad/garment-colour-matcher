package com.garmentcolour.app.models

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.garmentcolour.engine.Fusion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data class Ready(val loadMillis: Long) : ModelStatus
    /** The APK was built without running tool/models_in_docker.sh (D12). */
    data object Missing : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/** A model that returns raw class codes, one byte per pixel of the bitmap it was given. */
interface Segmenter : AutoCloseable {
    fun segment(small: Bitmap): ByteArray
}

/**
 * Loads both models once, in the background, and runs them one photo at a time.
 * A model that is missing or fails is absent, never fatal (models HLD).
 * docs/01-platform/models/lld.md §4.
 */
class Segmenters(private val context: Context) {
    private val _people = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    private val _clothes = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val people: StateFlow<ModelStatus> = _people
    val clothes: StateFlow<ModelStatus> = _clothes

    private var mediaPipe: Segmenter? = null
    private var segformer: Segmenter? = null
    private val loaded = CompletableDeferred<Unit>()
    private val mutex = Mutex()

    fun load(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            mediaPipe = open(MediaPipeSegmenter.ASSET, _people) { MediaPipeSegmenter(context) }
            segformer = open(SegformerSegmenter.ASSET, _clothes) { SegformerSegmenter(context) }
            loaded.complete(Unit)
        }
    }

    private fun open(asset: String, status: MutableStateFlow<ModelStatus>, create: () -> Segmenter): Segmenter? {
        val present = context.assets.list(ASSET_DIR)?.contains(asset.substringAfterLast('/')) == true
        if (!present) {
            status.value = ModelStatus.Missing
            return null
        }
        val start = SystemClock.elapsedRealtime()
        return try {
            create().also { status.value = ModelStatus.Ready(SystemClock.elapsedRealtime() - start) }
        } catch (e: Throwable) {
            Log.w(TAG, "could not load $asset", e)
            status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /**
     * (MediaPipe classes, SegFormer classes) for [small], each null if that model is
     * absent or threw on this photo — the tier then records which ran (parsing HLD).
     */
    suspend fun segment(small: Bitmap): Pair<ByteArray?, ByteArray?> {
        loaded.await()
        return mutex.withLock {
            val people = runModel("MediaPipe") { mediaPipe?.segment(small) }
            val clothes = runModel("SegFormer") { segformer?.segment(small) }
            people to clothes
        }
    }

    private inline fun runModel(name: String, block: () -> ByteArray?): ByteArray? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$name failed on this photo", e)
        null
    }

    companion object {
        const val ASSET_DIR = "models"
        private const val TAG = "Segmenters"
    }
}

/** MediaPipe Selfie Multiclass, as published (D11). */
class MediaPipeSegmenter(context: Context) : Segmenter {
    private val segmenter: ImageSegmenter = ImageSegmenter.createFromOptions(
        context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build(),
    )

    override fun segment(small: Bitmap): ByteArray {
        val result = segmenter.segment(BitmapImageBuilder(small).build())
        val mask = result.categoryMask().orElseThrow { IllegalStateException("MediaPipe returned no category mask") }
        val raw: ByteArray
        val mw: Int
        val mh: Int
        try {
            mw = mask.width
            mh = mask.height
            val buffer = ByteBufferExtractor.extract(mask)
            buffer.rewind()
            raw = ByteArray(mw * mh)
            buffer.get(raw)
        } finally {
            mask.close()
        }
        return if (mw == small.width && mh == small.height) raw else nearest(raw, mw, mh, small.width, small.height)
    }

    override fun close() = segmenter.close()

    companion object {
        const val ASSET = "models/selfie_multiclass_256x256.tflite"

        fun nearest(src: ByteArray, sw: Int, sh: Int, w: Int, h: Int) = ByteArray(w * h) { i ->
            val x = (i % w).toLong() * sw / w
            val y = (i / w).toLong() * sh / h
            src[(y * sw + x).toInt()]
        }
    }
}

/** SegFormer B2 clothes: the repository's own ONNX export, int8-quantised (D11). */
class SegformerSegmenter(context: Context) : Segmenter {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(ASSET).use { input ->
        env.createSession(input.readBytes(), OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
    }
    private val inputName: String = session.inputNames.first()

    override fun segment(small: Bitmap): ByteArray {
        val size = Fusion.SEGFORMER_SIZE
        val scaled = Bitmap.createScaledBitmap(small, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== small) scaled.recycle()
        val input = FloatBuffer.wrap(Fusion.segformerInput(px, size))
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                require(shape.size == 4) { "SegFormer output has shape ${shape.contentToString()}" }
                val buffer = out.floatBuffer
                val logits = FloatArray(buffer.remaining())
                buffer.get(logits)
                return Fusion.segformerArgmax(
                    logits, small.width, small.height,
                    classes = shape[1].toInt(), lw = shape[3].toInt(), lh = shape[2].toInt(),
                )
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val ASSET = "models/segformer_b2_clothes_int8.onnx"
    }
}
