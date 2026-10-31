package com.garmentcolour.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.garmentcolour.app.data.CaptureEntity
import com.garmentcolour.app.data.CaptureStore
import com.garmentcolour.app.models.Segmenters
import com.garmentcolour.engine.Analysis
import com.garmentcolour.engine.Box
import com.garmentcolour.engine.Correction
import com.garmentcolour.engine.Engine
import com.garmentcolour.engine.Fusion
import com.garmentcolour.engine.GrabCut
import com.garmentcolour.engine.Illuminant
import com.garmentcolour.engine.LabelMap
import com.garmentcolour.engine.LinearImage
import com.garmentcolour.engine.NeutralResult
import com.garmentcolour.engine.Refusal
import com.garmentcolour.engine.SrgbCorrection
import com.garmentcolour.engine.Tier
import com.garmentcolour.engine.Tier3
import com.garmentcolour.engine.Vec3
import com.garmentcolour.engine.raw.CameraProfile
import com.garmentcolour.engine.raw.Dng
import com.garmentcolour.engine.raw.DngException
import com.garmentcolour.engine.raw.RawCorrection
import com.garmentcolour.engine.raw.RawImages
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface Input {
    data class Camera(val file: File) : Input
    /** A DNG CameraX wrote (D23). */
    data class CameraRaw(val file: File) : Input
    data class Import(val uri: Uri) : Input
}

enum class Step { DECODING, FINDING_GARMENTS, READING_LIGHT, CORRECTING, SAVING }

/** Something the person can be told, in words. */
class PipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface TapResult {
    data object Done : TapResult
    data class Refused(val why: Refusal) : TapResult
}

/**
 * A photo opened for analysis: its working image, its correction, and how to render it.
 * JPEG and RAW differ only here (D23); everything after is one path.
 */
private interface Opened {
    val working: LinearImage
    val correction: Correction
    val decoded: Decoded
    val capturePath: String
    val exposureGain: Double

    /** A new bitmap at working size for the segmenters; the caller recycles it. */
    fun preview(): Bitmap

    /** Renders the full image corrected for [analysis] to [file]. */
    suspend fun render(analysis: Analysis, file: File)

    /** RAW only: the *before* image, rendered with the phone's own white point. */
    suspend fun renderBefore(file: File) {}

    fun close() {}
}

private class OpenedJpeg(private val full: Bitmaps.Full) : Opened {
    override val working = Bitmaps.working(full.bitmap)
    override val correction: Correction = SrgbCorrection
    override val decoded = full.decoded
    override val capturePath = "JPEG"
    override val exposureGain = 1.0

    override fun preview(): Bitmap = Bitmaps.small(full.bitmap, working)

    override suspend fun render(analysis: Analysis, file: File) {
        Bitmaps.correctInPlace(full.bitmap, analysis.matrix)
        Bitmaps.writeJpeg(full.bitmap, file)
    }

    override fun close() = full.bitmap.recycle()
}

/** Raw LLD §1–§8. */
private class OpenedRaw(file: File) : Opened {
    private val dng = Dng.read(file)
    private val profile = CameraProfile(dng)
    private val raw = RawImages.linearize(dng)
    override val working = RawImages.working(raw, profile)
    override val exposureGain = RawImages.exposureGain(working, dng.baselineExposure)
    private val rawCorrection = RawCorrection(profile, exposureGain)
    override val correction: Correction = rawCorrection
    private val size = RawImages.outputSize(raw)
    override val decoded = Decoded(size.first, size.second, dng.cropW.toLong() * dng.cropH / 1e6, size.third)
    override val capturePath = "RAW"

    override fun preview(): Bitmap = Bitmap.createBitmap(
        RawImages.preview(working, rawCorrection.matrix(rawCorrection.fallback)),
        working.width, working.height, Bitmap.Config.ARGB_8888,
    )

    override suspend fun render(analysis: Analysis, file: File) =
        Bitmaps.renderRaw(raw, rawCorrection.cameraMatrix(analysis.applied), size.first, size.second, file)

    override suspend fun renderBefore(file: File) =
        Bitmaps.renderRaw(raw, rawCorrection.cameraMatrix(rawCorrection.fallback), size.first, size.second, file)
}

/**
 * A photo to a saved capture, and a saved capture re-rendered. One render path for
 * all four callers (pipeline HLD). docs/02-domain/pipeline/lld.md.
 */
class Pipeline(
    private val context: Context,
    private val store: CaptureStore,
    private val segmenters: Segmenters,
    /** The start-up sweep; nothing is written until it has finished (pipeline LLD §8). */
    private val ready: Deferred<Unit>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Pipeline LLD §2. Returns the new capture's id. */
    suspend fun process(input: Input, progress: (Step) -> Unit): String = withContext(Dispatchers.Default) {
        ready.await()
        mutex.withLock {
            val id = UUID.randomUUID().toString()
            val dir = store.dir(id)
            if (!dir.mkdirs()) throw PipelineException("Could not make room for the photo")
            try {
                val source = bringIn(input, id)
                progress(Step.DECODING)
                val opened = open(id, if (input is Input.CameraRaw) "RAW" else "JPEG")
                try {
                    progress(Step.FINDING_GARMENTS)
                    val (labels, tier) = segment(opened)
                    progress(Step.READING_LIGHT)
                    val analysis = Engine.analyse(opened.working, labels, correction = opened.correction)
                    progress(Step.CORRECTING)
                    opened.renderBefore(store.original(id))
                    opened.render(analysis, store.corrected(id))
                    progress(Step.SAVING)
                    writeLabels(labels, store.labels(id))
                    // Files first, row last: a row never points at a file not yet written.
                    store.insert(
                        Records.capture(id, clock(), source, opened.decoded, tier, analysis, opened.capturePath, opened.exposureGain),
                        Records.garments(id, analysis.garments),
                    )
                } finally {
                    opened.close()
                }
                id
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw e
            }
        }
    }

    /** A neutral tap at (u, v), as fractions of the image (illuminant LLD §6). */
    suspend fun retap(id: String, u: Double, v: Double): TapResult = rerender(id) { capture, opened, labels ->
        when (val tap = Illuminant.neutral(opened.working, u, v)) {
            is NeutralResult.Refused -> null to TapResult.Refused(tap.why)
            is NeutralResult.Light -> {
                val analysis = Engine.analyse(opened.working, labels, tap.e, opened.correction)
                Rerender(analysis, labels, Tier.valueOf(capture.tier), AdjustmentKind.NEUTRAL_TAP, tap = u to v) to TapResult.Done
            }
        }
    }

    suspend fun useEstimate(id: String) {
        rerender(id) { capture, opened, labels ->
            val analysis = Engine.analyse(opened.working, labels, correction = opened.correction)
            Rerender(analysis, labels, Tier.valueOf(capture.tier), AdjustmentKind.USE_ESTIMATE) to Unit
        }
    }

    /**
     * A dragged box, cut out by GrabCut (D24), replaces the label map and resets the light to
     * the estimate (pipeline HLD).
     */
    suspend fun box(id: String, box: Box) {
        rerender(id) { _, opened, _ ->
            val b = box.normalised()
            val labels = GrabCut.segment(opened.working, b).labels
            val analysis = Engine.analyse(opened.working, labels, correction = opened.correction)
            Rerender(analysis, labels, Tier.BOX, AdjustmentKind.BOX, box = b) to Unit
        }
    }

    private class Rerender(
        val analysis: Analysis,
        val labels: LabelMap,
        val tier: Tier,
        val kind: AdjustmentKind,
        val tap: Pair<Double, Double>? = null,
        val box: Box? = null,
    )

    /** Pipeline LLD §3. [plan] returns null to change nothing. */
    private suspend fun <T> rerender(
        id: String,
        plan: (CaptureEntity, Opened, LabelMap) -> Pair<Rerender?, T>,
    ): T = withContext(Dispatchers.Default) {
        ready.await()
        mutex.withLock {
            val capture = store.get(id) ?: throw PipelineException("This capture no longer exists")
            val opened = open(id, capture.capturePath)
            try {
                val stored = readLabels(store.labels(id), opened.working)
                val (r, result) = plan(capture, opened, stored)
                if (r == null) return@withLock result
                opened.render(r.analysis, store.corrected(id))
                if (r.labels !== stored) writeLabels(r.labels, store.labels(id))
                val updated = Records.capture(
                    id, capture.createdAt, Source.valueOf(capture.source), opened.decoded, r.tier, r.analysis,
                    opened.capturePath, opened.exposureGain,
                )
                store.rerendered(
                    updated,
                    Records.garments(id, r.analysis.garments),
                    Records.adjustment(id, clock(), r.kind, Records.applied(capture), r.analysis.applied, r.tap, r.box),
                )
                result
            } finally {
                opened.close()
            }
        }
    }

    private fun bringIn(input: Input, id: String): Source = when (input) {
        is Input.Camera -> {
            move(input.file, store.original(id))
            Source.CAMERA
        }
        is Input.CameraRaw -> {
            move(input.file, store.originalDng(id))
            Source.CAMERA
        }
        is Input.Import -> {
            val stream = context.contentResolver.openInputStream(input.uri)
                ?: throw PipelineException("Could not open the photo you picked")
            stream.use { inp -> store.original(id).outputStream().use { inp.copyTo(it) } }
            Source.IMPORT
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun open(id: String, capturePath: String): Opened = if (capturePath == "RAW") {
        val file = store.originalDng(id)
        if (!file.exists()) throw PipelineException("The RAW photo is missing")
        try {
            OpenedRaw(file)
        } catch (e: DngException) {
            throw PipelineException("Could not read this RAW photo: ${e.message}", e)
        } catch (e: IOException) {
            throw PipelineException("Could not read this RAW photo: ${e.message ?: e.javaClass.simpleName}", e)
        }
    } else {
        OpenedJpeg(decode(store.original(id)))
    }

    private fun decode(file: File): Bitmaps.Full = try {
        if (!file.exists()) throw PipelineException("The original photo is missing")
        Bitmaps.decode(file)
    } catch (e: PipelineException) {
        throw e
    } catch (e: Exception) {
        // ImageDecoder's message names the problem; keep it (an error says the reason).
        throw PipelineException("Could not read this photo: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private suspend fun segment(opened: Opened): Pair<LabelMap, Tier> {
        val small = opened.preview()
        val w = opened.working.width
        val h = opened.working.height
        try {
            val (people, clothes) = segmenters.segment(small)
            val fused = Fusion.fuse(people, clothes, w, h)
                // Neither model ran: GrabCut on the centre of the frame (D24).
                ?: return GrabCut.segment(opened.working, Tier3.CENTRE).labels to Tier.CENTRE
            return fused.labels to fused.tier
        } finally {
            small.recycle()
        }
    }

    private fun writeLabels(labels: LabelMap, file: File) = Bitmaps.writeAtomically(file) { labels.write(it) }

    private fun readLabels(file: File, working: LinearImage): LabelMap {
        val labels = try {
            file.inputStream().buffered().use { LabelMap.read(it) }
        } catch (e: IOException) {
            throw PipelineException("This capture's garment map is unreadable: ${e.message}", e)
        }
        if (labels.width != working.width || labels.height != working.height) {
            throw PipelineException("This capture's garment map does not fit its photo")
        }
        return labels
    }

    companion object {
        /** The applied light, as stored. */
        fun applied(c: CaptureEntity): Vec3 = Records.applied(c)
    }
}
