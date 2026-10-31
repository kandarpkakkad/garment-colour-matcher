package com.garmentcolour.engine.raw

import com.garmentcolour.engine.Colour
import com.garmentcolour.engine.Engine
import com.garmentcolour.engine.Label
import com.garmentcolour.engine.LabelMap
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Opt-in: runs the whole RAW path on real DNGs and writes what it renders, for a person to
 * look at. `tool/test_in_docker.sh dng` sets `gcm.dngDir` to `build/check/dng/`; elsewhere
 * this is skipped. No segmentation here (no models on the JVM): every pixel is scene.
 */
class RealDngCheck {
    @Test
    fun renderEveryDngInTheDirectory() {
        val dir = System.getProperty("gcm.dngDir").orEmpty()
        assumeTrue("set gcm.dngDir to run", dir.isNotEmpty() && File(dir).isDirectory)
        val out = File(dir, "out").apply { mkdirs() }
        val files = File(dir).listFiles { f -> f.name.lowercase().endsWith(".dng") }.orEmpty().sortedBy { it.name }
        for (f in files) {
            println("== ${f.name}")
            val t0 = System.nanoTime()
            val dng = try {
                Dng.read(f)
            } catch (e: DngException) {
                println("   refused: ${e.message}")
                continue
            }
            val profile = CameraProfile(dng)
            println("   ${dng.make} ${dng.model}: ${dng.width}×${dng.height}, crop ${dng.cropW}×${dng.cropH}, orientation ${dng.orientation}, " +
                "black ${dng.black.toList()}, white ${dng.white}, gain maps ${dng.gainMaps.size}, illuminants ${dng.illuminant1}/${dng.illuminant2}")
            val raw = RawImages.linearize(dng)
            val working = RawImages.working(raw, profile)
            val gain = RawImages.exposureGain(working, dng.baselineExposure)
            val correction = RawCorrection(profile, gain)
            val a = Engine.analyse(working, LabelMap.filled(working.width, working.height, Label.BACKGROUND), correction = correction)
            val (ax, ay) = Colour.xy(Colour.M * a.applied)
            val (sx, sy) = Colour.xy(Colour.M * profile.asShotLight)
            println("   working ${working.width}×${working.height}, exposure ×${"%.2f".format(gain)}")
            println("   as shot: xy ${"%.4f %.4f".format(sx, sy)} ≈ ${Colour.cct(sx, sy)?.toInt()} K (camera ${"%.0f".format(profile.temperatureOf(profile.asShotNeutral))} K)")
            println("   estimate: ${a.method} xy ${"%.4f %.4f".format(ax, ay)} ≈ ${Colour.cct(ax, ay)?.toInt()} K, " +
                "level ${a.estimate.level} score ${"%.2f".format(a.estimate.score)} disagreement ${a.estimate.disagreementDeg?.let { "%.1f°".format(it) }}, " +
                "${"%.1f".format(Colour.angleDeg(a.applied, profile.asShotLight))}° from as-shot")
            for ((name, light) in listOf("asshot" to profile.asShotLight, "corrected" to a.applied)) {
                val t = System.nanoTime()
                val (w, h, half) = RawImages.outputSize(raw)
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val rows = IntArray(64 * w)
                var y = 0
                while (y < h) {
                    val y1 = minOf(h, y + 64)
                    RawImages.renderRows(raw, correction.cameraMatrix(light), y, y1, rows)
                    img.setRGB(0, y, w, y1 - y, rows, 0, w)
                    y = y1
                }
                val ms = (System.nanoTime() - t) / 1_000_000
                val pw = 900
                val ph = h * pw / w
                val preview = BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB)
                preview.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(img, 0, 0, pw, ph, null)
                    dispose()
                }
                ImageIO.write(preview, "jpg", File(out, "${f.nameWithoutExtension}-$name.jpg"))
                println("   rendered $name ${w}×$h${if (half) " (half)" else ""} in $ms ms")
            }
            println("   total ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }
}
