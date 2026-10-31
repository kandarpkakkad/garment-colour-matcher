package com.garmentcolour.engine

import kotlin.math.floor
import kotlin.math.max

/** One garment's measured colour under D65 (D18: measurement, never a name). */
data class Garment(
    val label: Label,
    val fraction: Double,
    val bounds: Box,
    /** Corrected linear RGB. */
    val linear: Vec3,
    val xyz: Vec3,
    val lab: Lab,
    val hex: String,
    /** Mostly overexposed: measured anyway, flagged unreliable. */
    val clipped: Boolean,
)

/** docs/02-domain/garments/lld.md; reference spike/gcm/garments.py. */
object GarmentMeter {
    fun measure(image: LinearImage, labels: LabelMap, matrix: Mat3): List<Garment> {
        val w = image.width
        val h = image.height
        val nAll = image.size
        val out = ArrayList<Garment>()
        for (label in Label.GARMENTS) {
            val p = ArrayList<Int>()
            for (i in 0 until nAll) if (labels.code(i) == label.code) p += i
            if (p.isEmpty() || p.size < max(30.0, 0.005 * nAll)) continue

            var xmin = w
            var ymin = h
            var xmax = -1
            var ymax = -1
            for (i in p) {
                val x = i % w
                val y = i / w
                if (x < xmin) xmin = x
                if (x > xmax) xmax = x
                if (y < ymin) ymin = y
                if (y > ymax) ymax = y
            }
            val bounds = Box(xmin.toDouble() / w, ymin.toDouble() / h, (xmax + 1.0) / w, (ymax + 1.0) / h)

            var q = p.filter { !Illuminant.isClipped(image, it) && !Illuminant.isDark(image, it) }
            val clipped: Boolean
            if (q.size >= max(30.0, 0.5 * p.size)) {
                clipped = false
            } else {
                q = p.filter { !Illuminant.isDark(image, it) }
                clipped = true
            }
            if (q.isEmpty()) continue

            // Trim the deepest shadows and brightest highlights: the middle 80 % of luminance.
            val y = DoubleArray(q.size) { k ->
                val i = q[k]
                Colour.luminance(image.r[i].toDouble(), image.g[i].toDouble(), image.b[i].toDouble())
            }
            val sorted = y.sortedArray()
            val lo = sorted[floor(0.10 * (sorted.size - 1)).toInt()]
            val hi = sorted[floor(0.90 * (sorted.size - 1)).toInt()]
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var n = 0
            for (k in q.indices) {
                if (y[k] < lo || y[k] > hi) continue
                val i = q[k]
                r += image.r[i]
                g += image.g[i]
                b += image.b[i]
                n++
            }
            // Measure first, then correct: the correction is linear (D4), so this is the
            // corrected mean without the per-pixel clipping of the encode.
            val c = matrix * Vec3(r / n, g / n, b / n)
            val rgb = Vec3(max(c.x, 0.0), max(c.y, 0.0), max(c.z, 0.0))
            val xyz = Colour.M * rgb
            out += Garment(label, p.size.toDouble() / nAll, bounds, rgb, xyz, Colour.xyzToLab(xyz), Colour.hex(rgb), clipped)
        }
        return out.sortedWith(compareByDescending<Garment> { it.fraction }.thenBy { it.label.code })
    }
}
