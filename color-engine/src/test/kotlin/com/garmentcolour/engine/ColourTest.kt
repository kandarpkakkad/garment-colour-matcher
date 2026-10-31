package com.garmentcolour.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColourTest {
    /** Sharma, Wu and Dalal (2005), table 1. */
    private val sharma = listOf(
        Triple(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485), 2.0425),
        Triple(Lab(50.0, 3.1571, -77.2803), Lab(50.0, 0.0, -82.7485), 2.8615),
        Triple(Lab(50.0, 2.8361, -74.0200), Lab(50.0, 0.0, -82.7485), 3.4412),
        Triple(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0), 2.3669),
        Triple(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0), 27.1492),
        Triple(Lab(50.0, 2.5, 0.0), Lab(61.0, -5.0, 29.0), 22.8977),
        Triple(Lab(50.0, 2.5, 0.0), Lab(56.0, -27.0, -3.0), 31.9030),
        Triple(Lab(50.0, 2.5, 0.0), Lab(58.0, 24.0, 15.0), 19.4535),
        Triple(Lab(50.0, 2.5, 0.0), Lab(50.0, 3.1736, 0.5854), 1.0000),
        Triple(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387), 1.2644),
        Triple(Lab(63.0109, -31.0961, -5.8663), Lab(62.8187, -29.7946, -4.0864), 1.2630),
        Triple(Lab(2.0776, 0.0795, -1.1350), Lab(0.9033, -0.0636, -0.5514), 0.9082),
    )

    @Test
    fun deltaE2000MatchesSharmasTable() {
        for ((a, b, want) in sharma) {
            assertEquals("$a vs $b", want, Colour.deltaE2000(a, b), 1e-4)
            assertEquals("$b vs $a", want, Colour.deltaE2000(b, a), 1e-4)
        }
    }

    @Test
    fun bradfordD50ToD65MatchesLindbloom() {
        val want = doubleArrayOf(
            0.9555766, -0.0230393, 0.0631636,
            -0.0282895, 1.0099416, 0.0210077,
            0.0122982, -0.0204830, 1.3299098,
        )
        val got = Bradford.cat(Vec3(0.96422, 1.0, 0.82521), Colour.D65)
        for (i in 0 until 9) assertEquals("entry $i", want[i], got[i / 3, i % 3], 1e-5)
    }

    @Test
    fun everyEightBitValueRoundTrips() {
        for (v in 0..255) assertEquals(v, Srgb.to8(Srgb.DECODE[v].toDouble()))
    }

    @Test
    fun theEncodeTableIsAtMostOneLevelOff() {
        for (k in 0..100_000) {
            val l = k / 100_000.0
            assertTrue("at $l", abs(Srgb.encodeFast(l.toFloat()) - Srgb.to8(l)) <= 1)
        }
        assertEquals(0, Srgb.encodeFast(Float.NaN))
        assertEquals(255, Srgb.encodeFast(2f))
    }

    @Test
    fun correctionMakesTheLightNeutralAndKeepsItsLuminance() {
        val e = Vec3(0.75, 0.55, 0.36)
        val out = Bradford.correctionMatrix(e) * (e * 0.3)
        assertEquals(out.x, out.y, 1e-12)
        assertEquals(out.y, out.z, 1e-12)
        val before = Colour.luminance(e.x * 0.3, e.y * 0.3, e.z * 0.3)
        assertEquals(before, Colour.luminance(out.x, out.y, out.z), 1e-12)
    }

    @Test
    fun whiteLightIsTheIdentity() {
        val m = Bradford.correctionMatrix(Vec3.ONES)
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1.0 else 0.0, m[i / 3, i % 3], 1e-12)
    }

    @Test
    fun theInverseIsAnInverse() {
        val p = Colour.M * Colour.M_INV
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1.0 else 0.0, p[i / 3, i % 3], 1e-12)
    }

    @Test
    fun labAndHexOfKnownColours() {
        val white = Colour.labOfLinear(Vec3.ONES)
        assertEquals(100.0, white.l, 1e-3)
        assertEquals(0.0, white.a, 0.01)
        assertEquals(0.0, white.b, 0.01)
        assertEquals("#FFFFFF", Colour.hex(Vec3.ONES))
        assertEquals("#000000", Colour.hex(Vec3(-1.0, 0.0, 0.0)))
        assertEquals("#BCBCBC", Colour.hex(Vec3(0.5, 0.5, 0.5)))
    }

    @Test
    fun cctOfD65IsAbout6500() {
        val (x, y) = Colour.xy(Colour.D65)
        assertEquals(6504.0, Colour.cct(x, y)!!, 10.0)
        // Far off the daylight locus, McCamy's cubic runs past 25000 K: not shown.
        assertEquals(null, Colour.cct(0.2, 0.2))
    }
}
