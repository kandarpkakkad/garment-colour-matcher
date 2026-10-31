package com.garmentcolour.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * The phase 2 gate (D10, accuracy LLD §3): the Kotlin engine reproduces the Python
 * reference on every fixture spike/make_fixtures.py wrote, garment colours within
 * ΔE2000 0.5. Regenerate with `tool/spike_in_docker.sh fixtures`.
 */
class FixtureParityTest {
    private class Fixture(
        val name: String,
        val image: LinearImage,
        val labels: LabelMap,
        val expect: Map<String, List<String>>,
        val garments: List<List<String>>,
    )

    private fun load(name: String): Fixture {
        val text = javaClass.getResourceAsStream("/fixtures/$name.txt")?.bufferedReader()?.readLines()
            ?: error("fixture $name missing")
        require(text[0] == "gcm-fixture 1") { "$name: unknown format ${text[0]}" }
        val expect = HashMap<String, List<String>>()
        val garments = ArrayList<List<String>>()
        var w = 0
        var h = 0
        var at = 1
        while (text[at] != "pixels") {
            val f = text[at].split(' ')
            when (f[0]) {
                "size" -> { w = f[1].toInt(); h = f[2].toInt() }
                "expect.garment" -> garments += f.drop(1)
                else -> expect[f[0]] = f.drop(1)
            }
            at++
        }
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val codes = ByteArray(n)
        for (y in 0 until h) {
            val cells = text[at + 1 + y].split(' ')
            require(cells.size == w) { "$name: row $y has ${cells.size} cells" }
            for (x in 0 until w) {
                val v = cells[x].toLong(16)
                val i = y * w + x
                r[i] = Srgb.DECODE[((v shr 24) and 0xFF).toInt()]
                g[i] = Srgb.DECODE[((v shr 16) and 0xFF).toInt()]
                b[i] = Srgb.DECODE[((v shr 8) and 0xFF).toInt()]
                codes[i] = (v and 0xFF).toByte()
            }
        }
        return Fixture(name, LinearImage(w, h, r, g, b), LabelMap(w, h, codes), expect, garments)
    }

    private fun vec(f: List<String>): Vec3? =
        if (f.first() == "-") null else Vec3(f[0].toDouble(), f[1].toDouble(), f[2].toDouble())

    private fun names(): List<String> =
        javaClass.getResourceAsStream("/fixtures/index.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun assertSameDirection(what: String, want: Vec3?, got: Vec3?) {
        if (want == null) {
            assertNull("$what: Python had no answer, Kotlin gave $got", got)
            return
        }
        assertNotNull("$what: Kotlin had no answer, Python gave $want", got)
        val deg = Colour.angleDeg(want, got!!)
        assertTrue("$what: ${"%.5f".format(deg)}° apart", deg <= 0.01)
    }

    @Test
    fun thereAreFixtures() {
        assertTrue("expected a dozen fixtures, found ${names().size}", names().size >= 10)
    }

    @Test
    fun everyFixtureMatchesThePythonReference() {
        var worstDeltaE = 0.0
        val failures = ArrayList<String>()
        for (name in names()) {
            try {
                worstDeltaE = maxOf(worstDeltaE, check(load(name)))
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        println("FixtureParityTest: ${names().size} fixtures, worst garment ΔE2000 ${"%.6f".format(worstDeltaE)}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** Returns the worst garment ΔE2000 for the fixture. */
    private fun check(fx: Fixture): Double {
        val a = Engine.analyse(fx.image, fx.labels)
        val est = a.estimate
        for (k in Estimator.entries) assertSameDirection(k.name, vec(fx.expect.getValue("expect.${k.name}")), est.answers[k])
        assertSameDirection("combined", vec(fx.expect.getValue("expect.combined")), est.combined)

        val d = fx.expect.getValue("expect.disagreement").first()
        if (d == "-") assertNull(est.disagreementDeg) else assertEquals("disagreement", d.toDouble(), est.disagreementDeg!!, 0.01)
        assertEquals("support", fx.expect.getValue("expect.support").first().toDouble(), est.support, 1e-9)
        assertEquals("score", fx.expect.getValue("expect.score").first().toDouble(), est.score, 1e-3)
        assertEquals("level", fx.expect.getValue("expect.level").first(), est.level.name)
        assertEquals("reason", fx.expect.getValue("expect.reason").first(), est.reason?.name ?: "-")
        assertEquals("method", fx.expect.getValue("expect.method").first(), a.method.name)

        val m = fx.expect.getValue("expect.matrix").map { it.toDouble() }
        for (i in 0 until 9) {
            val got = a.matrix[i / 3, i % 3]
            assertTrue("matrix[$i] ${m[i]} vs $got", abs(m[i] - got) <= 1e-4)
        }

        assertEquals("garments", fx.garments.map { it[0] }, a.garments.map { it.label.name })
        var worst = 0.0
        for ((want, got) in fx.garments.zip(a.garments)) {
            assertEquals("${got.label} fraction", want[1].toDouble(), got.fraction, 1e-9)
            assertEquals("${got.label} clipped", want[2] == "1", got.clipped)
            val de = Colour.deltaE2000(Lab(want[3].toDouble(), want[4].toDouble(), want[5].toDouble()), got.lab)
            assertTrue("${got.label}: ΔE2000 $de > 0.5 — the phase 2 gate", de <= 0.5)
            worst = maxOf(worst, de)
        }

        val n = fx.expect.getValue("expect.neutral")
        when (val tap = Illuminant.neutral(fx.image, n[0].toDouble(), n[1].toDouble())) {
            is NeutralResult.Light -> {
                assertEquals("neutral", n[2], "ok")
                assertSameDirection("neutral", vec(n.drop(3)), tap.e)
            }
            is NeutralResult.Refused -> {
                assertEquals("neutral", n[2], "refused")
                assertEquals("neutral refusal", n[3], tap.why.name)
            }
        }
        return worst
    }
}
