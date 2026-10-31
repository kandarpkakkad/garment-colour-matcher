package com.garmentcolour.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class GrabCutResult(val labels: LabelMap, val refined: Boolean)

/**
 * GrabCut (Rother, Kolmogorov and Blake, 2004) from a box: the garment cut out of it, the
 * rest of the box returned to the scene (D24). docs/02-domain/parsing/lld.md §7.
 * Deterministic: the same image and box give the same map.
 */
object GrabCut {
    const val MAX_SIDE = 160
    private const val K = 5
    private const val ITERATIONS = 4
    private const val GAMMA = 50.0
    private const val MIN_SHARE = 0.05
    private val SQRT2 = sqrt(2.0)

    fun segment(image: LinearImage, box: Box): GrabCutResult {
        val b = box.normalised()
        val plain = Tier3.box(image.width, image.height, b)
        val small = downscale(image, MAX_SIDE)
        val sw = small.width
        val sh = small.height
        val n = sw * sh

        // Colours as they look (8-bit sRGB values): GrabCut compares appearance, not light.
        val z = DoubleArray(3 * n)
        for (i in 0 until n) {
            z[3 * i] = 255.0 * Srgb.encode(small.r[i].toDouble())
            z[3 * i + 1] = 255.0 * Srgb.encode(small.g[i].toDouble())
            z[3 * i + 2] = 255.0 * Srgb.encode(small.b[i].toDouble())
        }
        val unknown = BooleanArray(n) { i -> inside(b, i % sw, i / sw, sw, sh) }
        val unknownCount = unknown.count { it }
        if (unknownCount < 16 || n - unknownCount < 16) return GrabCutResult(plain, false)

        val fg = unknown.copyOf()
        val fgModel = Gmm()
        val bgModel = Gmm()
        fgModel.init(z, indices(fg, true))
        bgModel.init(z, indices(fg, false))
        val beta = beta(z, sw, sh)

        repeat(ITERATIONS) {
            fgModel.relearn(z, indices(fg, true))
            bgModel.relearn(z, indices(fg, false))
            cut(z, sw, sh, unknown, fg, fgModel, bgModel, beta)
        }
        keepLargest(fg, sw, sh)

        val w = image.width
        val h = image.height
        val codes = ByteArray(w * h)
        var selected = 0
        var inBox = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!inside(b, x, y, w, h)) {
                codes[y * w + x] = Label.BACKGROUND.code.toByte()
                continue
            }
            inBox++
            val s = (y.toLong() * sh / h).toInt() * sw + (x.toLong() * sw / w).toInt()
            val keep = fg[s]
            if (keep) selected++
            codes[y * w + x] = (if (keep) Label.SELECTED else Label.BACKGROUND).code.toByte()
        }
        if (selected < MIN_SHARE * inBox) return GrabCutResult(plain, false)
        return GrabCutResult(LabelMap(w, h, codes), true)
    }

    /** The centre-inside rule Tier3.box uses (parsing LLD §5). */
    private fun inside(b: Box, x: Int, y: Int, w: Int, h: Int): Boolean {
        val cx = (x + 0.5) / w
        val cy = (y + 0.5) / h
        return cx >= b.l && cx < b.r && cy >= b.t && cy < b.b
    }

    private fun indices(mask: BooleanArray, value: Boolean): IntArray {
        var c = 0
        for (v in mask) if (v == value) c++
        val out = IntArray(c)
        var k = 0
        for (i in mask.indices) if (mask[i] == value) out[k++] = i
        return out
    }

    /** Box average in linear light to at most [maxSide] on the long side. */
    internal fun downscale(image: LinearImage, maxSide: Int): LinearImage {
        val (w, h) = BoxDownscaler.workingSize(image.width, image.height, maxSide)
        if (w == image.width && h == image.height) return image
        val n = w * h
        val r = DoubleArray(n); val g = DoubleArray(n); val bb = DoubleArray(n); val c = IntArray(n)
        for (y in 0 until image.height) {
            val dy = (y.toLong() * h / image.height).toInt()
            for (x in 0 until image.width) {
                val j = dy * w + (x.toLong() * w / image.width).toInt()
                val i = y * image.width + x
                r[j] += image.r[i]; g[j] += image.g[i]; bb[j] += image.b[i]; c[j]++
            }
        }
        return LinearImage(
            w, h,
            FloatArray(n) { (r[it] / max(1, c[it])).toFloat() },
            FloatArray(n) { (g[it] / max(1, c[it])).toFloat() },
            FloatArray(n) { (bb[it] / max(1, c[it])).toFloat() },
        )
    }

    private fun dist2(z: DoubleArray, i: Int, j: Int): Double {
        val a = z[3 * i] - z[3 * j]
        val b = z[3 * i + 1] - z[3 * j + 1]
        val c = z[3 * i + 2] - z[3 * j + 2]
        return a * a + b * b + c * c
    }

    /** Forward 8-neighbours: right, down-left, down, down-right — each pair once. */
    private val NEIGHBOURS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 1), intArrayOf(0, 1), intArrayOf(1, 1))

    private fun beta(z: DoubleArray, w: Int, h: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in 0 until h) for (x in 0 until w) for (d in NEIGHBOURS) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (nx < 0 || nx >= w || ny >= h) continue
            sum += dist2(z, y * w + x, ny * w + nx)
            count++
        }
        return if (count == 0 || sum <= 0.0) 0.0 else 1.0 / (2.0 * sum / count)
    }

    private fun cut(
        z: DoubleArray, w: Int, h: Int, unknown: BooleanArray, fg: BooleanArray,
        fgModel: Gmm, bgModel: Gmm, beta: Double,
    ) {
        val n = w * h
        val node = IntArray(n) { -1 }
        var m = 0
        for (i in 0 until n) if (unknown[i]) node[i] = m++
        val s = m
        val t = m + 1
        val graph = MaxFlow(m + 2, 16 * m + 16)
        val sink = DoubleArray(m)
        for (y in 0 until h) for (x in 0 until w) for (d in NEIGHBOURS) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (nx < 0 || nx >= w || ny >= h) continue
            val i = y * w + x
            val j = ny * w + nx
            if (!unknown[i] && !unknown[j]) continue
            val v = GAMMA / (if (d[0] != 0 && d[1] != 0) SQRT2 else 1.0) * exp(-beta * dist2(z, i, j))
            when {
                unknown[i] && unknown[j] -> graph.addEdge(node[i], node[j], v, v)
                // A fixed-background neighbour pulls towards the sink.
                unknown[i] -> sink[node[i]] += v
                else -> sink[node[j]] += v
            }
        }
        for (i in 0 until n) {
            val k = node[i]
            if (k < 0) continue
            graph.addEdge(s, k, -ln(max(bgModel.likelihood(z, i), 1e-300)))
            graph.addEdge(k, t, -ln(max(fgModel.likelihood(z, i), 1e-300)) + sink[k])
        }
        graph.run(s, t)
        val side = graph.sourceSide(s)
        for (i in 0 until n) if (unknown[i]) fg[i] = side[node[i]]
    }

    /** The largest 4-connected garment region; the rest goes back to the scene. */
    private fun keepLargest(fg: BooleanArray, w: Int, h: Int) {
        val comp = IntArray(fg.size) { -1 }
        val queue = IntArray(fg.size)
        var best = -1
        var bestSize = 0
        var id = 0
        for (start in fg.indices) {
            if (!fg[start] || comp[start] >= 0) continue
            var qh = 0
            var qt = 0
            queue[qt++] = start
            comp[start] = id
            while (qh < qt) {
                val p = queue[qh++]
                val x = p % w
                val y = p / w
                fun visit(q: Int) {
                    if (fg[q] && comp[q] < 0) { comp[q] = id; queue[qt++] = q }
                }
                if (x > 0) visit(p - 1)
                if (x < w - 1) visit(p + 1)
                if (y > 0) visit(p - w)
                if (y < h - 1) visit(p + w)
            }
            if (qt > bestSize) { bestSize = qt; best = id }
            id++
        }
        for (i in fg.indices) if (fg[i] && comp[i] != best) fg[i] = false
    }

    /** A K-component Gaussian mixture over 3-D colours, full covariance. */
    private class Gmm {
        val weight = DoubleArray(K)
        val mean = Array(K) { DoubleArray(3) }
        val inv = Array(K) { DoubleArray(9) }
        val norm = DoubleArray(K)

        /** k-means from luminance quantiles, then the parameters. */
        fun init(z: DoubleArray, idx: IntArray) {
            if (idx.isEmpty()) { weight.fill(0.0); return }
            val lum = DoubleArray(idx.size) { val i = idx[it]; 0.299 * z[3 * i] + 0.587 * z[3 * i + 1] + 0.114 * z[3 * i + 2] }
            val order = idx.indices.sortedBy { lum[it] }
            val centres = Array(K) { k ->
                val i = idx[order[floor((k + 0.5) / K * idx.size).toInt().coerceIn(0, idx.size - 1)]]
                doubleArrayOf(z[3 * i], z[3 * i + 1], z[3 * i + 2])
            }
            val assign = IntArray(idx.size)
            repeat(10) {
                for ((a, i) in idx.withIndex()) {
                    var bestK = 0
                    var bestD = Double.MAX_VALUE
                    for (k in 0 until K) {
                        val dr = z[3 * i] - centres[k][0]
                        val dg = z[3 * i + 1] - centres[k][1]
                        val db = z[3 * i + 2] - centres[k][2]
                        val dd = dr * dr + dg * dg + db * db
                        if (dd < bestD) { bestD = dd; bestK = k }
                    }
                    assign[a] = bestK
                }
                val s = Array(K) { DoubleArray(3) }
                val c = IntArray(K)
                for ((a, i) in idx.withIndex()) {
                    val k = assign[a]
                    s[k][0] += z[3 * i]; s[k][1] += z[3 * i + 1]; s[k][2] += z[3 * i + 2]; c[k]++
                }
                for (k in 0 until K) if (c[k] > 0) for (ch in 0 until 3) centres[k][ch] = s[k][ch] / c[k]
            }
            learn(z, idx, assign)
        }

        /** Assign each sample to its most likely component, then re-estimate. */
        fun relearn(z: DoubleArray, idx: IntArray) {
            if (idx.isEmpty()) { weight.fill(0.0); return }
            val assign = IntArray(idx.size) { a ->
                val i = idx[a]
                var bestK = 0
                var best = -1.0
                for (k in 0 until K) {
                    val p = component(z, i, k)
                    if (p > best) { best = p; bestK = k }
                }
                bestK
            }
            learn(z, idx, assign)
        }

        private fun learn(z: DoubleArray, idx: IntArray, assign: IntArray) {
            val count = IntArray(K)
            val sum = Array(K) { DoubleArray(3) }
            val prod = Array(K) { DoubleArray(9) }
            for ((a, i) in idx.withIndex()) {
                val k = assign[a]
                count[k]++
                for (p in 0 until 3) {
                    sum[k][p] += z[3 * i + p]
                    for (q in 0 until 3) prod[k][3 * p + q] += z[3 * i + p] * z[3 * i + q]
                }
            }
            for (k in 0 until K) {
                if (count[k] == 0) { weight[k] = 0.0; continue }
                weight[k] = count[k].toDouble() / idx.size
                for (p in 0 until 3) mean[k][p] = sum[k][p] / count[k]
                val cov = DoubleArray(9) { pq ->
                    val p = pq / 3
                    val q = pq % 3
                    prod[k][pq] / count[k] - mean[k][p] * mean[k][q] + (if (p == q) 1.0 else 0.0)
                }
                val cm = Mat3(cov)
                val det = cov[0] * (cov[4] * cov[8] - cov[5] * cov[7]) -
                    cov[1] * (cov[3] * cov[8] - cov[5] * cov[6]) +
                    cov[2] * (cov[3] * cov[7] - cov[4] * cov[6])
                val ci = cm.inverse()
                for (pq in 0 until 9) inv[k][pq] = ci[pq / 3, pq % 3]
                norm[k] = 1.0 / sqrt((2 * PI) * (2 * PI) * (2 * PI) * max(det, 1e-12))
            }
        }

        fun component(z: DoubleArray, i: Int, k: Int): Double {
            if (weight[k] <= 0.0) return 0.0
            val d0 = z[3 * i] - mean[k][0]
            val d1 = z[3 * i + 1] - mean[k][1]
            val d2 = z[3 * i + 2] - mean[k][2]
            val c = inv[k]
            val q = d0 * (c[0] * d0 + c[1] * d1 + c[2] * d2) +
                d1 * (c[3] * d0 + c[4] * d1 + c[5] * d2) +
                d2 * (c[6] * d0 + c[7] * d1 + c[8] * d2)
            return weight[k] * norm[k] * exp(-0.5 * q)
        }

        fun likelihood(z: DoubleArray, i: Int): Double {
            var p = 0.0
            for (k in 0 until K) p += component(z, i, k)
            return p
        }
    }
}
