package com.garmentcolour.app

import com.garmentcolour.app.data.CaptureFull
import com.garmentcolour.app.data.ExportJson
import com.garmentcolour.app.pipeline.AdjustmentKind
import com.garmentcolour.app.pipeline.Decoded
import com.garmentcolour.app.pipeline.Records
import com.garmentcolour.app.pipeline.Source
import com.garmentcolour.engine.Engine
import com.garmentcolour.engine.Label
import com.garmentcolour.engine.LabelMap
import com.garmentcolour.engine.LinearImage
import com.garmentcolour.engine.Tier
import com.garmentcolour.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Engine results → rows → export, without Android (storage schema, storage LLD §3). */
class RecordsTest {
    private val light = Vec3(0.75, 0.55, 0.36)

    /** A textured grey wall under [light] with a navy top in the middle third. */
    private fun scene(): Pair<LinearImage, LabelMap> {
        val w = 90
        val h = 60
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val codes = ByteArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val top = x in 30..59
            val v = if (top) Vec3(0.02, 0.03, 0.09) else light * (0.25 + 0.3 * (((x / 3) + (y / 3)) % 2))
            r[i] = v.x.toFloat(); g[i] = v.y.toFloat(); b[i] = v.z.toFloat()
            codes[i] = (if (top) Label.TOP else Label.BACKGROUND).code.toByte()
        }
        return LinearImage(w, h, r, g, b) to LabelMap(w, h, codes)
    }

    private val decoded = Decoded(4000, 3000, 12.0, downscaled = false)

    @Test
    fun anEstimatedCaptureMapsToItsRow() {
        val (img, labels) = scene()
        val a = Engine.analyse(img, labels)
        val c = Records.capture("id-1", 1_700_000_000_000, Source.CAMERA, decoded, Tier.BOTH, a)
        assertEquals("ESTIMATED", c.appliedMethod)
        assertEquals("BOTH", c.tier)
        assertEquals("JPEG", c.capturePath)
        assertEquals(a.estimate.combined!!.x, c.estR!!, 0.0)
        assertEquals(c.estR!!, c.appliedR, 0.0)
        assertEquals(Engine.VERSION, c.engineVersion)
        assertTrue(c.estAnswers.startsWith("GRAY_WORLD "))

        val rows = Records.garments("id-1", a.garments)
        assertEquals(listOf("TOP"), rows.map { it.label })
        assertEquals(0, rows[0].rank)
        assertTrue(rows[0].hex.matches(Regex("#[0-9A-F]{6}")))
    }

    @Test
    fun aTapKeepsTheEstimateBesideTheAppliedLight() {
        val (img, labels) = scene()
        val tapped = Vec3(0.7, 0.6, 0.4)
        val a = Engine.analyse(img, labels, tapped)
        val c = Records.capture("id-2", 0, Source.IMPORT, decoded, Tier.BOX, a)
        assertEquals("NEUTRAL_TAP", c.appliedMethod)
        assertTrue("the estimate is still stored", c.estR != null)
        assertEquals(tapped.normalised()!!.x, c.appliedR, 1e-12)

        val adj = Records.adjustment("id-2", 5, AdjustmentKind.NEUTRAL_TAP, light, tapped, tap = 0.1 to 0.2)
        assertEquals("NEUTRAL_TAP", adj.kind)
        assertEquals(0.1, adj.tapU!!, 0.0)
        assertNull(adj.boxL)
        assertTrue(adj.angleDeg > 0)
    }

    @Test
    fun theExportIsJsonWithNoNaN() {
        val (img, labels) = scene()
        val a = Engine.analyse(img, labels)
        val c = Records.capture("q\"uote\n", 0, Source.CAMERA, decoded, Tier.BOTH, a)
            .copy(estDisagreement = Double.NaN)
        val g = Records.garments(c.id, a.garments)
        val adj = Records.adjustment(c.id, 1, AdjustmentKind.USE_ESTIMATE, light, light)
        val text = ExportJson.write(listOf(CaptureFull(c, g, listOf(adj))), "0.1.0", Instant.EPOCH)

        assertTrue(text.contains("\"format\": \"garment-colour-export\""))
        assertTrue(text.contains("\"id\": \"q\\\"uote\\n\""))
        assertTrue(text.contains("\"disagreementDeg\": null"))
        assertFalse(text.contains("NaN"))
        assertTrue(text.contains("\"kind\": \"USE_ESTIMATE\""))
        assertTrue(text.contains("\"exportedAt\": \"1970-01-01T00:00:00Z\""))
        // Balanced brackets: a cheap check that the hand-built structure closes.
        assertEquals(text.count { it == '{' }, text.count { it == '}' })
        assertEquals(text.count { it == '[' }, text.count { it == ']' })
    }

    @Test
    fun anEmptyExportIsStillAnObject() {
        val text = ExportJson.write(emptyList(), "0.1.0", Instant.EPOCH)
        assertTrue(text.contains("\"captures\": []"))
        assertEquals("\"a\\u0001\"", ExportJson.str("a\u0001"))
    }
}
