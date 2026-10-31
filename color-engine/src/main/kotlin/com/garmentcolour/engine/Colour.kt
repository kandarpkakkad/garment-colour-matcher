package com.garmentcolour.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The sRGB transfer curve and its tables. docs/02-domain/colour/lld.md §1. */
object Srgb {
    fun decode(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun encode(linear: Double): Double {
        val v = linear.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    /** floor(x + 0.5), as the spike does: Python's round() is banker's rounding. */
    fun to8(linear: Double): Int = floor(255.0 * encode(linear) + 0.5).toInt()

    /** 8-bit sRGB → linear. */
    val DECODE: FloatArray = FloatArray(256) { decode(it / 255.0).toFloat() }

    private val ENCODE: IntArray = IntArray(4096) { to8(it / 4095.0) }

    /** Linear → 8-bit through the 4096-entry table: at most one level from [to8]. */
    fun encodeFast(linear: Float): Int {
        val v = if (linear > 1f) 1f else if (linear > 0f) linear else 0f   // NaN → 0
        return ENCODE[(v * 4095f + 0.5f).toInt()]
    }
}

data class Lab(val l: Double, val a: Double, val b: Double)

/** Spaces, differences, display values. Colour LLD §2, §4. */
object Colour {
    val M = Mat3.of(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    val M_INV = M.inverse()
    val D65 = Vec3(0.95047, 1.0, 1.08883)

    /**
     * The white the sRGB matrix itself defines. It differs from [D65] in the seventh
     * digit; adapting to it keeps a neutral exactly neutral and a white light exactly
     * the identity. Lab still uses [D65].
     */
    val SRGB_WHITE = M * Vec3.ONES

    fun luminance(r: Double, g: Double, b: Double) = r * 0.2126729 + g * 0.7151522 + b * 0.0721750

    fun xyzToLab(xyz: Vec3, white: Vec3 = D65): Lab {
        val d = 6.0 / 29.0
        fun f(t: Double) = if (t > d * d * d) cbrt(t) else t / (3 * d * d) + 4.0 / 29.0
        val fx = f(xyz.x / white.x)
        val fy = f(xyz.y / white.y)
        val fz = f(xyz.z / white.z)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun labOfLinear(rgb: Vec3) = xyzToLab(M * rgb)

    /** xy chromaticity of an XYZ colour. */
    fun xy(xyz: Vec3): Pair<Double, Double> {
        val s = xyz.x + xyz.y + xyz.z
        return xyz.x / s to xyz.y / s
    }

    /** McCamy's approximation, for display only; null outside 1500–25000 K. */
    fun cct(x: Double, y: Double): Double? = mccamy(x, y).takeIf { it.isFinite() && it in 1500.0..25000.0 }

    /** McCamy's cubic, unbounded — for interpolating camera matrices (raw LLD §4). */
    fun mccamy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
    }

    /** `#RRGGBB` of a linear colour, clamped to [0, 1]. */
    fun hex(rgb: Vec3): String =
        "#%02X%02X%02X".format(Srgb.to8(rgb.x), Srgb.to8(rgb.y), Srgb.to8(rgb.z))

    fun angleDeg(a: Vec3, b: Vec3): Double {
        val c = (a dot b) / (sqrt(a dot a) * sqrt(b dot b))
        return acos(c.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** CIEDE2000 — Sharma, Wu and Dalal (2005), kL = kC = kH = 1. */
    fun deltaE2000(p: Lab, q: Lab): Double {
        fun rad(deg: Double) = deg * PI / 180.0
        fun deg(rad: Double) = rad * 180.0 / PI
        fun hue(b: Double, a: Double) = if (a == 0.0 && b == 0.0) 0.0 else ((deg(atan2(b, a)) % 360) + 360) % 360

        val c1 = hypot(p.a, p.b)
        val c2 = hypot(q.a, q.b)
        val cb = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cb.pow(7) / (cb.pow(7) + 25.0.pow(7))))
        val a1p = (1 + g) * p.a
        val a2p = (1 + g) * q.a
        val c1p = hypot(a1p, p.b)
        val c2p = hypot(a2p, q.b)
        val h1p = hue(p.b, a1p)
        val h2p = hue(q.b, a2p)

        val dlp = q.l - p.l
        val dcp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            h2p - h1p > 180 -> h2p - h1p - 360
            h2p - h1p < -180 -> h2p - h1p + 360
            else -> h2p - h1p
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(rad(dhp / 2))

        val lbp = (p.l + q.l) / 2
        val cbp = (c1p + c2p) / 2
        val hbp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * cos(rad(hbp - 30)) + 0.24 * cos(rad(2 * hbp)) +
            0.32 * cos(rad(3 * hbp + 6)) - 0.20 * cos(rad(4 * hbp - 63))
        val dTheta = 30 * exp(-((hbp - 275) / 25).pow(2))
        val rc = 2 * sqrt(cbp.pow(7) / (cbp.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lbp - 50).pow(2) / sqrt(20 + (lbp - 50).pow(2))
        val sc = 1 + 0.045 * cbp
        val sh = 1 + 0.015 * cbp * t
        val rt = -sin(rad(2 * dTheta)) * rc
        return sqrt(
            (dlp / sl).pow(2) + (dcp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dcp / sc) * (dHp / sh),
        )
    }
}

/** Chromatic adaptation and the correction matrix. Colour LLD §3, D4. */
object Bradford {
    val B = Mat3.of(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val B_INV = B.inverse()

    fun cat(ws: Vec3, wd: Vec3): Mat3 = (B_INV * Mat3.diag((B * wd) / (B * ws))) * B

    /**
     * `C = M⁻¹ · CAT(scene white → sRGB's D65) · M`. The scene white is scaled to the
     * target's luminance, so a grey keeps its luminance; `e = (1, 1, 1)` is the identity.
     */
    fun correctionMatrix(e: Vec3): Mat3 {
        val xs = Colour.M * e
        val scene = xs * (Colour.SRGB_WHITE.y / xs.y)
        return Colour.M_INV * (cat(scene, Colour.SRGB_WHITE) * Colour.M)
    }
}
