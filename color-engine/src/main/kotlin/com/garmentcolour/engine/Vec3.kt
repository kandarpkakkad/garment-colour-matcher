package com.garmentcolour.engine

import kotlin.math.sqrt

/** A colour, a white or an illuminant: three doubles. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(o: Vec3) = Vec3(x / o.x, y / o.y, z / o.z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(this dot this)
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    /** Unit length, or null for a zero or non-finite vector — an estimator that cannot answer. */
    fun normalised(): Vec3? {
        if (!isFinite()) return null
        val n = norm()
        if (!n.isFinite() || n <= 0.0) return null
        return Vec3(x / n, y / n, z / n)
    }

    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 has no component $i")
    }

    companion object {
        val ONES = Vec3(1.0, 1.0, 1.0)
    }
}

/** A 3×3 matrix, row-major. */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9) { "a 3×3 matrix needs 9 values, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int) = m[row * 3 + col]

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            out[r * 3 + c] = m[r * 3] * o[0, c] + m[r * 3 + 1] * o[1, c] + m[r * 3 + 2] * o[2, c]
        }
        return Mat3(out)
    }

    /** The cofactor inverse — the same formula as `inv3` in spike/gcm/colour.py (D10). */
    fun inverse(): Mat3 {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val ca = e * i - f * h
        val cb = -(d * i - f * g)
        val cc = d * h - e * g
        val det = a * ca + b * cb + c * cc
        val adj = doubleArrayOf(
            ca, -(b * i - c * h), b * f - c * e,
            cb, a * i - c * g, -(a * f - c * d),
            cc, -(a * h - b * g), a * e - b * d,
        )
        return Mat3(DoubleArray(9) { adj[it] / det })
    }

    fun toDoubleArray(): DoubleArray = m.copyOf()

    override fun toString() = m.joinToString(prefix = "Mat3(", postfix = ")")

    companion object {
        fun of(vararg v: Double) = Mat3(v.copyOf())
        fun diag(v: Vec3) = of(v.x, 0.0, 0.0, 0.0, v.y, 0.0, 0.0, 0.0, v.z)
        val IDENTITY = of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}
