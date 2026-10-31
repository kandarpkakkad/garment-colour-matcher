package com.garmentcolour.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garments, the working image, the corrector and the estimate codec. */
class MeasureTest {
    private fun image(w: Int, h: Int, pixel: (Int, Int) -> Pair<Vec3, Label>): Pair<LinearImage, LabelMap> {
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val c = ByteArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val (v, l) = pixel(x, y)
            val i = y * w + x
            r[i] = v.x.toFloat(); g[i] = v.y.toFloat(); b[i] = v.z.toFloat(); c[i] = l.code.toByte()
        }
        return LinearImage(w, h, r, g, b) to LabelMap(w, h, c)
    }

    private val navy = Vec3(0.02, 0.03, 0.09)
    private val grey = Vec3(0.3, 0.3, 0.3)

    @Test
    fun aPlainGarmentMeasuresAsItsColour() {
        val (img, labels) = image(50, 40) { x, _ -> if (x < 20) navy to Label.TOP else grey to Label.BACKGROUND }
        val g = GarmentMeter.measure(img, labels, Mat3.IDENTITY).single()
        assertEquals(Label.TOP, g.label)
        assertEquals(0.4, g.fraction, 1e-12)
        assertEquals(Box(0.0, 0.0, 0.4, 1.0), g.bounds)
        assertTrue(Colour.angleDeg(g.linear, navy) < 1e-4)
        assertEquals(Colour.hex(navy), g.hex)
        assertFalse(g.clipped)
    }

    @Test
    fun shadowsAndHighlightsAreTrimmed() {
        // 84 % navy, 8 % deep shadow, 8 % specular white: the trim leaves only the navy.
        val (img, labels) = image(100, 10) { x, _ ->
            when {
                x < 8 -> navy * 0.15 to Label.TOP
                x < 16 -> Vec3(0.9, 0.9, 0.9) to Label.TOP
                else -> navy to Label.TOP
            }
        }
        val g = GarmentMeter.measure(img, labels, Mat3.IDENTITY).single()
        assertTrue(Colour.deltaE2000(Colour.labOfLinear(navy), g.lab) < 0.5)
    }

    @Test
    fun aBlownOutGarmentIsReportedAndFlagged() {
        val (img, labels) = image(40, 40) { x, _ ->
            when {
                x < 25 -> Vec3(1.0, 1.0, 1.0) to Label.TOP
                x < 30 -> Vec3(0.8, 0.8, 0.78) to Label.TOP
                else -> grey to Label.BACKGROUND
            }
        }
        assertTrue(GarmentMeter.measure(img, labels, Mat3.IDENTITY).single().clipped)
    }

    @Test
    fun tinyRegionsAreIgnoredAndBiggerOnesComeFirst() {
        val (img, labels) = image(100, 100) { x, y ->
            when {
                x < 30 -> navy to Label.TROUSERS
                x < 70 -> grey to Label.TOP
                x == 99 && y < 20 -> navy to Label.SCARF // 0.2 % of the frame
                else -> grey to Label.BACKGROUND
            }
        }
        assertEquals(listOf(Label.TOP, Label.TROUSERS), GarmentMeter.measure(img, labels, Mat3.IDENTITY).map { it.label })
    }

    @Test
    fun theWorkingImageIsAveragedInLinearLight() {
        val d = BoxDownscaler(2, 1, 1, 1)
        d.addRows(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0, 1)
        assertEquals(0.5f, d.result().r[0], 1e-6f) // not the 0.21 of averaging 0 and 255 encoded
        assertEquals(512 to 384, BoxDownscaler.workingSize(4000, 3000))
        assertEquals(300 to 200, BoxDownscaler.workingSize(300, 200))
    }

    @Test
    fun stripsAddUpToTheWholeImage() {
        val px = IntArray(8 * 6) { 0xFF000000.toInt() or (it * 5 shl 16) or (it * 3 shl 8) or it }
        val whole = BoxDownscaler(8, 6, 3, 2).also { it.addRows(px, 0, 6) }.result()
        val strips = BoxDownscaler(8, 6, 3, 2).also {
            it.addRows(px.copyOfRange(0, 16), 0, 2)
            it.addRows(px.copyOfRange(16, 48), 2, 4)
        }.result()
        for (i in 0 until 6) assertEquals(whole.g[i], strips.g[i], 0f)
    }

    @Test
    fun theCorrectorNeutralisesTheLightAndKeepsAlpha() {
        val e = Vec3(0.75, 0.55, 0.36)
        val p = e * 0.5
        val px = intArrayOf(0x80000000.toInt() or (Srgb.to8(p.x) shl 16) or (Srgb.to8(p.y) shl 8) or Srgb.to8(p.z))
        Corrector(Bradford.correctionMatrix(e)).apply(px)
        val r = (px[0] shr 16) and 0xFF; val g = (px[0] shr 8) and 0xFF; val b = px[0] and 0xFF
        assertTrue("$r $g $b", maxOf(r, g, b) - minOf(r, g, b) <= 3)
        assertEquals(0x80, (px[0] ushr 24))
    }

    @Test
    fun theIdentityLeavesPixelsAlone() {
        val px = IntArray(256) { 0xFF000000.toInt() or (it shl 16) or ((255 - it) shl 8) or (it / 2) }
        val before = px.copyOf()
        val c = Corrector(Bradford.correctionMatrix(Vec3.ONES))
        assertTrue(c.isIdentity)
        c.apply(px)
        assertTrue(before.contentEquals(px))
    }

    @Test
    fun estimatesRoundTripThroughTheDatabaseText() {
        val answers = mapOf(
            Estimator.GRAY_WORLD to Vec3(0.61, 0.58, 0.53),
            Estimator.SHADES_OF_GRAY to Vec3(0.6312346, 0.57, 0.52),
            Estimator.GRAY_EDGE to null,
            Estimator.SKIN to null,
        )
        val text = EstimateCodec.encode(answers)
        assertEquals("GRAY_WORLD 0.610000 0.580000 0.530000;SHADES_OF_GRAY 0.631235 0.570000 0.520000;GRAY_EDGE -;SKIN -", text)
        val back = EstimateCodec.decode(text)
        assertEquals(0.631235, back[Estimator.SHADES_OF_GRAY]!!.x, 1e-12)
        assertEquals(null, back[Estimator.GRAY_EDGE])
        assertEquals(null, EstimateCodec.decode("rubbish;;SKIN 1 2")[Estimator.SKIN])
    }
}
