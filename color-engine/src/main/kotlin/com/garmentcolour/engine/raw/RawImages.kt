package com.garmentcolour.engine.raw

import com.garmentcolour.engine.BoxDownscaler
import com.garmentcolour.engine.Illuminant
import com.garmentcolour.engine.LinearImage
import com.garmentcolour.engine.Mat3
import com.garmentcolour.engine.Srgb
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Bayer samples as linear values 0…65534 (×1/65534), with 65535 meaning "the sensor clipped
 * here". Made by [RawImages.linearize], which reuses the DNG's own sample array.
 */
class LinearRaw(
    val width: Int,
    val height: Int,
    val cfa: IntArray,
    val data: ShortArray,
    val cropX: Int,
    val cropY: Int,
    val cropW: Int,
    val cropH: Int,
    val orientation: Int,
)

/** Output (display) coordinates → stored crop coordinates, for TIFF orientations 1–8 (raw LLD §8). */
object Orientation {
    fun swapsAxes(tag: Int) = tag in 5..8

    /** Output size of a stored [w] × [h] image. */
    fun outputSize(tag: Int, w: Int, h: Int) = if (swapsAxes(tag)) h to w else w to h

    fun sx(tag: Int, ox: Int, oy: Int, w: Int): Int = when (tag) {
        2, 3 -> w - 1 - ox
        5, 6 -> oy
        7, 8 -> w - 1 - oy
        else -> ox
    }

    fun sy(tag: Int, ox: Int, oy: Int, h: Int): Int = when (tag) {
        3, 4 -> h - 1 - oy
        5, 8 -> ox
        6, 7 -> h - 1 - ox
        else -> oy
    }
}

/** docs/02-domain/raw/lld.md §2–§8. Kotlin only (D23). */
object RawImages {
    /** D14: full-resolution demosaic up to this many crop pixels; one pixel per quad above. */
    const val FULL_LIMIT = 25_000_000L
    private const val SATURATED = 0xFFFF
    private const val SCALE = 65534f
    private val WHITE_PIXEL = 0xFFFFFFFF.toInt()

    /** §2: black, white, saturation, gain map. Destroys [dng]'s samples (reuses the array). */
    fun linearize(dng: Dng): LinearRaw {
        val w = dng.width
        val h = dng.height
        val data = dng.raw
        val maps = dng.gainMaps
        for (y in 0 until h) {
            val relV = (y + 0.5) / h
            val py = 2 * (y and 1)
            for (x in 0 until w) {
                val i = y * w + x
                val p = py + (x and 1)
                val black = dng.black[p]
                var lin = ((data[i].toInt() and 0xFFFF) - black) / (dng.white - black)
                if (lin >= 0.98f) {
                    data[i] = SATURATED.toShort()
                    continue
                }
                for (m in maps) if (m.covers(x, y)) lin *= m.gain(relV, (x + 0.5) / w)
                data[i] = (lin.coerceIn(0f, 1f) * SCALE + 0.5f).toInt().toShort()
            }
        }
        return LinearRaw(w, h, dng.cfa, data, dng.cropX, dng.cropY, dng.cropW, dng.cropH, dng.orientation)
    }

    fun halfSize(raw: LinearRaw) = raw.cropW.toLong() * raw.cropH > FULL_LIMIT

    /** The rendered image's size, oriented, and whether it is half-size. */
    fun outputSize(raw: LinearRaw): Triple<Int, Int, Boolean> {
        val half = halfSize(raw)
        val w = if (half) raw.cropW / 2 else raw.cropW
        val h = if (half) raw.cropH / 2 else raw.cropH
        val (ow, oh) = Orientation.outputSize(raw.orientation, w, h)
        return Triple(ow, oh, half)
    }

    /**
     * Per CFA position and colour, the neighbour offsets (dx, dy pairs) within ±1 whose CFA
     * colour is that colour — the bilinear demosaic's sources. Own colour: just (0, 0).
     */
    private fun neighbours(cfa: IntArray): Array<Array<IntArray>> = Array(4) { p ->
        val py = p / 2
        val px = p % 2
        Array(3) { c ->
            if (cfa[p] == c) {
                intArrayOf(0, 0)
            } else {
                val out = ArrayList<Int>()
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val q = 2 * ((py + dy) and 1) + ((px + dx) and 1)
                    if (cfa[q] == c) { out += dx; out += dy }
                }
                out.toIntArray()
            }
        }
    }

    /** §7: the working image — camera RGB through T, saturated blocks clipped, oriented. */
    fun working(raw: LinearRaw, profile: CameraProfile): LinearImage {
        val x0 = raw.cropX and 1.inv()
        val y0 = raw.cropY and 1.inv()
        val qw = max(1, (raw.cropW - (raw.cropX - x0)) / 2)
        val qh = max(1, (raw.cropH - (raw.cropY - y0)) / 2)
        // Sized from the quads, not the crop: a block must hold at least one quad.
        val (ow, oh) = Orientation.outputSize(raw.orientation, qw, qh)
        val (ww, wh) = BoxDownscaler.workingSize(ow, oh)
        val (uw, uh) = if (Orientation.swapsAxes(raw.orientation)) wh to ww else ww to wh

        val sum = DoubleArray(uw * uh * 3)
        val count = IntArray(uw * uh)
        val saturated = BooleanArray(uw * uh)
        val colourSum = FloatArray(3)
        val colourN = IntArray(3)
        for (qy in 0 until qh) {
            val by = (qy.toLong() * uh / qh).toInt()
            for (qx in 0 until qw) {
                val bx = (qx.toLong() * uw / qw).toInt()
                val j = by * uw + bx
                colourSum.fill(0f)
                colourN.fill(0)
                var sat = false
                for (k in 0 until 4) {
                    val x = x0 + 2 * qx + (k and 1)
                    val y = y0 + 2 * qy + (k shr 1)
                    if (x >= raw.width || y >= raw.height) continue
                    val v = raw.data[y * raw.width + x].toInt() and 0xFFFF
                    if (v == SATURATED) sat = true
                    val c = raw.cfa[2 * (y and 1) + (x and 1)]
                    colourSum[c] += v / SCALE
                    colourN[c]++
                }
                if (sat) saturated[j] = true
                for (c in 0 until 3) if (colourN[c] > 0) sum[3 * j + c] += (colourSum[c] / colourN[c]).toDouble()
                count[j]++
            }
        }

        val t = profile.toWorking
        val ur = FloatArray(uw * uh)
        val ug = FloatArray(uw * uh)
        val ub = FloatArray(uw * uh)
        for (j in 0 until uw * uh) {
            if (saturated[j] || count[j] == 0) {
                // Clipped: never votes, never measured as a colour (illuminant LLD §1).
                ur[j] = 1f; ug[j] = 1f; ub[j] = 1f
                continue
            }
            val r = sum[3 * j] / count[j]
            val g = sum[3 * j + 1] / count[j]
            val b = sum[3 * j + 2] / count[j]
            ur[j] = (t[0, 0] * r + t[0, 1] * g + t[0, 2] * b).toFloat()
            ug[j] = (t[1, 0] * r + t[1, 1] * g + t[1, 2] * b).toFloat()
            ub[j] = (t[2, 0] * r + t[2, 1] * g + t[2, 2] * b).toFloat()
        }

        val n = ww * wh
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (oy in 0 until wh) for (ox in 0 until ww) {
            val sx = Orientation.sx(raw.orientation, ox, oy, uw)
            val sy = Orientation.sy(raw.orientation, ox, oy, uh)
            val s = sy * uw + sx
            val o = oy * ww + ox
            r[o] = ur[s]; g[o] = ug[s]; b[o] = ub[s]
        }
        return LinearImage(ww, wh, r, g, b)
    }

    /** §6: 2^BaselineExposure · clamp(0.9 / p99, 0.5, 8) over unclipped working pixels. */
    fun exposureGain(working: LinearImage, baselineExposure: Double): Double {
        val maxes = ArrayList<Float>(working.size)
        for (i in 0 until working.size) {
            val m = working.max(i)
            if (m < Illuminant.CLIP && m > 0f) maxes += m
        }
        val base = 2.0.pow(baselineExposure)
        if (maxes.isEmpty()) return base
        maxes.sort()
        val p99 = maxes[floor(0.99 * (maxes.size - 1)).toInt()].toDouble()
        return base * (0.9 / p99).coerceIn(0.5, 8.0)
    }

    /** 8-bit ARGB of `matrix · px` for each working pixel — the segmenters' input (§7). */
    fun preview(working: LinearImage, matrix: Mat3): IntArray {
        val m = FloatArray(9) { matrix[it / 3, it % 3].toFloat() }
        return IntArray(working.size) { i ->
            val r = working.r[i]
            val g = working.g[i]
            val b = working.b[i]
            if (r >= 1f && g >= 1f && b >= 1f) return@IntArray WHITE_PIXEL
            (0xFF shl 24) or
                (Srgb.encodeFast(m[0] * r + m[1] * g + m[2] * b) shl 16) or
                (Srgb.encodeFast(m[3] * r + m[4] * g + m[5] * b) shl 8) or
                Srgb.encodeFast(m[6] * r + m[7] * g + m[8] * b)
        }
    }

    /**
     * §8: output rows [rowFrom, rowTo) of the oriented image, as ARGB, through [f] (camera
     * RGB → corrected linear sRGB). Saturated pixels are white (D23). [out] holds
     * `(rowTo − rowFrom) · width` pixels. Row ranges are independent: render them in parallel.
     */
    fun renderRows(raw: LinearRaw, f: Mat3, rowFrom: Int, rowTo: Int, out: IntArray) {
        val (ow, _, half) = outputSize(raw)
        val sw = if (half) raw.cropW / 2 else raw.cropW
        val sh = if (half) raw.cropH / 2 else raw.cropH
        val m = FloatArray(9) { f[it / 3, it % 3].toFloat() }
        val nb = neighbours(raw.cfa)
        val rgb = FloatArray(3)
        val x0 = raw.cropX and 1.inv()
        val y0 = raw.cropY and 1.inv()
        for (oy in rowFrom until rowTo) {
            val base = (oy - rowFrom) * ow
            for (ox in 0 until ow) {
                val sx = Orientation.sx(raw.orientation, ox, oy, sw)
                val sy = Orientation.sy(raw.orientation, ox, oy, sh)
                val saturated = if (half) quad(raw, x0 + 2 * sx, y0 + 2 * sy, rgb) else pixel(raw, nb, raw.cropX + sx, raw.cropY + sy, rgb)
                out[base + ox] = if (saturated) WHITE_PIXEL else
                    (0xFF shl 24) or
                        (Srgb.encodeFast(m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]) shl 16) or
                        (Srgb.encodeFast(m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]) shl 8) or
                        Srgb.encodeFast(m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2])
            }
        }
    }

    /** Bilinear demosaic at raw (x, y) into [rgb]; true if a sample it used was clipped (§3). */
    private fun pixel(raw: LinearRaw, nb: Array<Array<IntArray>>, x: Int, y: Int, rgb: FloatArray): Boolean {
        val p = 2 * (y and 1) + (x and 1)
        var sat = false
        for (c in 0 until 3) {
            val offs = nb[p][c]
            var s = 0f
            var n = 0
            var k = 0
            while (k < offs.size) {
                val xx = x + offs[k]
                val yy = y + offs[k + 1]
                k += 2
                if (xx < 0 || yy < 0 || xx >= raw.width || yy >= raw.height) continue
                val v = raw.data[yy * raw.width + xx].toInt() and 0xFFFF
                if (v == SATURATED) sat = true
                s += v
                n++
            }
            rgb[c] = if (n > 0) s / n / SCALE else 0f
        }
        return sat
    }

    /** One pixel per 2 × 2 quad at even raw (x, y) (§3). */
    private fun quad(raw: LinearRaw, x: Int, y: Int, rgb: FloatArray): Boolean {
        var sat = false
        val s = FloatArray(3)
        val n = IntArray(3)
        for (k in 0 until 4) {
            val xx = min(x + (k and 1), raw.width - 1)
            val yy = min(y + (k shr 1), raw.height - 1)
            val v = raw.data[yy * raw.width + xx].toInt() and 0xFFFF
            if (v == SATURATED) sat = true
            val c = raw.cfa[2 * (yy and 1) + (xx and 1)]
            s[c] += v.toFloat()
            n[c]++
        }
        for (c in 0 until 3) rgb[c] = if (n[c] > 0) s[c] / n[c] / SCALE else 0f
        return sat
    }
}
