package com.garmentcolour.engine

enum class Method { ESTIMATED, NEUTRAL_TAP, NONE }

data class Analysis(
    val estimate: Estimate,
    /** The light the correction used. */
    val applied: Vec3,
    val method: Method,
    val matrix: Mat3,
    val garments: List<Garment>,
)

/**
 * How a light in the working space becomes a correction. Pipeline LLD §1: Bradford in
 * linear sRGB for JPEGs; the DNG colour chain for RAW (raw LLD §5).
 */
interface Correction {
    /** Working-space values → corrected linear sRGB, for the light [e]. */
    fun matrix(e: Vec3): Mat3

    /** The light used when there is no estimate: "as the phone made it" (D7). */
    val fallback: Vec3
}

/** The JPEG path (D4). Its fallback, white, gives the identity. */
object SrgbCorrection : Correction {
    override fun matrix(e: Vec3) = Bradford.correctionMatrix(e)
    override val fallback: Vec3 = Vec3.ONES.normalised()!!
}

/** The one entry point the app calls. Pipeline LLD §1; reference spike/gcm/engine.py. */
object Engine {
    /** Stored on every capture. Bump when the same photo would give different numbers. */
    const val VERSION = 1

    /**
     * Estimates the light (always, even with an [override], so the estimate a tap replaced
     * is kept), builds the correction, and measures the garments. The fixtures run with the
     * default [SrgbCorrection].
     */
    fun analyse(
        image: LinearImage,
        labels: LabelMap,
        override: Vec3? = null,
        correction: Correction = SrgbCorrection,
    ): Analysis {
        val estimate = Illuminant.estimate(image, labels)
        val (applied, method) = when {
            override != null -> (override.normalised() ?: throw IllegalArgumentException("override $override has no direction")) to
                Method.NEUTRAL_TAP
            estimate.combined != null -> estimate.combined to Method.ESTIMATED
            // No usable scene: the photo as the phone made it (D7).
            else -> correction.fallback to Method.NONE
        }
        val matrix = correction.matrix(applied)
        return Analysis(estimate, applied, method, matrix, GarmentMeter.measure(image, labels, matrix))
    }
}

/**
 * Applies a correction matrix to packed ARGB pixels in place, through the sRGB tables
 * (colour LLD §1, pipeline LLD §6). Alpha is kept.
 */
class Corrector(matrix: Mat3) {
    private val m = FloatArray(9) { matrix[it / 3, it % 3].toFloat() }

    /** The identity leaves pixels exactly as they were rather than one table step off. */
    val isIdentity: Boolean = (0 until 9).all { kotlin.math.abs(matrix[it / 3, it % 3] - (if (it % 4 == 0) 1.0 else 0.0)) < 1e-9 }

    fun apply(pixels: IntArray, offset: Int = 0, count: Int = pixels.size - offset) {
        if (isIdentity) return
        val d = Srgb.DECODE
        for (i in offset until offset + count) {
            val p = pixels[i]
            val r = d[(p shr 16) and 0xFF]
            val g = d[(p shr 8) and 0xFF]
            val b = d[p and 0xFF]
            val r2 = m[0] * r + m[1] * g + m[2] * b
            val g2 = m[3] * r + m[4] * g + m[5] * b
            val b2 = m[6] * r + m[7] * g + m[8] * b
            pixels[i] = (p and ALPHA) or (Srgb.encodeFast(r2) shl 16) or (Srgb.encodeFast(g2) shl 8) or Srgb.encodeFast(b2)
        }
    }

    private companion object {
        const val ALPHA = 0xFF000000.toInt()
    }
}

/**
 * `est_answers` in the database: `NAME r g b` or `NAME -`, `;`-separated, in
 * [Estimator] order. Storage schema §4.
 */
object EstimateCodec {
    fun encode(answers: Map<Estimator, Vec3?>): String = Estimator.entries.joinToString(";") { k ->
        val v = answers[k]
        if (v == null) "${k.name} -" else "${k.name} ${num(v.x)} ${num(v.y)} ${num(v.z)}"
    }

    fun decode(text: String): Map<Estimator, Vec3?> {
        val out = Estimator.entries.associateWith<Estimator, Vec3?> { null }.toMutableMap()
        for (part in text.split(';')) {
            val f = part.trim().split(' ').filter { it.isNotEmpty() }
            val k = Estimator.entries.firstOrNull { it.name == f.firstOrNull() } ?: continue
            if (f.size == 4) {
                val v = Vec3(f[1].toDoubleOrNull() ?: continue, f[2].toDoubleOrNull() ?: continue, f[3].toDoubleOrNull() ?: continue)
                out[k] = v
            }
        }
        return out
    }

    private fun num(x: Double) = "%.6f".format(java.util.Locale.ROOT, x)
}
