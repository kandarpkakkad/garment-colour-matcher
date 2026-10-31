package com.garmentcolour.engine.raw

import com.garmentcolour.engine.Bradford
import com.garmentcolour.engine.Colour
import com.garmentcolour.engine.Correction
import com.garmentcolour.engine.Mat3
import com.garmentcolour.engine.Vec3
import kotlin.math.abs
import kotlin.math.max

/**
 * The camera's own colour characterisation, from its DNG tags, by the DNG standard's
 * colour chain (DNG 1.4 §6). docs/02-domain/raw/lld.md §4. Kotlin only (D23).
 */
class CameraProfile(dng: Dng) {
    private val cm1 = dng.colorMatrix1
    private val cm2 = dng.colorMatrix2
    private val cc1 = dng.cameraCalibration1 ?: Mat3.IDENTITY
    private val cc2 = dng.cameraCalibration2 ?: cc1
    // Rows rescaled so FM · (1, 1, 1) = D50 exactly, as the DNG SDK does: phones write them
    // rounded (the OnePlus 6T's sum to 0.9609, 1, 0.8203), which tints every neutral.
    private val fm1 = dng.forwardMatrix1?.let(::normaliseForward)
    private val fm2 = dng.forwardMatrix2?.let(::normaliseForward) ?: fm1
    private val ab = dng.analogBalance?.let { Mat3.diag(it) } ?: Mat3.IDENTITY
    private val t1 = dng.illuminant1?.let(::temperatureOfIlluminant)
    private val t2 = dng.illuminant2?.let(::temperatureOfIlluminant)

    /** Two matrices, two known illuminants: interpolate. Otherwise matrix 1 for every light. */
    val interpolates: Boolean = cm2 != null && t1 != null && t2 != null && abs(t1 - t2) > 1.0

    private val tMin = if (interpolates) minOf(t1!!, t2!!) else 2000.0
    private val tMax = if (interpolates) maxOf(t1!!, t2!!) else 12000.0

    /** Weight of the illuminant-1 matrices at temperature [t]: linear in 1/t. */
    fun weight(t: Double): Double {
        if (!interpolates) return 1.0
        val w = (1 / t - 1 / t2!!) / (1 / t1!! - 1 / t2)
        return w.coerceIn(0.0, 1.0)
    }

    private fun mix(a: Mat3, b: Mat3?, g: Double): Mat3 {
        if (b == null || g >= 1.0) return a
        return Mat3(DoubleArray(9) { g * a[it / 3, it % 3] + (1 - g) * b[it / 3, it % 3] })
    }

    fun xyzToCamera(t: Double): Mat3 {
        val g = weight(t)
        return ab * (mix(cc1, cc2, g) * mix(cm1, cm2, g))
    }

    /** The temperature of the light whose neutral the camera sees as [n] (LLD §4). */
    fun temperatureOf(n: Vec3): Double {
        var x = D50_X
        var y = D50_Y
        var t = 5003.0
        repeat(20) {
            t = Colour.mccamy(x, y).let { if (it.isFinite()) it else 5003.0 }.coerceIn(tMin, tMax)
            val (nx, ny) = Colour.xy(xyzToCamera(t).inverse() * n)
            if (!nx.isFinite() || !ny.isFinite()) return t
            val moved = abs(nx - x) + abs(ny - y)
            x = nx
            y = ny
            if (moved < 1e-7) return Colour.mccamy(x, y).let { if (it.isFinite()) it else t }.coerceIn(tMin, tMax)
        }
        return t
    }

    /** Camera RGB → XYZ (D50) when [n] is the camera's neutral. `cameraToXyzD50(n) · n = D50`. */
    fun cameraToXyzD50(n: Vec3): Mat3 {
        val t = temperatureOf(n)
        val g = weight(t)
        val fm = fm1
        if (fm != null) {
            val abccInv = (ab * mix(cc1, cc2, g)).inverse()
            val ref = abccInv * n
            return mix(fm, fm2, g) * (Mat3.diag(Vec3(1 / ref.x, 1 / ref.y, 1 / ref.z)) * abccInv)
        }
        val camToXyz = xyzToCamera(t).inverse()
        val w = camToXyz * n
        val c = Bradford.cat(w * (1 / w.y), D50) * camToXyz
        val y = (c * n).y
        return Mat3(DoubleArray(9) { c[it / 3, it % 3] / y })
    }

    /** Camera RGB → linear sRGB for neutral [n]: `S(n) · n = (1, 1, 1)`. */
    fun toSrgb(n: Vec3): Mat3 = Colour.M_INV * (Bradford.cat(D50, Colour.SRGB_WHITE) * cameraToXyzD50(n))

    /** The camera's neutral under D65, max component 1. */
    val neutralD65: Vec3 = normaliseMax(xyzToCamera(6504.0) * Colour.D65)

    /**
     * The working transform T: camera RGB as a camera white-balanced for daylight renders it,
     * scaled so no unsaturated sensor value can reach 0.9 in any channel. The estimator treats
     * 0.95 as clipped (illuminant LLD §1); on RAW, clipping is decided at the sensor instead.
     */
    val toWorking: Mat3 = toSrgb(neutralD65).let { s ->
        var reach = 0.0
        for (r in 0 until 3) reach = max(reach, (0 until 3).sumOf { c -> max(s[r, c], 0.0) })
        val k = 0.9 / reach
        Mat3(DoubleArray(9) { s[it / 3, it % 3] * k })
    }
    val fromWorking: Mat3 = toWorking.inverse()

    /** The phone's own white point (LLD §4). */
    val asShotNeutral: Vec3 = dng.asShotNeutral?.takeIf { it.x > 0 && it.y > 0 && it.z > 0 }?.let(::normaliseMax)
        ?: dng.asShotWhiteXY?.let { (x, y) ->
            val xyz = Vec3(x / y, 1.0, (1 - x - y) / y)
            normaliseMax(xyzToCamera(Colour.mccamy(x, y).coerceIn(tMin, tMax)) * xyz)
        }
        ?: neutralD65

    /** The as-shot light, in the working space, unit length. */
    val asShotLight: Vec3 = (toWorking * asShotNeutral).normalised() ?: Vec3.ONES.normalised()!!

    /**
     * The camera neutral for a working-space light [e], scaled so its working luminance is 1
     * (LLD §5). A light outside the camera's gamut is pulled just inside it.
     */
    fun cameraNeutral(e: Vec3): Vec3 {
        val raw = fromWorking * e
        val m = maxOf(raw.x, raw.y, raw.z)
        val n = if (m <= 0 || !m.isFinite()) neutralD65
        else Vec3(max(raw.x, 1e-3 * m), max(raw.y, 1e-3 * m), max(raw.z, 1e-3 * m))
        val w = toWorking * n
        return n * (1 / Colour.luminance(w.x, w.y, w.z))
    }

    companion object {
        private const val D50_X = 0.3457
        private const val D50_Y = 0.3585
        val D50 = Vec3(0.96422, 1.0, 0.82521)

        private fun normaliseForward(m: Mat3): Mat3 {
            val s = m * Vec3.ONES
            return Mat3(DoubleArray(9) { m[it / 3, it % 3] * D50[it / 3] / s[it / 3] })
        }

        private fun normaliseMax(v: Vec3): Vec3 {
            val m = maxOf(v.x, v.y, v.z)
            return if (m > 0 && m.isFinite()) v * (1 / m) else Vec3.ONES
        }

        /** EXIF LightSource → colour temperature, K (LLD §4); null when unknown. */
        fun temperatureOfIlluminant(code: Int): Double? = when (code) {
            17 -> 2856.0
            18 -> 4874.0
            19 -> 6774.0
            20 -> 5503.0
            21 -> 6504.0
            22 -> 7504.0
            23 -> 5003.0
            24 -> 3200.0
            1, 4, 9 -> 5500.0
            2, 14 -> 4150.0
            3 -> 2850.0
            10 -> 6500.0
            11 -> 7500.0
            12 -> 6430.0
            13 -> 5000.0
            15 -> 3450.0
            else -> null
        }
    }
}

/**
 * RAW's correction (raw LLD §5): the DNG chain for the light's camera neutral, times the
 * exposure gain. On working-space values it is `F · T⁻¹`, so garments are measured exactly as
 * on the JPEG path; its fallback is the phone's as-shot light (D7).
 */
class RawCorrection(val profile: CameraProfile, val gain: Double) : Correction {
    /** Camera RGB → corrected linear sRGB, for the working-space light [e]. */
    fun cameraMatrix(e: Vec3): Mat3 {
        val s = profile.toSrgb(profile.cameraNeutral(e))
        return Mat3(DoubleArray(9) { gain * s[it / 3, it % 3] })
    }

    override fun matrix(e: Vec3): Mat3 = cameraMatrix(e) * profile.fromWorking

    override val fallback: Vec3 get() = profile.asShotLight
}
