package com.garmentcolour.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ParsingTest {
    @Test
    fun rawClassesMapAsTheModelsDefineThem() {
        assertEquals(Label.TOP, Fusion.fromSegformer(4))
        assertEquals(Label.TROUSERS, Fusion.fromSegformer(6))
        assertEquals(Label.SCARF, Fusion.fromSegformer(17))
        assertEquals(Label.SKIN, Fusion.fromSegformer(11))
        assertEquals(Label.ACCESSORY, Fusion.fromSegformer(9)) // a shoe
        assertEquals(Label.ACCESSORY, Fusion.fromSegformer(99))
        assertEquals(Label.SKIN, Fusion.fromMediaPipe(3))
        assertEquals(Label.CLOTHING, Fusion.fromMediaPipe(4))
        assertEquals(Label.ACCESSORY, Fusion.fromMediaPipe(5))
    }

    @Test
    fun eitherModelCanKeepAPixelOutOfTheEstimate() {
        // pixel 0: SegFormer top, MediaPipe background → top
        // pixel 1: SegFormer background, MediaPipe clothes → accessory (worn, unnamed)
        // pixel 2: SegFormer background, MediaPipe skin → background
        // pixel 3: SegFormer belt, MediaPipe background → accessory
        val sf = byteArrayOf(4, 0, 0, 8)
        val mp = byteArrayOf(0, 4, 2, 0)
        val fused = Fusion.fuse(mp, sf, 4, 1)!!
        assertEquals(Tier.BOTH, fused.tier)
        assertEquals(listOf(Label.TOP, Label.ACCESSORY, Label.BACKGROUND, Label.ACCESSORY), (0 until 4).map { fused.labels.label(it) })
    }

    @Test
    fun tiersFollowWhichModelsRan() {
        assertEquals(Tier.CLOTHES_ONLY, Fusion.fuse(null, byteArrayOf(4), 1, 1)!!.tier)
        val people = Fusion.fuse(byteArrayOf(4), null, 1, 1)!!
        assertEquals(Tier.PEOPLE_ONLY, people.tier)
        assertEquals(Label.CLOTHING, people.labels.label(0))
        assertNull(Fusion.fuse(null, null, 1, 1))
    }

    @Test
    fun logitsAreUpsampledBeforeTheArgmax() {
        // Two logit cells: class 0 wins on the left, class 1 on the right.
        val logits = floatArrayOf(1f, 0f, /* class 1 */ 0f, 1f)
        val out = Fusion.segformerArgmax(logits, width = 4, height = 1, classes = 2, lw = 2, lh = 1)
        assertArrayEquals(byteArrayOf(0, 0, 1, 1), out)
        val tie = Fusion.segformerArgmax(floatArrayOf(0.5f, 0.5f), 1, 1, classes = 2, lw = 1, lh = 1)
        assertEquals(0, tie[0].toInt())
    }

    @Test
    fun segformerInputIsNormalisedNchw() {
        val white = IntArray(4) { 0xFFFFFFFF.toInt() }
        val t = Fusion.segformerInput(white, size = 2)
        assertEquals(12, t.size)
        assertEquals((1 - 0.485f) / 0.229f, t[0], 1e-6f)
        assertEquals((1 - 0.456f) / 0.224f, t[4], 1e-6f)
        assertEquals((1 - 0.406f) / 0.225f, t[8], 1e-6f)
    }

    @Test
    fun boxesAreOrderedClampedAndNeverTiny() {
        assertEquals(Box(0.2, 0.1, 0.6, 0.5), Box(0.6, 0.5, 0.2, 0.1).normalised())
        val tiny = Box(0.5, 0.5, 0.5, 0.5).normalised()
        assertEquals(0.02, tiny.r - tiny.l, 1e-12)
        val edge = Box(-0.3, 0.99, 0.0, 1.4).normalised()
        assertTrue(edge.l >= 0.0 && edge.b <= 1.0 && edge.r - edge.l >= 0.02 - 1e-12)
    }

    @Test
    fun theCentreBoxSelectsTheMiddle() {
        val m = Tier3.centre(10, 10)
        assertEquals(Label.SELECTED, m.label(5 * 10 + 5))
        assertEquals(Label.BACKGROUND, m.label(0))
    }

    @Test
    fun labelMapsRoundTripAndRefuseOtherFiles() {
        val map = LabelMap(3, 2, byteArrayOf(0, 1, 2, 10, 16, 20))
        val bytes = ByteArrayOutputStream().also { map.write(it) }.toByteArray()
        val back = LabelMap.read(ByteArrayInputStream(bytes))
        assertEquals(3, back.width)
        assertArrayEquals(map.codes, back.codes)
        assertThrows { LabelMap.read(ByteArrayInputStream("JPEG....".toByteArray())) }
        assertThrows { LabelMap.read(ByteArrayInputStream(bytes + byteArrayOf(0))) }
        assertEquals(Label.ACCESSORY, Label.of(77))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("expected an IOException")
    }
}
