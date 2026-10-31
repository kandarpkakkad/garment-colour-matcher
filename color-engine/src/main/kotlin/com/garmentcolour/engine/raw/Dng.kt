package com.garmentcolour.engine.raw

import com.garmentcolour.engine.Mat3
import com.garmentcolour.engine.Vec3
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.floor
import kotlin.math.min

/** A DNG this reader cannot use, named by what is unsupported. Raw LLD §1. */
class DngException(message: String) : IOException(message)

/** One `GainMap` opcode from `OpcodeList2` (raw LLD §2). */
class GainMap(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val rowPitch: Int,
    val colPitch: Int,
    val pointsV: Int,
    val pointsH: Int,
    val spacingV: Double,
    val spacingH: Double,
    val originV: Double,
    val originH: Double,
    val mapPlanes: Int,
    val gains: FloatArray,
) {
    fun covers(x: Int, y: Int) =
        y in top until bottom && x in left until right && (y - top) % rowPitch == 0 && (x - left) % colPitch == 0

    /** Bilinear in the map at relative image position (v, h), clamped to the map; plane 0. */
    fun gain(v: Double, h: Double): Float {
        val iv = if (pointsV > 1) ((v - originV) / spacingV).coerceIn(0.0, pointsV - 1.0) else 0.0
        val ih = if (pointsH > 1) ((h - originH) / spacingH).coerceIn(0.0, pointsH - 1.0) else 0.0
        val v0 = floor(iv).toInt()
        val h0 = floor(ih).toInt()
        val v1 = min(v0 + 1, pointsV - 1)
        val h1 = min(h0 + 1, pointsH - 1)
        val fv = (iv - v0).toFloat()
        val fh = (ih - h0).toFloat()
        fun g(r: Int, c: Int) = gains[(r * pointsH + c) * mapPlanes]
        val top = (1 - fh) * g(v0, h0) + fh * g(v0, h1)
        val bottom = (1 - fh) * g(v1, h0) + fh * g(v1, h1)
        return (1 - fv) * top + fv * bottom
    }
}

/**
 * The raw image and the tags the colour pipeline needs, from the DNGs Android's
 * `DngCreator` writes (D23). docs/02-domain/raw/lld.md §1.
 */
class Dng(
    val width: Int,
    val height: Int,
    /** Unsigned 16-bit samples, row-major. `RawImages.linearize` reuses this array. */
    val raw: ShortArray,
    /** Colour (0 R, 1 G, 2 B) at CFA position `2·(y mod 2) + (x mod 2)`. */
    val cfa: IntArray,
    /** Black level per CFA position. */
    val black: FloatArray,
    val white: Float,
    /** The default crop, in absolute raw coordinates. */
    val cropX: Int,
    val cropY: Int,
    val cropW: Int,
    val cropH: Int,
    val orientation: Int,
    val colorMatrix1: Mat3,
    val colorMatrix2: Mat3?,
    val cameraCalibration1: Mat3?,
    val cameraCalibration2: Mat3?,
    val forwardMatrix1: Mat3?,
    val forwardMatrix2: Mat3?,
    val analogBalance: Vec3?,
    val asShotNeutral: Vec3?,
    val asShotWhiteXY: Pair<Double, Double>?,
    val illuminant1: Int?,
    val illuminant2: Int?,
    val baselineExposure: Double,
    val gainMaps: List<GainMap>,
    val make: String?,
    val model: String?,
) {
    companion object {
        private const val CFA_PHOTOMETRIC = 32803

        fun read(file: File): Dng {
            val buffer = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
            return read(buffer)
        }

        fun read(bytes: ByteArray): Dng = read(ByteBuffer.wrap(bytes))

        fun read(source: ByteBuffer): Dng {
            val buf = source.duplicate()
            if (buf.limit() < 8) throw DngException("file too short to be a DNG")
            buf.order(
                when (val bo = buf.getShort(0).toInt() and 0xFFFF) {
                    0x4949 -> ByteOrder.LITTLE_ENDIAN
                    0x4D4D -> ByteOrder.BIG_ENDIAN
                    else -> throw DngException("not a TIFF file (byte order 0x${bo.toString(16)})")
                },
            )
            if (buf.getShort(2).toInt() != 42) throw DngException("not a TIFF file (BigTIFF and others are not supported)")
            val tiff = Tiff(buf)
            val ifd0 = tiff.ifd(tiff.u32(4))
            val all = ArrayList<Map<Int, Tiff.Entry>>()
            fun walk(ifd: Map<Int, Tiff.Entry>, depth: Int) {
                all += ifd
                if (depth >= 3) return
                ifd[330]?.let { e ->
                    for (sub in tiff.longs(e)) {
                        // A broken thumbnail or preview IFD must not cost the raw image.
                        val child = try { tiff.ifd(sub) } catch (_: DngException) { continue }
                        walk(child, depth + 1)
                    }
                }
            }
            walk(ifd0, 0)
            val rawIfd = all.firstOrNull { ifd ->
                tiff.int(ifd, 262) == CFA_PHOTOMETRIC && (tiff.int(ifd, 254) ?: 0) == 0
            } ?: throw DngException(
                "no Bayer (CFA) image in this DNG" +
                    (tiff.int(ifd0, 262)?.let { " — its main image has PhotometricInterpretation $it" } ?: ""),
            )
            return parse(tiff, rawIfd, ifd0)
        }

        private fun parse(t: Tiff, raw: Map<Int, Tiff.Entry>, ifd0: Map<Int, Tiff.Entry>): Dng {
            fun tag(id: Int) = raw[id] ?: ifd0[id]
            fun require(id: Int, name: String, ok: (Int) -> Boolean) {
                val v = t.int(raw, id) ?: throw DngException("$name is missing")
                if (!ok(v)) throw DngException("$name $v is not supported")
            }
            require(259, "Compression") { it == 1 }
            require(258, "BitsPerSample") { it == 16 }
            (t.int(raw, 277) ?: 1).let { if (it != 1) throw DngException("SamplesPerPixel $it is not supported") }
            if (raw[324] != null) throw DngException("tiled DNGs are not supported")
            val width = t.int(raw, 256) ?: throw DngException("ImageWidth is missing")
            val height = t.int(raw, 257) ?: throw DngException("ImageLength is missing")
            if (width < 2 || height < 2 || width.toLong() * height > 200_000_000L) throw DngException("image size $width × $height")

            val dim = raw[33421]?.let { t.longs(it) } ?: throw DngException("CFARepeatPatternDim is missing")
            if (dim.size != 2 || dim[0] != 2L || dim[1] != 2L) throw DngException("CFA pattern ${dim.joinToString("×")} is not supported")
            val pattern = raw[33422]?.let { t.longs(it) } ?: throw DngException("CFAPattern is missing")
            if (pattern.size != 4 || pattern.any { it !in 0L..2L }) throw DngException("CFAPattern ${pattern.joinToString()} is not supported")
            raw[50710]?.let { e ->
                val planes = t.longs(e)
                if (planes.toList() != listOf(0L, 1L, 2L)) throw DngException("CFAPlaneColor ${planes.joinToString()} is not supported")
            }
            val cfa = IntArray(4) { pattern[it].toInt() }

            val samples = ShortArray(width * height)
            val offsets = raw[273]?.let { t.longs(it) } ?: throw DngException("StripOffsets is missing")
            val counts = raw[279]?.let { t.longs(it) } ?: throw DngException("StripByteCounts is missing")
            if (offsets.size != counts.size) throw DngException("strip offsets and counts disagree")
            var at = 0
            for (i in offsets.indices) {
                val n = min((counts[i] / 2).toInt(), samples.size - at)
                if (n <= 0) break
                val start = offsets[i].toInt()
                if (start < 0 || start + 2L * n > t.buf.limit()) throw DngException("strip $i lies outside the file")
                val strip = t.buf.duplicate().order(t.buf.order())
                strip.position(start)
                strip.slice().order(t.buf.order()).asShortBuffer().get(samples, at, n)
                at += n
            }
            if (at < samples.size) throw DngException("the strips hold $at of ${samples.size} samples")

            val blackDim = tag(50713)?.let { t.longs(it) }?.map { it.toInt() } ?: listOf(1, 1)
            val blackValues = tag(50714)?.let { t.doubles(it) } ?: doubleArrayOf(0.0)
            val (br, bc) = blackDim[0] to blackDim.getOrElse(1) { 1 }
            if (br !in 1..2 || bc !in 1..2) throw DngException("BlackLevelRepeatDim $br×$bc is not supported")
            val black = FloatArray(4) { p ->
                val i = ((p / 2) % br) * bc + (p % 2) % bc
                blackValues.getOrElse(i) { blackValues[0] }.toFloat()
            }
            val white = (tag(50717)?.let { t.doubles(it).firstOrNull() } ?: 65535.0).toFloat()
            if (black.any { it >= white }) throw DngException("black level reaches the white level")

            val active = tag(50829)?.let { t.longs(it) }?.map { it.toInt() } ?: listOf(0, 0, height, width)
            val (aTop, aLeft) = active[0] to active[1]
            val aW = active[3] - active[1]
            val aH = active[2] - active[0]
            val origin = tag(50719)?.let { t.doubles(it) } ?: doubleArrayOf(0.0, 0.0)
            val size = tag(50720)?.let { t.doubles(it) } ?: doubleArrayOf(aW.toDouble(), aH.toDouble())
            val cropX = (aLeft + origin[0].toInt()).coerceIn(0, width - 2)
            val cropY = (aTop + origin[1].toInt()).coerceIn(0, height - 2)
            val cropW = size[0].toInt().coerceIn(2, width - cropX)
            val cropH = size[1].toInt().coerceIn(2, height - cropY)

            fun mat(id: Int): Mat3? = tag(id)?.let { e ->
                val v = t.doubles(e)
                if (v.size != 9) throw DngException("tag $id has ${v.size} values; only three-colour cameras are supported")
                Mat3(v)
            }
            fun vec(id: Int): Vec3? = tag(id)?.let { t.doubles(it) }?.takeIf { it.size >= 3 }?.let { Vec3(it[0], it[1], it[2]) }

            return Dng(
                width = width,
                height = height,
                raw = samples,
                cfa = cfa,
                black = black,
                white = white,
                cropX = cropX,
                cropY = cropY,
                cropW = cropW,
                cropH = cropH,
                orientation = (t.int(ifd0, 274) ?: t.int(raw, 274) ?: 1).takeIf { it in 1..8 } ?: 1,
                colorMatrix1 = mat(50721) ?: throw DngException("ColorMatrix1 is missing"),
                colorMatrix2 = mat(50722),
                cameraCalibration1 = mat(50723),
                cameraCalibration2 = mat(50724),
                forwardMatrix1 = mat(50964),
                forwardMatrix2 = mat(50965),
                analogBalance = vec(50727),
                asShotNeutral = vec(50728),
                asShotWhiteXY = tag(50729)?.let { t.doubles(it) }?.takeIf { it.size >= 2 }?.let { it[0] to it[1] },
                illuminant1 = tag(50778)?.let { t.int(it) },
                illuminant2 = tag(50779)?.let { t.int(it) },
                baselineExposure = tag(50730)?.let { t.doubles(it).firstOrNull() } ?: 0.0,
                gainMaps = tag(51009)?.let { gainMaps(t.bytes(it)) } ?: emptyList(),
                make = ifd0[271]?.let { t.string(it) },
                model = ifd0[272]?.let { t.string(it) },
            )
        }

        /** `OpcodeList2`, always big-endian; only GainMap (id 9) is kept. Raw LLD §2. */
        internal fun gainMaps(bytes: ByteArray): List<GainMap> {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (bytes.size < 4) return emptyList()
            val count = b.getInt(0)
            var at = 4
            val out = ArrayList<GainMap>()
            repeat(count) {
                if (at + 16 > bytes.size) return out
                val id = b.getInt(at)
                val size = b.getInt(at + 12)
                val p = at + 16
                if (size < 0 || p + size > bytes.size) return out
                if (id == 9 && size >= 76) {
                    val pointsV = b.getInt(p + 32)
                    val pointsH = b.getInt(p + 36)
                    val mapPlanes = b.getInt(p + 72)
                    val n = pointsV.toLong() * pointsH * mapPlanes
                    if (pointsV > 0 && pointsH > 0 && mapPlanes > 0 && 76 + 4 * n <= size) {
                        out += GainMap(
                            top = b.getInt(p),
                            left = b.getInt(p + 4),
                            bottom = b.getInt(p + 8),
                            right = b.getInt(p + 12),
                            rowPitch = b.getInt(p + 24).coerceAtLeast(1),
                            colPitch = b.getInt(p + 28).coerceAtLeast(1),
                            pointsV = pointsV,
                            pointsH = pointsH,
                            spacingV = b.getDouble(p + 40),
                            spacingH = b.getDouble(p + 48),
                            originV = b.getDouble(p + 56),
                            originH = b.getDouble(p + 64),
                            mapPlanes = mapPlanes,
                            gains = FloatArray(n.toInt()) { b.getFloat(p + 76 + 4 * it) },
                        )
                    }
                }
                at = p + size
            }
            return out
        }
    }
}

/** Just enough TIFF to read DNG tags. */
internal class Tiff(val buf: ByteBuffer) {
    class Entry(val type: Int, val count: Int, val offset: Int)

    fun u32(off: Int) = buf.getInt(off).toLong() and 0xFFFFFFFFL

    fun ifd(offset: Long): Map<Int, Entry> {
        val off = offset.toInt()
        if (off < 8 || off + 2 > buf.limit()) throw DngException("IFD at $offset lies outside the file")
        val n = buf.getShort(off).toInt() and 0xFFFF
        if (off + 2 + 12L * n > buf.limit()) throw DngException("IFD at $offset runs past the end of the file")
        val out = HashMap<Int, Entry>()
        for (i in 0 until n) {
            val e = off + 2 + 12 * i
            val tag = buf.getShort(e).toInt() and 0xFFFF
            val type = buf.getShort(e + 2).toInt() and 0xFFFF
            val count = u32(e + 4)
            val size = SIZES.getOrElse(type) { 0 } * count
            if (size == 0L || count > Int.MAX_VALUE) continue
            val at = if (size <= 4) e + 8 else u32(e + 8).toInt()
            if (at < 0 || at + size > buf.limit()) continue
            out[tag] = Entry(type, count.toInt(), at)
        }
        return out
    }

    fun longs(e: Entry): LongArray = LongArray(e.count) { i ->
        when (e.type) {
            1, 7 -> (buf.get(e.offset + i).toLong() and 0xFF)
            3 -> (buf.getShort(e.offset + 2 * i).toLong() and 0xFFFF)
            4, 13 -> u32(e.offset + 4 * i)
            8 -> buf.getShort(e.offset + 2 * i).toLong()
            9 -> buf.getInt(e.offset + 4 * i).toLong()
            else -> doubles(e)[i].toLong()
        }
    }

    fun doubles(e: Entry): DoubleArray = DoubleArray(e.count) { i ->
        when (e.type) {
            5 -> u32(e.offset + 8 * i).toDouble() / u32(e.offset + 8 * i + 4).toDouble()
            10 -> buf.getInt(e.offset + 8 * i).toDouble() / buf.getInt(e.offset + 8 * i + 4).toDouble()
            11 -> buf.getFloat(e.offset + 4 * i).toDouble()
            12 -> buf.getDouble(e.offset + 8 * i)
            6 -> buf.get(e.offset + i).toDouble()
            else -> longs(e)[i].toDouble()
        }
    }

    fun int(e: Entry): Int? = if (e.count >= 1) longs(e)[0].toInt() else null
    fun int(ifd: Map<Int, Entry>, tag: Int): Int? = ifd[tag]?.let { int(it) }

    fun bytes(e: Entry) = ByteArray(e.count * SIZES.getOrElse(e.type) { 1 }) { buf.get(e.offset + it) }

    fun string(e: Entry) = bytes(e).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII).trim()

    private companion object {
        /** Bytes per value, by TIFF type. */
        val SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)
    }
}
