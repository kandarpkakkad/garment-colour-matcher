package com.garmentcolour.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GrabCutTest {
    @Test
    fun maxFlowSolvesTheTextbookNetwork() {
        // CLRS figure 26.1: the maximum flow is 23.
        val g = MaxFlow(6)
        val s = 0; val v1 = 1; val v2 = 2; val v3 = 3; val v4 = 4; val t = 5
        g.addEdge(s, v1, 16.0); g.addEdge(s, v2, 13.0); g.addEdge(v1, v3, 12.0); g.addEdge(v2, v1, 4.0)
        g.addEdge(v2, v4, 14.0); g.addEdge(v3, v2, 9.0); g.addEdge(v3, t, 20.0); g.addEdge(v4, v3, 7.0)
        g.addEdge(v4, t, 4.0)
        assertEquals(23.0, g.run(s, t), 1e-9)
        val side = g.sourceSide(s)
        assertTrue(side[s] && side[v1] && side[v2] && side[v4])
        assertFalse(side[v3] || side[t])
    }

    @Test
    fun maxFlowHandlesLongPathsWithoutRecursing() {
        val n = 200_000
        val g = MaxFlow(n)
        for (i in 0 until n - 1) g.addEdge(i, i + 1, 1.0 + (i % 7))
        assertEquals(1.0, g.run(0, n - 1), 1e-9)
    }

    /** A red ellipse on a busy grey-beige background, with sensor-like noise. */
    private fun scene(w: Int, h: Int, seed: Long = 7): Pair<LinearImage, BooleanArray> {
        val rnd = Random(seed)
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        val truth = BooleanArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val dx = (x - w / 2.0) / (w * 0.22)
            val dy = (y - h / 2.0) / (h * 0.30)
            val inEllipse = dx * dx + dy * dy <= 1.0
            truth[i] = inEllipse
            val noise = (rnd.nextGaussian() * 0.01).toFloat()
            if (inEllipse) {
                val shade = (0.8 + 0.2 * kotlin.math.sin(x / 3.0)).toFloat()
                r[i] = 0.45f * shade + noise; g[i] = 0.04f * shade + noise; b[i] = 0.05f * shade + noise
            } else {
                val beige = ((x / 6) + (y / 6)) % 2 == 0
                r[i] = (if (beige) 0.45f else 0.20f) + noise
                g[i] = (if (beige) 0.40f else 0.20f) + noise
                b[i] = (if (beige) 0.30f else 0.21f) + noise
            }
        }
        return LinearImage(w, h, r, g, b) to truth
    }

    @Test
    fun cutsTheGarmentOutOfTheBox() {
        val (img, truth) = scene(200, 150)
        val result = GrabCut.segment(img, Box(0.20, 0.12, 0.80, 0.88))
        assertTrue(result.refined)
        var inter = 0
        var union = 0
        for (i in truth.indices) {
            val sel = result.labels.label(i) == Label.SELECTED
            if (sel && truth[i]) inter++
            if (sel || truth[i]) union++
        }
        val iou = inter.toDouble() / union
        assertTrue("IoU $iou", iou >= 0.9)
        assertEquals(Label.BACKGROUND, result.labels.label(0))
    }

    @Test
    fun theSameInputGivesTheSameCut() {
        val (img, _) = scene(120, 90)
        val box = Box(0.2, 0.1, 0.8, 0.9)
        assertArrayEquals(GrabCut.segment(img, box).labels.codes, GrabCut.segment(img, box).labels.codes)
    }

    @Test
    fun nothingToCutFallsBackToTheWholeBox() {
        val n = 80 * 60
        val flat = LinearImage(80, 60, FloatArray(n) { 0.3f }, FloatArray(n) { 0.3f }, FloatArray(n) { 0.3f })
        val box = Box(0.3, 0.3, 0.7, 0.7)
        val result = GrabCut.segment(flat, box)
        assertFalse(result.refined)
        assertArrayEquals(Tier3.box(80, 60, box).codes, result.labels.codes)
    }
}
