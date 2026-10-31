package com.garmentcolour.app.pipeline

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import com.garmentcolour.engine.BoxDownscaler
import com.garmentcolour.engine.Corrector
import com.garmentcolour.engine.LinearImage
import com.garmentcolour.engine.Mat3
import com.garmentcolour.engine.raw.LinearRaw
import com.garmentcolour.engine.raw.RawImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The only place Android bitmaps meet engine types (pipeline HLD). Full images are
 * walked in strips of rows so one bitmap is the most ever held (pipeline LLD §7).
 */
object Bitmaps {
    /** D14: a 50 MP bitmap is 200 MB and would be no more accurate. */
    const val MAX_PIXELS = 16_000_000L
    private const val STRIP = 64

    class Full(val bitmap: Bitmap, val decoded: Decoded)

    /** Mutable sRGB ARGB_8888, orientation applied by the decoder. Pipeline LLD §4. */
    fun decode(file: File): Full {
        var srcW = 0
        var srcH = 0
        var scaled = false
        var bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            srcW = info.size.width
            srcH = info.size.height
            val px = srcW.toLong() * srcH
            if (px > MAX_PIXELS) {
                val s = sqrt(MAX_PIXELS.toDouble() / px)
                decoder.setTargetSize(max(1, (srcW * s).toInt()), max(1, (srcH * s).toInt()))
                scaled = true
            }
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            // A 16-bit or HDR source decodes to F16; the engine reads 8-bit sRGB.
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = copy
        }
        // An Ultra HDR gain map carries the original's colours: kept, a viewer would
        // re-apply them over the correction.
        if (bmp.hasGainmap()) bmp.gainmap = null
        return Full(bmp, Decoded(bmp.width, bmp.height, srcW.toLong() * srcH / 1e6, scaled))
    }

    /** The working image, box-averaged in linear light (pipeline LLD §5). */
    fun working(bmp: Bitmap): LinearImage {
        val (w, h) = BoxDownscaler.workingSize(bmp.width, bmp.height)
        val d = BoxDownscaler(bmp.width, bmp.height, w, h)
        forStrips(bmp) { buf, y, rows -> d.addRows(buf, y, rows) }
        return d.result()
    }

    /** The full image scaled to the working size, for the segmenters: always a new bitmap. */
    fun small(bmp: Bitmap, working: LinearImage): Bitmap {
        val s = Bitmap.createScaledBitmap(bmp, working.width, working.height, true)
        // Same size in, same bitmap out: copy, so the caller can recycle what it got.
        return if (s === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else s
    }

    /**
     * RAW: rows rendered in parallel ranges (raw LLD §8) into one bitmap, written as JPEG.
     * Batches of one range per spare core bound the memory to a few strips.
     */
    suspend fun renderRaw(raw: LinearRaw, f: Mat3, width: Int, height: Int, file: File) = coroutineScope {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val ranges = (0 until height step STRIP).map { it to minOf(height, it + STRIP) }
            val parallel = max(1, Runtime.getRuntime().availableProcessors() - 1)
            for (batch in ranges.chunked(parallel)) {
                val done = batch.map { (y0, y1) ->
                    async(Dispatchers.Default) { IntArray((y1 - y0) * width).also { RawImages.renderRows(raw, f, y0, y1, it) } }
                }.awaitAll()
                batch.zip(done).forEach { (r, px) -> bmp.setPixels(px, 0, width, 0, r.first, width, r.second - r.first) }
            }
            writeJpeg(bmp, file)
        } finally {
            bmp.recycle()
        }
    }

    /** Pipeline LLD §6. The identity leaves the bitmap untouched. */
    fun correctInPlace(bmp: Bitmap, matrix: Mat3) {
        val c = Corrector(matrix)
        if (c.isIdentity) return
        forStrips(bmp) { buf, y, rows ->
            c.apply(buf, 0, rows * bmp.width)
            bmp.setPixels(buf, 0, bmp.width, 0, y, bmp.width, rows)
        }
    }

    private inline fun forStrips(bmp: Bitmap, block: (IntArray, Int, Int) -> Unit) {
        val w = bmp.width
        val buf = IntArray(w * STRIP)
        var y = 0
        while (y < bmp.height) {
            val rows = minOf(STRIP, bmp.height - y)
            bmp.getPixels(buf, 0, w, 0, y, w, rows)
            block(buf, y, rows)
            y += rows
        }
    }

    /** Written beside the target and renamed, so a reader never sees half a file. */
    fun writeJpeg(bmp: Bitmap, file: File) = writeAtomically(file) { out ->
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("the JPEG encoder refused the image")
    }

    fun writeAtomically(file: File, write: (FileOutputStream) -> Unit) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { write(it); it.fd.sync() }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not move ${tmp.name} into place")
        }
    }

    /** For the screen: decoded at most [maxSide] on its long side. Null if the file is gone. */
    fun forDisplay(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = maxSide.toDouble() / longest
                decoder.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
            }
        }
    }
}
