package com.garmentcolour.engine.raw

import com.garmentcolour.engine.Colour
import com.garmentcolour.engine.Engine
import com.garmentcolour.engine.Label
import com.garmentcolour.engine.LabelMap
import com.garmentcolour.engine.Method
import com.garmentcolour.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class RawTest {
    private val xyzA = run { val x = 0.44757; val y = 0.40745; Vec3(x / y, 1.0, (1 - x - y) / y) }

    /** A scene of neutral surfaces of luminance pattern [lum] under the light whose camera neutral is [n]. */
    private fun scene(w: Int, h: Int, n: Vec3, lum: (Int, Int) -> Double, black: Int = 64, white: Int = 1023) =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val c = intArrayOf(0, 1, 1, 2)[2 * (y and 1) + (x and 1)]
            val v = lum(x, y) * n[c]
            (black + v.coerceIn(0.0, 1.0) * (white - black)).toInt()
        }

    private fun profileOf(d: TestDng) = CameraProfile(Dng.read(d.build()))

    @Test
    fun readsWhatDngCreatorWrites() {
        for (bigEndian in listOf(false, true)) for (rowPerStrip in listOf(true, false)) {
            val d = TestDng(8, 6, IntArray(48) { 100 + it }).apply {
                this.bigEndian = bigEndian
                this.rowPerStrip = rowPerStrip
                orientation = 6
                cropOrigin = 2 to 2
                cropSize = 4 to 2
            }
            val dng = Dng.read(d.build())
            assertEquals(8, dng.width)
            assertEquals(6, dng.height)
            assertEquals(147, dng.raw[47].toInt() and 0xFFFF)
            assertEquals(listOf(0, 1, 1, 2), dng.cfa.toList())
            assertEquals(64f, dng.black[3], 0f)
            assertEquals(1023f, dng.white, 0f)
            assertEquals(listOf(2, 2, 4, 2), listOf(dng.cropX, dng.cropY, dng.cropW, dng.cropH))
            assertEquals(6, dng.orientation)
            assertEquals(1.875, dng.colorMatrix1[1, 1], 1e-4)
            assertEquals(0.5137, dng.asShotNeutral!!.x, 1e-4)
            assertEquals(21, dng.illuminant1)
        }
    }

    @Test
    fun refusesWhatItCannotRead() {
        fun refused(d: TestDng, mention: String) {
            try {
                Dng.read(d.build())
                fail("expected a DngException mentioning $mention")
            } catch (e: DngException) {
                assertTrue("${e.message} should mention $mention", e.message!!.contains(mention))
            }
        }
        refused(TestDng(4, 4, IntArray(16)).apply { compression = 7 }, "Compression")
        refused(TestDng(4, 4, IntArray(16)).apply { photometric = 34892 }, "34892")
        refused(TestDng(4, 4, IntArray(16)).apply { cfa = intArrayOf(0, 1, 3, 2) }, "CFAPattern")
        try {
            Dng.read("JFIF....".toByteArray())
            fail()
        } catch (_: DngException) {
        }
    }

    @Test
    fun linearisesClipsAndAppliesTheGainMap() {
        val d = TestDng(4, 4, IntArray(16) { if (it == 5) 1023 else 64 + 479 }).apply {
            opcodeList2 = TestDng.uniformGainMap(4, 4, 1.5f)
        }
        val raw = RawImages.linearize(Dng.read(d.build()))
        assertEquals(0xFFFF, raw.data[5].toInt() and 0xFFFF)
        val v = (raw.data[0].toInt() and 0xFFFF) / 65534.0
        assertEquals(479.0 / 959 * 1.5, v, 1e-4)
    }

    @Test
    fun everyOrientationIsABijectionAndSixRotatesClockwise() {
        val w = 5
        val h = 3
        for (tag in 1..8) {
            val (ow, oh) = Orientation.outputSize(tag, w, h)
            val seen = HashSet<Int>()
            for (oy in 0 until oh) for (ox in 0 until ow) {
                val sx = Orientation.sx(tag, ox, oy, w)
                val sy = Orientation.sy(tag, ox, oy, h)
                assertTrue("tag $tag", sx in 0 until w && sy in 0 until h)
                seen += sy * w + sx
            }
            assertEquals("tag $tag covers every pixel", w * h, seen.size)
        }
        // Rotated 90° clockwise for display: the stored bottom-left becomes the top-left.
        assertEquals(0, Orientation.sx(6, 0, 0, w))
        assertEquals(h - 1, Orientation.sy(6, 0, 0, h))
    }

    @Test
    fun theProfileMapsEachNeutralToWhite() {
        for (withFm in listOf(true, false)) {
            val d = TestDng(4, 4, IntArray(16)).apply { if (!withFm) { forwardMatrix1 = null; forwardMatrix2 = null } }
            val p = profileOf(d)
            for (n in listOf(p.neutralD65, Vec3(0.4, 1.0, 0.7), Vec3(0.8, 1.0, 0.3))) {
                val out = p.toSrgb(n) * n
                assertEquals("fm=$withFm $n", 1.0, out.x, 1e-9)
                assertEquals(1.0, out.y, 1e-9)
                assertEquals(1.0, out.z, 1e-9)
            }
            // T is scaled (raw LLD §4) but still maps the daylight neutral to a neutral.
            val w = p.toWorking * p.neutralD65
            assertEquals(w.y, w.x, 1e-9)
            assertEquals(w.y, w.z, 1e-9)
            // …and no unsaturated sensor value reaches the estimator's clip line.
            val brightest = p.toWorking * Vec3(1.0, 1.0, 1.0)
            assertTrue(maxOf(brightest.x, brightest.y, brightest.z) <= 0.9 + 1e-9)
        }
    }

    @Test
    fun matricesInterpolateByTemperature() {
        val p = profileOf(TestDng(4, 4, IntArray(16)))
        assertTrue(p.interpolates)
        assertEquals(1.0, p.weight(6504.0), 1e-12)
        assertEquals(0.0, p.weight(2856.0), 1e-12)
        assertEquals(0.0, p.weight(2000.0), 1e-12)
        val nA = p.xyzToCamera(2856.0) * xyzA
        assertEquals(2856.0, p.temperatureOf(nA), 40.0)
        val n65 = p.neutralD65
        assertEquals(6504.0, p.temperatureOf(n65), 40.0)
    }

    @Test
    fun theCorrectionNeutralisesTheLightAndKeepsLuminance() {
        val p = profileOf(TestDng(4, 4, IntArray(16)))
        val c = RawCorrection(p, gain = 2.0)
        val nA = p.xyzToCamera(2856.0) * xyzA
        val e = (p.toWorking * nA).normalised()!!
        val out = c.matrix(e) * (e * 0.3)
        assertEquals(out.x, out.y, 1e-9)
        assertEquals(out.y, out.z, 1e-9)
        // To 1e-6: the sRGB white's own luminance is 1.0000001 (colour LLD §3).
        assertEquals(2.0 * Colour.luminance(e.x * 0.3, e.y * 0.3, e.z * 0.3), Colour.luminance(out.x, out.y, out.z), 1e-6)
        // The working light maps back to the camera neutral it came from.
        val back = p.cameraNeutral(e)
        assertTrue(Colour.angleDeg(back, nA) < 1e-6)
    }

    @Test
    fun aTungstenSceneComesOutNeutral() {
        val w = 96
        val h = 72
        val d0 = TestDng(w, h, IntArray(w * h))
        val p = profileOf(d0)
        val nA = p.xyzToCamera(2856.0) * xyzA
        val nMax = nA * (1 / maxOf(nA.x, nA.y, nA.z))
        // A smoothly textured grey wall under tungsten (smooth: a hard luminance edge would
        // fringe under any bilinear demosaic), with a blown-out window in one corner.
        val samples = scene(w, h, nMax, { x, y ->
            if (x < 10 && y < 10) 2.0 else 0.35 + 0.15 * kotlin.math.sin(x / 6.0) * kotlin.math.cos(y / 7.0)
        })
        val dng = Dng.read(TestDng(w, h, samples).build())
        val profile = CameraProfile(dng)
        val raw = RawImages.linearize(dng)
        val working = RawImages.working(raw, profile)
        val labels = LabelMap.filled(working.width, working.height, Label.BACKGROUND)
        val gain = RawImages.exposureGain(working, dng.baselineExposure)
        val correction = RawCorrection(profile, gain)
        val a = Engine.analyse(working, labels, correction = correction)
        assertEquals(Method.ESTIMATED, a.method)

        val out = IntArray(w * h)
        RawImages.renderRows(raw, correction.cameraMatrix(a.applied), 0, h, out)
        for (y in 20 until 60) for (x in 20 until 80) {
            val px = out[y * w + x]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            assertTrue("($x, $y) = $r $g $b", maxOf(r, g, b) - minOf(r, g, b) <= 2)
        }
        assertEquals("the blown-out window renders white", 0xFFFFFFFF.toInt(), out[2 * w + 2])

        // As shot by a phone that guessed daylight: visibly warm.
        val asShot = IntArray(w * h)
        val daylight = RawCorrection(profile, gain).cameraMatrix((profile.toWorking * profile.neutralD65).normalised()!!)
        RawImages.renderRows(raw, daylight, 0, h, asShot)
        val px = asShot[40 * w + 40]
        assertTrue("tungsten under a daylight balance is warm", ((px shr 16) and 0xFF) > (px and 0xFF) + 20)
    }

    @Test
    fun outputSizeFollowsOrientationAndTheLimit() {
        val d = TestDng(8, 6, IntArray(48)).apply { orientation = 6 }
        val raw = RawImages.linearize(Dng.read(d.build()))
        assertEquals(Triple(6, 8, false), RawImages.outputSize(raw))
        val working = RawImages.working(raw, CameraProfile(Dng.read(d.build())))
        assertEquals(3, working.width)
        assertEquals(4, working.height)
    }

    @Test
    fun noAsShotNeutralFallsBackToDaylight() {
        val p = profileOf(TestDng(4, 4, IntArray(16)).apply { asShotNeutral = null })
        assertTrue(Colour.angleDeg(p.asShotNeutral, p.neutralD65) < 1e-9)
        assertNull(Dng.read(TestDng(4, 4, IntArray(16)).apply { asShotNeutral = null }.build()).asShotNeutral)
        val w = p.toWorking * p.asShotNeutral
        assertTrue(abs(w.x - w.z) < 1e-9 && abs(w.x - w.y) < 1e-9)
    }
}
