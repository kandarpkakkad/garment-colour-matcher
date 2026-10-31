package com.garmentcolour.engine

import kotlin.math.floor
import kotlin.math.max

/**
 * A linear-light image as three float planes. Planes are Float to keep a 512 px
 * working image near 2.4 MB; every sum over them is done in Double (colour LLD §5).
 */
class LinearImage(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    val size get() = width * height

    init {
        require(r.size == size && g.size == size && b.size == size) { "planes must be $width × $height" }
    }

    fun max(i: Int): Float = maxOf(r[i], g[i], b[i])

    companion object {
        /** From packed ARGB (Android's Bitmap layout); alpha is ignored. */
        fun fromArgb(argb: IntArray, width: Int, height: Int): LinearImage {
            val n = width * height
            require(argb.size >= n) { "need $n pixels, got ${argb.size}" }
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            for (i in 0 until n) {
                val p = argb[i]
                r[i] = Srgb.DECODE[(p shr 16) and 0xFF]
                g[i] = Srgb.DECODE[(p shr 8) and 0xFF]
                b[i] = Srgb.DECODE[p and 0xFF]
            }
            return LinearImage(width, height, r, g, b)
        }
    }
}

/**
 * Box-averages a full image into the working image, in linear light, from strips of
 * rows (pipeline LLD §5). Source pixel (x, y) lands in working pixel
 * (⌊x·w/W⌋, ⌊y·h/H⌋), so every source pixel counts exactly once.
 */
class BoxDownscaler(private val srcW: Int, private val srcH: Int, val dstW: Int, val dstH: Int) {
    private val sr = DoubleArray(dstW * dstH)
    private val sg = DoubleArray(dstW * dstH)
    private val sb = DoubleArray(dstW * dstH)
    private val count = IntArray(dstW * dstH)
    private val xMap = IntArray(srcW) { (it.toLong() * dstW / srcW).toInt() }

    /** [argb] holds [rows] full rows starting at source row [firstRow]. */
    fun addRows(argb: IntArray, firstRow: Int, rows: Int) {
        for (dy in 0 until rows) {
            val y = firstRow + dy
            val base = ((y.toLong() * dstH / srcH).toInt()) * dstW
            val off = dy * srcW
            for (x in 0 until srcW) {
                val p = argb[off + x]
                val j = base + xMap[x]
                sr[j] += Srgb.DECODE[(p shr 16) and 0xFF]
                sg[j] += Srgb.DECODE[(p shr 8) and 0xFF]
                sb[j] += Srgb.DECODE[p and 0xFF]
                count[j]++
            }
        }
    }

    fun result(): LinearImage {
        val n = dstW * dstH
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val c = count[i]
            if (c > 0) {
                r[i] = (sr[i] / c).toFloat()
                g[i] = (sg[i] / c).toFloat()
                b[i] = (sb[i] / c).toFloat()
            }
        }
        return LinearImage(dstW, dstH, r, g, b)
    }

    companion object {
        const val WORKING_LONG_SIDE = 512

        /** D14: at most 512 px on the long side; never upscaled. */
        fun workingSize(width: Int, height: Int, longest: Int = WORKING_LONG_SIDE): Pair<Int, Int> {
            val s = longest.toDouble() / max(width, height)
            if (s >= 1.0) return width to height
            return max(1, floor(width * s + 0.5).toInt()) to max(1, floor(height * s + 0.5).toInt())
        }
    }
}
