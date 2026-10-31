package com.garmentcolour.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Which segmentation produced a label map. Parsing HLD. */
enum class Tier { BOTH, CLOTHES_ONLY, PEOPLE_ONLY, BOX, CENTRE }

/** A rectangle as fractions of the image's width and height. */
data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
    /** Ordered, inside [0, 1], and at least 2 % of each side. Parsing LLD §5. */
    fun normalised(): Box {
        fun span(a: Double, b: Double): Pair<Double, Double> {
            var lo = min(a, b).coerceIn(0.0, 1.0)
            var hi = max(a, b).coerceIn(0.0, 1.0)
            if (hi - lo < MIN_SIDE) {
                val c = ((lo + hi) / 2).coerceIn(MIN_SIDE / 2, 1 - MIN_SIDE / 2)
                lo = c - MIN_SIDE / 2
                hi = c + MIN_SIDE / 2
            }
            return lo to hi
        }
        val (l2, r2) = span(l, r)
        val (t2, b2) = span(t, b)
        return Box(l2, t2, r2, b2)
    }

    companion object {
        const val MIN_SIDE = 0.02
    }
}

data class Fused(val labels: LabelMap, val tier: Tier)

/** Combining the two models' class maps (D13). Parsing LLD §2–§4. */
object Fusion {
    const val SEGFORMER_SIZE = 512
    const val SEGFORMER_CLASSES = 18
    const val SEGFORMER_OUT = 128

    /** MediaPipe Selfie Multiclass: 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 others. */
    fun fromMediaPipe(cls: Int): Label = when (cls) {
        0 -> Label.BACKGROUND
        1 -> Label.HAIR
        2, 3 -> Label.SKIN
        4 -> Label.CLOTHING
        else -> Label.ACCESSORY
    }

    /** SegFormer B2 clothes, `id2label` of its config.json. */
    fun fromSegformer(cls: Int): Label = when (cls) {
        0 -> Label.BACKGROUND
        2 -> Label.HAIR
        11, 12, 13, 14, 15 -> Label.SKIN
        4 -> Label.TOP
        5 -> Label.SKIRT
        6 -> Label.TROUSERS
        7 -> Label.DRESS
        17 -> Label.SCARF
        else -> Label.ACCESSORY // 1 hat, 3 sunglasses, 8 belt, 9–10 shoes, 16 bag
    }

    /**
     * Raw class maps, one byte per working pixel, either possibly absent. Null when both
     * are: the caller falls back to tier 3. Either model calling a pixel worn keeps it
     * out of the estimate.
     */
    fun fuse(mediaPipe: ByteArray?, segformer: ByteArray?, width: Int, height: Int): Fused? {
        val n = width * height
        mediaPipe?.let { require(it.size == n) { "MediaPipe map is ${it.size}, need $n" } }
        segformer?.let { require(it.size == n) { "SegFormer map is ${it.size}, need $n" } }
        val tier = when {
            mediaPipe != null && segformer != null -> Tier.BOTH
            segformer != null -> Tier.CLOTHES_ONLY
            mediaPipe != null -> Tier.PEOPLE_ONLY
            else -> return null
        }
        val out = ByteArray(n)
        for (i in 0 until n) {
            val m = mediaPipe?.let { fromMediaPipe(it[i].toInt() and 0xFF) }
            val s = segformer?.let { fromSegformer(it[i].toInt() and 0xFF) }
            val label = when {
                s == null -> m!!
                m == null -> s
                s.role == Role.GARMENT || s == Label.ACCESSORY -> s
                m == Label.CLOTHING || m == Label.ACCESSORY -> Label.ACCESSORY
                else -> s
            }
            out[i] = label.code.toByte()
        }
        return Fused(LabelMap(width, height, out), tier)
    }

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** NCHW `float32[1, 3, size, size]` from already-scaled ARGB pixels. Parsing LLD §4. */
    fun segformerInput(argb: IntArray, size: Int = SEGFORMER_SIZE): FloatArray {
        val plane = size * size
        require(argb.size >= plane) { "need $plane pixels, got ${argb.size}" }
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = argb[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /**
     * Logits `[classes][lh][lw]` upsampled bilinearly to `w × h`, then argmax (lowest
     * index on a tie). Returns raw SegFormer classes. Parsing LLD §4.
     */
    fun segformerArgmax(
        logits: FloatArray,
        width: Int,
        height: Int,
        classes: Int = SEGFORMER_CLASSES,
        lw: Int = SEGFORMER_OUT,
        lh: Int = SEGFORMER_OUT,
    ): ByteArray {
        require(logits.size >= classes * lw * lh) { "logits too short: ${logits.size}" }
        val plane = lw * lh
        val x0s = IntArray(width)
        val x1s = IntArray(width)
        val fxs = FloatArray(width)
        for (x in 0 until width) {
            val u = ((x + 0.5) * lw / width - 0.5).coerceIn(0.0, lw - 1.0)
            x0s[x] = floor(u).toInt()
            x1s[x] = min(x0s[x] + 1, lw - 1)
            fxs[x] = (u - x0s[x]).toFloat()
        }
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val v = ((y + 0.5) * lh / height - 0.5).coerceIn(0.0, lh - 1.0)
            val y0 = floor(v).toInt()
            val y1 = min(y0 + 1, lh - 1)
            val fy = (v - y0).toFloat()
            for (x in 0 until width) {
                val fx = fxs[x]
                var best = 0
                var bestValue = Float.NEGATIVE_INFINITY
                for (c in 0 until classes) {
                    val o = c * plane
                    val top = (1 - fx) * logits[o + y0 * lw + x0s[x]] + fx * logits[o + y0 * lw + x1s[x]]
                    val bottom = (1 - fx) * logits[o + y1 * lw + x0s[x]] + fx * logits[o + y1 * lw + x1s[x]]
                    val value = (1 - fy) * top + fy * bottom
                    if (value > bestValue) {
                        bestValue = value
                        best = c
                    }
                }
                out[y * width + x] = best.toByte()
            }
        }
        return out
    }
}

/** When no model produced a garment (D20). Parsing LLD §5. */
object Tier3 {
    val CENTRE = Box(0.30, 0.20, 0.70, 0.90)

    /** Pixels whose centre lies inside the box are SELECTED; the rest BACKGROUND. */
    fun box(width: Int, height: Int, box: Box): LabelMap {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val cy = (y + 0.5) / height
            for (x in 0 until width) {
                val cx = (x + 0.5) / width
                val inside = cy >= box.t && cy < box.b && cx >= box.l && cx < box.r
                out[y * width + x] = (if (inside) Label.SELECTED else Label.BACKGROUND).code.toByte()
            }
        }
        return LabelMap(width, height, out)
    }

    fun centre(width: Int, height: Int) = box(width, height, CENTRE)
}
