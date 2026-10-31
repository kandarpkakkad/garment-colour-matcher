package com.garmentcolour.engine

/**
 * Dinic's max-flow on a directed graph with real capacities, for GrabCut's cut (D24,
 * parsing LLD §7). Iterative throughout: an augmenting path in a pixel grid can be
 * thousands of nodes long, too deep for recursion on a phone's thread stack.
 */
class MaxFlow(private val n: Int, edgeHint: Int = 16) {
    private val head = IntArray(n) { -1 }
    private var to = IntArray(edgeHint)
    private var next = IntArray(edgeHint)
    private var cap = DoubleArray(edgeHint)
    private var m = 0

    /** An arc u → v with capacity [c], and v → u with [reverse] (pass the same for an undirected link). */
    fun addEdge(u: Int, v: Int, c: Double, reverse: Double = 0.0) {
        if (m + 2 > to.size) {
            val size = to.size * 2
            to = to.copyOf(size)
            next = next.copyOf(size)
            cap = cap.copyOf(size)
        }
        to[m] = v; cap[m] = c; next[m] = head[u]; head[u] = m++
        to[m] = u; cap[m] = reverse; next[m] = head[v]; head[v] = m++
    }

    fun run(s: Int, t: Int): Double {
        var flow = 0.0
        val level = IntArray(n)
        val it = IntArray(n)
        val queue = IntArray(n)
        val path = IntArray(n)
        while (levels(s, t, level, queue)) {
            System.arraycopy(head, 0, it, 0, n)
            var depth = 0
            var u = s
            while (true) {
                if (u == t) {
                    var b = Double.MAX_VALUE
                    for (k in 0 until depth) b = minOf(b, cap[path[k]])
                    for (k in 0 until depth) {
                        cap[path[k]] -= b
                        cap[path[k] xor 1] += b
                    }
                    flow += b
                    depth = 0
                    u = s
                    continue
                }
                var e = it[u]
                while (e != -1 && !(cap[e] > EPS && level[to[e]] == level[u] + 1)) e = next[e]
                it[u] = e
                if (e != -1) {
                    path[depth++] = e
                    u = to[e]
                } else {
                    // A dead end: never enter it again in this phase.
                    level[u] = -1
                    if (u == s) break
                    depth--
                    u = to[path[depth] xor 1]
                    it[u] = next[it[u]]
                }
            }
        }
        return flow
    }

    /** After [run]: the nodes still reachable from [s] in the residual graph — the source side of the cut. */
    fun sourceSide(s: Int): BooleanArray {
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        seen[s] = true
        queue[qt++] = s
        while (qh < qt) {
            val u = queue[qh++]
            var e = head[u]
            while (e != -1) {
                val v = to[e]
                if (!seen[v] && cap[e] > EPS) {
                    seen[v] = true
                    queue[qt++] = v
                }
                e = next[e]
            }
        }
        return seen
    }

    private fun levels(s: Int, t: Int, level: IntArray, queue: IntArray): Boolean {
        level.fill(-1)
        var qh = 0
        var qt = 0
        level[s] = 0
        queue[qt++] = s
        while (qh < qt) {
            val u = queue[qh++]
            var e = head[u]
            while (e != -1) {
                val v = to[e]
                if (level[v] < 0 && cap[e] > EPS) {
                    level[v] = level[u] + 1
                    queue[qt++] = v
                }
                e = next[e]
            }
        }
        return level[t] >= 0
    }

    private companion object {
        const val EPS = 1e-9
    }
}
