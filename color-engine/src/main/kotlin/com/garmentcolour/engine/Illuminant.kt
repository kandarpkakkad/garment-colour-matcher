package com.garmentcolour.engine

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class Estimator { GRAY_WORLD, SHADES_OF_GRAY, GRAY_EDGE, SKIN }

enum class Level { HIGH, LOW, USER }

enum class Reason { NO_SCENE, LITTLE_SCENE, DISAGREE, ONE_ESTIMATOR }

enum class Refusal { TOO_BRIGHT, TOO_DARK }

/** Every answer is a unit vector in linear RGB, or null when that estimator could not answer. */
data class Estimate(
    val answers: Map<Estimator, Vec3?>,
    val combined: Vec3?,
    val disagreementDeg: Double?,
    val support: Double,
    val score: Double,
    val level: Level,
    val reason: Reason?,
)

sealed interface NeutralResult {
    data class Light(val e: Vec3) : NeutralResult
    data class Refused(val why: Refusal) : NeutralResult
}

/**
 * The light. docs/02-domain/illuminant/lld.md, and its reference
 * spike/gcm/estimate.py — change both together (D10).
 */
object Illuminant {
    const val CLIP = 0.95f
    const val DARK = 0.005f
    private const val P = 6.0
    private const val EDGE_RADIUS = 4

    private val VOTERS = listOf(Estimator.SHADES_OF_GRAY, Estimator.GRAY_EDGE, Estimator.SKIN)
    private val REGIONS = listOf(Label.BACKGROUND, Label.HAIR, Label.SKIN)

    private val KERNEL: DoubleArray = DoubleArray(7) { exp(-((it - 3) * (it - 3)) / 2.0) }
        .let { k -> val s = k.sum(); DoubleArray(7) { k[it] / s } }

    /** ColorChecker dark skin (115, 82, 68) and light skin (194, 150, 130), as mean chromaticity. */
    internal val SKIN_K: Vec3 = run {
        fun chroma(r: Int, g: Int, b: Int): Vec3 {
            val v = Vec3(Srgb.decode(r / 255.0), Srgb.decode(g / 255.0), Srgb.decode(b / 255.0))
            return v * (1.0 / (v.x + v.y + v.z))
        }
        val dark = chroma(115, 82, 68)
        val light = chroma(194, 150, 130)
        Vec3((dark.x + light.x) / 2, (dark.y + light.y) / 2, (dark.z + light.z) / 2)
    }

    fun isClipped(image: LinearImage, i: Int) = image.max(i) >= CLIP
    fun isDark(image: LinearImage, i: Int) = image.max(i) < DARK

    /** §1: background, hair or skin, neither clipped nor dark. */
    fun usable(image: LinearImage, labels: LabelMap): BooleanArray {
        require(image.width == labels.width && image.height == labels.height) {
            "image ${image.width}×${image.height} vs labels ${labels.width}×${labels.height}"
        }
        return BooleanArray(image.size) { i ->
            val role = labels.role(i)
            (role == Role.SCENE || role == Role.SKIN) && !isClipped(image, i) && !isDark(image, i)
        }
    }

    fun estimate(image: LinearImage, labels: LabelMap): Estimate {
        val nAll = image.size
        val usable = usable(image, labels)
        val n = usable.count { it }
        val support = n.toDouble() / nAll
        val none = Estimator.entries.associateWith<Estimator, Vec3?> { null }
        if (n < max(500.0, 0.02 * nAll)) {
            return Estimate(none, null, null, support, 0.0, Level.LOW, Reason.NO_SCENE)
        }

        val w = regionWeights(labels, usable, n)
        val answers = mapOf(
            Estimator.GRAY_WORLD to grayWorld(image, w),
            Estimator.SHADES_OF_GRAY to shadesOfGray(image, w),
            Estimator.GRAY_EDGE to grayEdge(image, usable, w),
            Estimator.SKIN to skinAnchor(image, labels, usable),
        )
        val voters = VOTERS.mapNotNull { answers[it] }
        if (voters.isEmpty()) return Estimate(answers, null, null, support, 0.0, Level.LOW, Reason.NO_SCENE)

        var total = Vec3(0.0, 0.0, 0.0)
        for (a in voters) total += a
        val combined = total.normalised()
        var d = 0.0
        for (i in voters.indices) for (j in i + 1 until voters.size) d = max(d, Colour.angleDeg(voters[i], voters[j]))

        val agree = (1.0 - d / 6.0).coerceIn(0.0, 1.0)
        val enough = (support / 0.15).coerceIn(0.0, 1.0)
        // One estimator alone can never be confident: there is nothing to disagree with.
        val voices = if (voters.size >= 2) 1.0 else 0.45
        val score = agree * enough * voices
        val level = if (score >= 0.5) Level.HIGH else Level.LOW
        val reason = if (level == Level.HIGH) null else when {
            support < 0.075 -> Reason.LITTLE_SCENE
            d > 3.0 -> Reason.DISAGREE
            voters.size < 2 -> Reason.ONE_ESTIMATOR
            agree <= enough -> Reason.DISAGREE
            else -> Reason.LITTLE_SCENE
        }
        return Estimate(answers, combined, d, support, score, level, reason)
    }

    /** §2: each region carries its share of the usable pixels, capped at one half. */
    private fun regionWeights(labels: LabelMap, usable: BooleanArray, n: Int): DoubleArray {
        val counts = IntArray(256)
        for (i in usable.indices) if (usable[i]) counts[labels.code(i)]++
        val perCode = DoubleArray(256)
        for (region in REGIONS) {
            val nR = counts[region.code]
            if (nR > 0) perCode[region.code] = min(nR.toDouble() / n, 0.5) / nR
        }
        return DoubleArray(usable.size) { i -> if (usable[i]) perCode[labels.code(i)] else 0.0 }
    }

    private fun grayWorld(image: LinearImage, w: DoubleArray): Vec3? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var s = 0.0
        for (i in w.indices) {
            val wi = w[i]
            if (wi == 0.0) continue
            r += wi * image.r[i]
            g += wi * image.g[i]
            b += wi * image.b[i]
            s += wi
        }
        return Vec3(r / s, g / s, b / s).normalised()
    }

    private fun shadesOfGray(image: LinearImage, w: DoubleArray): Vec3? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var s = 0.0
        for (i in w.indices) {
            val wi = w[i]
            if (wi == 0.0) continue
            r += wi * image.r[i].toDouble().pow(P)
            g += wi * image.g[i].toDouble().pow(P)
            b += wi * image.b[i].toDouble().pow(P)
            s += wi
        }
        return Vec3((r / s).pow(1 / P), (g / s).pow(1 / P), (b / s).pow(1 / P)).normalised()
    }

    private fun grayEdge(image: LinearImage, usable: BooleanArray, w: DoubleArray): Vec3? {
        val width = image.width
        val height = image.height
        val e = erode(usable, width, height, EDGE_RADIUS)
        if (e.count { it } < 200) return null
        val mr = gradientMagnitude(blur(image.r, width, height), width, height)
        val mg = gradientMagnitude(blur(image.g, width, height), width, height)
        val mb = gradientMagnitude(blur(image.b, width, height), width, height)
        var biggest = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var s = 0.0
        for (i in e.indices) {
            if (!e[i]) continue
            biggest = maxOf(biggest, mr[i], mg[i], mb[i])
            r += w[i] * mr[i].pow(P)
            g += w[i] * mg[i].pow(P)
            b += w[i] * mb[i].pow(P)
            s += w[i]
        }
        if (biggest < 1e-9) return null
        return Vec3((r / s).pow(1 / P), (g / s).pow(1 / P), (b / s).pow(1 / P)).normalised()
    }

    /** Gaussian σ = 1, radius 3, horizontal then vertical, edges clamped. */
    internal fun blur(plane: FloatArray, width: Int, height: Int): DoubleArray {
        val tmp = DoubleArray(plane.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (k in 0 until 7) acc += KERNEL[k] * plane[row + (x + k - 3).coerceIn(0, width - 1)]
                tmp[row + x] = acc
            }
        }
        val out = DoubleArray(plane.size)
        for (y in 0 until height) for (x in 0 until width) {
            var acc = 0.0
            for (k in 0 until 7) acc += KERNEL[k] * tmp[(y + k - 3).coerceIn(0, height - 1) * width + x]
            out[y * width + x] = acc
        }
        return out
    }

    /** Central differences, edges clamped. */
    internal fun gradientMagnitude(b: DoubleArray, width: Int, height: Int): DoubleArray {
        val out = DoubleArray(b.size)
        for (y in 0 until height) for (x in 0 until width) {
            val gx = (b[y * width + min(x + 1, width - 1)] - b[y * width + max(x - 1, 0)]) / 2.0
            val gy = (b[min(y + 1, height - 1) * width + x] - b[max(y - 1, 0) * width + x]) / 2.0
            out[y * width + x] = sqrt(gx * gx + gy * gy)
        }
        return out
    }

    /** Chebyshev erosion; neighbours outside the image are ignored. */
    internal fun erode(mask: BooleanArray, width: Int, height: Int, r: Int): BooleanArray {
        val h = BooleanArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var ok = true
            for (k in max(0, x - r)..min(width - 1, x + r)) if (!mask[y * width + k]) { ok = false; break }
            h[y * width + x] = ok
        }
        val out = BooleanArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            var ok = true
            for (k in max(0, y - r)..min(height - 1, y + r)) if (!h[k * width + x]) { ok = false; break }
            out[y * width + x] = ok
        }
        return out
    }

    private fun skinAnchor(image: LinearImage, labels: LabelMap, usable: BooleanArray): Vec3? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        for (i in usable.indices) {
            if (!usable[i] || labels.code(i) != Label.SKIN.code) continue
            r += image.r[i]
            g += image.g[i]
            b += image.b[i]
            count++
        }
        if (count < max(300.0, 0.005 * image.size)) return null
        return Vec3(r / count, g / count, b / count).div(SKIN_K).normalised()
    }

    /** §6: the median of a small patch around the tap, unless it is blown out or black. */
    fun neutral(image: LinearImage, u: Double, v: Double): NeutralResult {
        val w = image.width
        val h = image.height
        val cx = floor(u * w).toInt().coerceIn(0, w - 1)
        val cy = floor(v * h).toInt().coerceIn(0, h - 1)
        val r = max(2, floor(0.01 * max(w, h) + 0.5).toInt())
        val rs = ArrayList<Float>()
        val gs = ArrayList<Float>()
        val bs = ArrayList<Float>()
        var total = 0
        var clipped = 0
        var dark = 0
        for (y in max(0, cy - r)..min(h - 1, cy + r)) for (x in max(0, cx - r)..min(w - 1, cx + r)) {
            val i = y * w + x
            total++
            when {
                isClipped(image, i) -> clipped++
                isDark(image, i) -> dark++
                else -> {
                    rs += image.r[i]
                    gs += image.g[i]
                    bs += image.b[i]
                }
            }
        }
        if (2 * rs.size < total) {
            return NeutralResult.Refused(if (clipped > dark) Refusal.TOO_BRIGHT else Refusal.TOO_DARK)
        }
        val e = Vec3(median(rs), median(gs), median(bs)).normalised()
            ?: return NeutralResult.Refused(Refusal.TOO_DARK)
        return NeutralResult.Light(e)
    }

    private fun median(values: List<Float>): Double {
        val s = values.map { it.toDouble() }.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
