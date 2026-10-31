package com.garmentcolour.engine.raw

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes small DNGs shaped like DngCreator's, so the reader is tested on files we control. */
class TestDng(val width: Int, val height: Int, val samples: IntArray) {
    var cfa = intArrayOf(0, 1, 1, 2)
    var black = 64
    var white = 1023
    var orientation = 1
    var cropOrigin = 0 to 0
    var cropSize: Pair<Int, Int>? = null
    var colorMatrix1 = ONEPLUS_CM1
    var colorMatrix2: DoubleArray? = ONEPLUS_CM2
    var forwardMatrix1: DoubleArray? = ONEPLUS_FM
    var forwardMatrix2: DoubleArray? = ONEPLUS_FM
    var illuminant1 = 21
    var illuminant2 = 17
    var asShotNeutral: DoubleArray? = doubleArrayOf(0.5137, 1.0, 0.5391)
    var opcodeList2: ByteArray? = null
    var compression = 1
    var photometric = 32803
    var bigEndian = false
    var rowPerStrip = true

    private class Field(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)

    fun build(): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun buf(n: Int) = ByteBuffer.allocate(n).order(order)
        fun shorts(vararg v: Int) = buf(2 * v.size).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
        fun longs(vararg v: Long) = buf(4 * v.size).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()
        fun srational(v: DoubleArray) = buf(8 * v.size).also { b -> v.forEach { b.putInt((it * 10000).roundToInt()); b.putInt(10000) } }.array()
        fun rational(v: DoubleArray) = buf(8 * v.size).also { b -> v.forEach { b.putInt((it * 10000).roundToInt()); b.putInt(10000) } }.array()

        val data = buf(2 * samples.size).also { b -> samples.forEach { b.putShort(it.toShort()) } }.array()
        val fields = ArrayList<Field>()
        fun add(tag: Int, type: Int, count: Int, bytes: ByteArray) { fields += Field(tag, type, count, bytes) }
        add(254, 4, 1, longs(0))
        add(256, 4, 1, longs(width.toLong()))
        add(257, 4, 1, longs(height.toLong()))
        add(258, 3, 1, shorts(16))
        add(259, 3, 1, shorts(compression))
        add(262, 3, 1, shorts(photometric))
        add(274, 3, 1, shorts(orientation))
        add(277, 3, 1, shorts(1))
        add(33421, 3, 2, shorts(2, 2))
        add(33422, 1, 4, ByteArray(4) { cfa[it].toByte() })
        add(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
        add(50713, 3, 2, shorts(2, 2))
        add(50714, 5, 4, rational(DoubleArray(4) { black.toDouble() }))
        add(50717, 4, 1, longs(white.toLong()))
        add(50719, 4, 2, longs(cropOrigin.first.toLong(), cropOrigin.second.toLong()))
        val (cw, ch) = cropSize ?: (width to height)
        add(50720, 4, 2, longs(cw.toLong(), ch.toLong()))
        add(50721, 10, 9, srational(colorMatrix1))
        colorMatrix2?.let { add(50722, 10, 9, srational(it)) }
        asShotNeutral?.let { add(50728, 5, 3, rational(it)) }
        add(50778, 3, 1, shorts(illuminant1))
        add(50779, 3, 1, shorts(illuminant2))
        forwardMatrix1?.let { add(50964, 10, 9, srational(it)) }
        forwardMatrix2?.let { add(50965, 10, 9, srational(it)) }
        opcodeList2?.let { add(51009, 7, it.size, it) }

        val strips = if (rowPerStrip) height else 1
        val rowBytes = 2 * width
        // StripOffsets is patched once the layout is known.
        add(273, 4, strips, ByteArray(4 * strips))
        add(278, 4, 1, longs(if (rowPerStrip) 1 else height.toLong()))
        add(279, 4, strips, longs(*LongArray(strips) { (if (rowPerStrip) rowBytes else rowBytes * height).toLong() }))
        fields.sortBy { it.tag }

        val ifdSize = 2 + 12 * fields.size + 4
        var extra = 8 + ifdSize
        val placed = HashMap<Int, Int>()
        for (f in fields) if (f.bytes.size > 4) { placed[f.tag] = extra; extra += f.bytes.size + (f.bytes.size and 1) }
        val dataStart = extra
        val offsets = longs(*LongArray(strips) { (dataStart + it * (if (rowPerStrip) rowBytes else 0)).toLong() })
        val fixed = fields.map { if (it.tag == 273) Field(273, 4, strips, offsets) else it }

        val out = ByteArrayOutputStream()
        out.write(if (bigEndian) byteArrayOf(0x4D, 0x4D, 0, 42) else byteArrayOf(0x49, 0x49, 42, 0))
        out.write(longs(8))
        out.write(shorts(fixed.size))
        for (f in fixed) {
            out.write(shorts(f.tag, f.type))
            out.write(longs(f.count.toLong()))
            if (f.bytes.size <= 4) out.write(f.bytes.copyOf(4)) else out.write(longs(placed.getValue(f.tag).toLong()))
        }
        out.write(longs(0))
        for (f in fixed) if (f.bytes.size > 4) {
            out.write(f.bytes)
            if (f.bytes.size and 1 == 1) out.write(0)
        }
        out.write(data)
        return out.toByteArray()
    }

    companion object {
        // From the OnePlus 6T sample on raw.pixls.us (CC0): a real phone's matrices.
        val ONEPLUS_CM1 = doubleArrayOf(0.60938, -0.28906, -0.09375, -0.96875, 1.875, 0.03906, 0.05469, -0.1875, 0.99219)
        val ONEPLUS_CM2 = doubleArrayOf(1.46094, -0.69531, -0.22656, -0.96875, 1.875, 0.03906, 0.03906, -0.13281, 0.67969)
        val ONEPLUS_FM = doubleArrayOf(0.4375, 0.38281, 0.14062, 0.21875, 0.71875, 0.0625, 0.01562, 0.09375, 0.71094)

        /** A GainMap opcode list (big-endian) with one uniform [gain] over the whole image. */
        fun uniformGainMap(width: Int, height: Int, gain: Float): ByteArray {
            val p = ByteBuffer.allocate(76 + 4 * 4).order(ByteOrder.BIG_ENDIAN)
            p.putInt(0); p.putInt(0); p.putInt(height); p.putInt(width)
            p.putInt(0); p.putInt(1); p.putInt(1); p.putInt(1)
            p.putInt(2); p.putInt(2)
            p.putDouble(1.0); p.putDouble(1.0); p.putDouble(0.0); p.putDouble(0.0)
            p.putInt(1)
            repeat(4) { p.putFloat(gain) }
            val params = p.array()
            val list = ByteBuffer.allocate(4 + 16 + params.size).order(ByteOrder.BIG_ENDIAN)
            list.putInt(1); list.putInt(9); list.putInt(0x01030000); list.putInt(0); list.putInt(params.size)
            list.put(params)
            return list.array()
        }
    }
}
