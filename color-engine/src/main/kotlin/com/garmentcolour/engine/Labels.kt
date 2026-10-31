package com.garmentcolour.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a label may do. Parsing LLD §1. */
enum class Role { SCENE, SKIN, GARMENT, EXCLUDED }

/**
 * The engine's label set. Codes are stored in labels.bin and in fixtures:
 * **never renumber**; add new codes. Parsing LLD §1.
 */
enum class Label(val code: Int, val role: Role, val title: String) {
    BACKGROUND(0, Role.SCENE, "Background"),
    HAIR(1, Role.SCENE, "Hair"),
    SKIN(2, Role.SKIN, "Skin"),
    TOP(10, Role.GARMENT, "Top"),
    SKIRT(11, Role.GARMENT, "Skirt"),
    TROUSERS(12, Role.GARMENT, "Trousers"),
    DRESS(13, Role.GARMENT, "Dress"),
    SCARF(14, Role.GARMENT, "Scarf"),
    CLOTHING(15, Role.GARMENT, "Clothing"),
    SELECTED(16, Role.GARMENT, "Selected area"),
    ACCESSORY(20, Role.EXCLUDED, "Accessory"),
    ;

    companion object {
        private val BY_CODE = arrayOfNulls<Label>(256).also { a -> entries.forEach { a[it.code] = it } }

        /** Any code not in the set is an accessory: excluded and unreported. */
        fun of(code: Int): Label = BY_CODE[code and 0xFF] ?: ACCESSORY

        /** In code order — the order garments are measured in, before sorting by size. */
        val GARMENTS: List<Label> = entries.filter { it.role == Role.GARMENT }

        /** Role per raw code, for hot loops. */
        internal val ROLE_BY_CODE: Array<Role> = Array(256) { of(it).role }
    }
}

/** One label code per working-image pixel, row-major. */
class LabelMap(val width: Int, val height: Int, val codes: ByteArray) {
    init {
        require(codes.size == width * height) { "need ${width * height} labels, got ${codes.size}" }
    }

    val size get() = width * height
    fun code(i: Int) = codes[i].toInt() and 0xFF
    fun label(i: Int) = Label.of(code(i))
    fun role(i: Int) = Label.ROLE_BY_CODE[code(i)]

    /** `labels.bin`: "GCL1", width and height as big-endian int32, then the codes. Parsing LLD §6. */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.write(MAGIC)
        d.writeInt(width)
        d.writeInt(height)
        d.write(codes)
        d.flush()
    }

    companion object {
        private val MAGIC = "GCL1".toByteArray(Charsets.US_ASCII)

        fun read(input: InputStream): LabelMap {
            val d = DataInputStream(input)
            val magic = ByteArray(4)
            d.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("not a label map (magic ${magic.decodeToString()})")
            val w = d.readInt()
            val h = d.readInt()
            if (w <= 0 || h <= 0 || w.toLong() * h > 4_000_000L) throw IOException("bad label map size $w × $h")
            val codes = ByteArray(w * h)
            d.readFully(codes)
            if (d.read() != -1) throw IOException("label map longer than $w × $h")
            return LabelMap(w, h, codes)
        }

        fun filled(width: Int, height: Int, label: Label) =
            LabelMap(width, height, ByteArray(width * height) { label.code.toByte() })
    }
}
