package com.garmentcolour.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IlluminantTest {
    private val tungsten = Vec3(0.75, 0.55, 0.36).normalised()!!

    /** A textured grey world under [light]; [paint] can overwrite pixels and labels. */
    private fun scene(
        light: Vec3,
        w: Int = 80,
        h: Int = 60,
        textured: Boolean = true,
        paint: (x: Int, y: Int) -> Pair<Vec3, Label>? = { _, _ -> null },
    ): Pair<LinearImage, LabelMap> {
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val codes = ByteArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val lum = if (textured) 0.25 + 0.35 * (((x / 4) + (y / 4)) % 2) else 0.4
            val (c, label) = paint(x, y) ?: (light * lum to Label.BACKGROUND)
            r[i] = c.x.toFloat()
            g[i] = c.y.toFloat()
            b[i] = c.z.toFloat()
            codes[i] = label.code.toByte()
        }
        return LinearImage(w, h, r, g, b) to LabelMap(w, h, codes)
    }

    @Test
    fun aGreyWorldGivesItsLightBackWithConfidence() {
        val (img, labels) = scene(tungsten)
        val e = Illuminant.estimate(img, labels)
        assertTrue(Colour.angleDeg(e.combined!!, tungsten) < 0.01)
        assertEquals(Level.HIGH, e.level)
        assertNull(e.reason)
    }

    @Test
    fun aDominantGarmentDoesNotPullTheEstimate() {
        val red = Vec3(0.6, 0.04, 0.03)
        val (img, labels) = scene(tungsten) { x, _ -> if (x in 10..69) red to Label.DRESS else null }
        val e = Illuminant.estimate(img, labels)
        assertTrue(Colour.angleDeg(e.combined!!, tungsten) < 0.01)

        // What the plan warns about: a plain average over every pixel is pulled far off.
        var sr = 0.0; var sg = 0.0; var sb = 0.0
        for (i in 0 until img.size) { sr += img.r[i]; sg += img.g[i]; sb += img.b[i] }
        assertTrue(Colour.angleDeg(Vec3(sr, sg, sb), tungsten) > 10)
    }

    @Test
    fun noUsableSceneMeansNoEstimate() {
        val (img, labels) = scene(tungsten) { _, y -> if (y < 58) Vec3(0.5, 0.1, 0.1) to Label.TOP else null }
        val e = Illuminant.estimate(img, labels)
        assertNull(e.combined)
        assertEquals(Level.LOW, e.level)
        assertEquals(Reason.NO_SCENE, e.reason)
        assertTrue(e.answers.values.all { it == null })
    }

    @Test
    fun eachRegionIsCappedAtHalfTheWeight() {
        val hair = Vec3(0.3, 0.1, 0.05)
        val grey = Vec3(0.5, 0.5, 0.5)
        // 90 % background, 10 % hair: background carries 0.5, hair 0.1.
        val (img, labels) = scene(grey, w = 100, h = 10, textured = false) { x, _ ->
            if (x < 10) hair to Label.HAIR else grey to Label.BACKGROUND
        }
        val e = Illuminant.estimate(img, labels)
        val want = Vec3(
            (0.5 * grey.x + 0.1 * hair.x) / 0.6,
            (0.5 * grey.y + 0.1 * hair.y) / 0.6,
            (0.5 * grey.z + 0.1 * hair.z) / 0.6,
        )
        assertTrue(Colour.angleDeg(e.answers[Estimator.GRAY_WORLD]!!, want) < 1e-4)
    }

    @Test
    fun oneEstimatorAloneIsNeverConfident() {
        val (img, labels) = scene(tungsten, textured = false)
        val e = Illuminant.estimate(img, labels)
        assertNull("a flat image has no edges", e.answers[Estimator.GRAY_EDGE])
        assertEquals(Level.LOW, e.level)
        assertEquals(Reason.ONE_ESTIMATOR, e.reason)
        assertTrue(e.score <= 0.45)
    }

    @Test
    fun clippedAndBlackPixelsDoNotVote() {
        val (img, labels) = scene(tungsten) { x, _ ->
            when {
                x < 20 -> Vec3(1.0, 0.97, 0.96) to Label.BACKGROUND
                x < 30 -> Vec3(0.001, 0.001, 0.004) to Label.BACKGROUND
                else -> null
            }
        }
        val usable = Illuminant.usable(img, labels)
        assertFalse(usable[0])
        assertFalse(usable[25])
        assertTrue(usable[40])
        assertTrue(Colour.angleDeg(Illuminant.estimate(img, labels).combined!!, tungsten) < 0.01)
    }

    @Test
    fun aNeutralTapReadsTheLightOrSaysWhyNot() {
        val (grey, _) = scene(tungsten)
        val ok = Illuminant.neutral(grey, 0.5, 0.5)
        assertTrue(ok is NeutralResult.Light && Colour.angleDeg(ok.e, tungsten) < 0.01)

        val (black, _) = scene(Vec3(0.001, 0.001, 0.001), textured = false)
        assertEquals(NeutralResult.Refused(Refusal.TOO_DARK), Illuminant.neutral(black, 0.5, 0.5))
        val (white, _) = scene(Vec3(3.0, 3.0, 3.0), textured = false)
        assertEquals(NeutralResult.Refused(Refusal.TOO_BRIGHT), Illuminant.neutral(white, 0.99, 0.01))
    }

    @Test
    fun erosionIgnoresTheOutsideAndClearsAroundAHole() {
        val w = 20
        val mask = BooleanArray(w * w) { true }
        assertTrue(Illuminant.erode(mask, w, w, 4).all { it })
        mask[10 * w + 10] = false
        val e = Illuminant.erode(mask, w, w, 4)
        assertFalse(e[6 * w + 6])
        assertFalse(e[14 * w + 14])
        assertTrue(e[5 * w + 5])
        assertTrue(e[15 * w + 15])
    }
}
