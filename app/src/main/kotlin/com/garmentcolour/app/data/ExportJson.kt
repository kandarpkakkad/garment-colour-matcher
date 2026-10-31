package com.garmentcolour.app.data

import java.time.Instant

/**
 * The export file (storage LLD §3), built by hand so the unit tests need no JSON
 * library and no Android. No images, ever (D16).
 */
object ExportJson {
    fun write(captures: List<CaptureFull>, appVersion: String, exportedAt: Instant): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"garment-colour-export\",\n")
        sb.append("  \"formatVersion\": 1,\n")
        sb.append("  \"appVersion\": ").append(str(appVersion)).append(",\n")
        sb.append("  \"exportedAt\": ").append(str(exportedAt.toString())).append(",\n")
        sb.append("  \"captures\": [")
        captures.forEachIndexed { i, full ->
            sb.append(if (i == 0) "\n" else ",\n")
            capture(sb, full)
        }
        sb.append(if (captures.isEmpty()) "]\n" else "\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun capture(sb: StringBuilder, full: CaptureFull) {
        val c = full.capture
        val est = if (c.estR == null || c.estG == null || c.estB == null) "null" else arr(c.estR, c.estG, c.estB)
        sb.append("    {\n")
        sb.append("      \"id\": ").append(str(c.id)).append(",\n")
        sb.append("      \"createdAt\": ").append(str(Instant.ofEpochMilli(c.createdAt).toString())).append(",\n")
        sb.append("      \"source\": ").append(str(c.source)).append(",\n")
        sb.append("      \"capturePath\": ").append(str(c.capturePath)).append(",\n")
        sb.append("      \"exposureGain\": ").append(num(c.exposureGain)).append(",\n")
        sb.append("      \"size\": [").append(c.width).append(", ").append(c.height).append("],\n")
        sb.append("      \"tier\": ").append(str(c.tier)).append(",\n")
        sb.append("      \"estimate\": { \"rgb\": ").append(est)
            .append(", \"answers\": ").append(str(c.estAnswers))
            .append(", \"disagreementDeg\": ").append(num(c.estDisagreement))
            .append(", \"support\": ").append(num(c.estSupport))
            .append(", \"score\": ").append(num(c.estScore))
            .append(", \"level\": ").append(str(c.estLevel))
            .append(", \"reason\": ").append(c.estReason?.let(::str) ?: "null").append(" },\n")
        sb.append("      \"applied\": { \"rgb\": ").append(arr(c.appliedR, c.appliedG, c.appliedB))
            .append(", \"xy\": ").append(arr(c.appliedX, c.appliedY))
            .append(", \"method\": ").append(str(c.appliedMethod)).append(" },\n")
        sb.append("      \"engineVersion\": ").append(c.engineVersion).append(",\n")
        sb.append("      \"garments\": [")
        full.garments.sortedBy { it.rank }.forEachIndexed { i, g ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("        { \"label\": ").append(str(g.label))
                .append(", \"fraction\": ").append(num(g.fraction))
                .append(", \"box\": ").append(arr(g.boxL, g.boxT, g.boxR, g.boxB))
                .append(", \"linearRgb\": ").append(arr(g.linR, g.linG, g.linB))
                .append(", \"xyz\": ").append(arr(g.xyzX, g.xyzY, g.xyzZ))
                .append(", \"lab\": ").append(arr(g.labL, g.labA, g.labB))
                .append(", \"hex\": ").append(str(g.hex))
                .append(", \"clipped\": ").append(g.clipped).append(" }")
        }
        sb.append(if (full.garments.isEmpty()) "],\n" else "\n      ],\n")
        sb.append("      \"adjustments\": [")
        full.adjustments.sortedBy { it.createdAt }.forEachIndexed { i, a ->
            sb.append(if (i == 0) "\n" else ",\n")
            val tap = if (a.tapU == null || a.tapV == null) "null" else arr(a.tapU, a.tapV)
            val box = if (a.boxL == null || a.boxT == null || a.boxR == null || a.boxB == null) "null"
            else arr(a.boxL, a.boxT, a.boxR, a.boxB)
            sb.append("        { \"createdAt\": ").append(str(Instant.ofEpochMilli(a.createdAt).toString()))
                .append(", \"kind\": ").append(str(a.kind))
                .append(", \"tap\": ").append(tap)
                .append(", \"box\": ").append(box)
                .append(", \"before\": ").append(arr(a.beforeR, a.beforeG, a.beforeB))
                .append(", \"after\": ").append(arr(a.afterR, a.afterG, a.afterB))
                .append(", \"angleDeg\": ").append(num(a.angleDeg)).append(" }")
        }
        sb.append(if (full.adjustments.isEmpty()) "]\n" else "\n      ]\n")
        sb.append("    }")
    }

    /** JSON has no NaN or infinity: those are written as null. */
    internal fun num(x: Double?): String = if (x == null || !x.isFinite()) "null" else x.toString()

    private fun arr(vararg xs: Double) = xs.joinToString(", ", "[", "]") { num(it) }

    /** RFC 8259 string escaping. */
    internal fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
