package io.github.ndev.flockeyes.core.reid

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A yardstick for "alike" that looks past how a cow happens to be standing.
 *
 * The recognition model's description of a picture changes with how the cow stands, how its box was cut
 * and what is behind it, as well as with which cow it is: two looks of one cow a few seconds apart can
 * differ as much as looks of two cows. A tuning is worked out from groups of looks known to be of one
 * animal (a cow followed from frame to frame, or one cow's photos on different days). It finds the
 * directions looks of ONE animal vary along and scales those down, so that what is left says more about
 * which cow it is. (In face and speaker recognition this is "within-class whitening".)
 *
 * The app ships with one made from videos of cows in fields and photos of cows in a barn
 * (models/cow-tuning.bin, made by tools/make_tuning.py; measured in docs/recognition.md). Scaling down
 * everything the looks vary along, without regard to which animal they're of, was tried first and is
 * worse than no tuning in a field: there most of what varies IS which cow it is.
 *
 * The sums are done here rather than with a maths library: the directions come from a few rounds of
 * subspace iteration (multiply by the looks, straighten up, repeat), then a small exact solve.
 */
class Tuning(
    val mean: FloatArray,
    /** The directions looks of one animal vary most along, at right angles to each other, most varied first. */
    val basis: Array<FloatArray>,
    /** For each direction, how much of a look's part along it to add back (negative: scale it down). */
    val gains: FloatArray,
    /** How many looks it was worked out from. */
    val looks: Int,
    /** How much the looks varied along each direction, along the rest (on average), and over all. */
    val spreads: FloatArray = FloatArray(basis.size),
    val rest: Float = 0f,
    val average: Float = 0f,
) {
    /** A description as this yardstick sees it, scaled to length 1. */
    fun apply(emb: FloatArray): FloatArray {
        val d = mean.size
        val out = FloatArray(d)
        for (i in 0 until d) out[i] = emb[i] - mean[i]
        for (k in basis.indices) {
            val v = basis[k]
            var a = 0f
            for (i in 0 until d) a += out[i] * v[i]
            val g = a * gains[k]
            for (i in 0 until d) out[i] += g * v[i]
        }
        return normalize(out)
    }

    /** As saved in models/cow-tuning.bin (the same layout tools/make_tuning.py writes). */
    fun toBytes(): ByteArray {
        val d = mean.size
        val k = basis.size
        val b = ByteBuffer.allocate(28 + 4 * (d + 2 * k + k * d)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC)
        b.putInt(1).putInt(d).putInt(k).putInt(looks)
        b.putFloat(average).putFloat(rest)
        for (v in mean) b.putFloat(v)
        for (v in spreads) b.putFloat(v)
        for (v in gains) b.putFloat(v)
        for (row in basis) for (v in row) b.putFloat(v)
        return b.array()
    }

    companion object {
        /** Fewer looks than this say too little to be worth tuning to. */
        const val MIN_LOOKS = 40
        private val MAGIC = byteArrayOf('F'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'U'.code.toByte())

        fun read(bytes: ByteArray): Tuning {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals(MAGIC) && b.int == 1) { "not a tuning file" }
            val d = b.int
            val k = b.int
            val looks = b.int
            val average = b.float
            val rest = b.float
            require(d in 1..8192 && k in 0..d && bytes.size == 28 + 4 * (d + 2 * k + k * d)) { "a damaged tuning file" }
            val mean = FloatArray(d) { b.float }
            val spreads = FloatArray(k) { b.float }
            val gains = FloatArray(k) { b.float }
            val basis = Array(k) { FloatArray(d) { b.float } }
            return Tuning(mean, basis, gains, looks, spreads, rest, average)
        }

        /**
         * Works out a tuning from groups of looks, each group of one animal (descriptions of length 1),
         * or null if there are too few. Groups of one say nothing and are left out. `directions`: how
         * many of the most varied directions are scaled one by one (the rest share one scale). `shrink`:
         * how far every scale is pulled towards the average, so that a direction the looks happen not to
         * vary along isn't blown up.
         */
        fun fit(groups: List<List<FloatArray>>, directions: Int = 64, shrink: Double = 0.3, iterations: Int = 10, extra: Int = 8): Tuning? {
            val all = groups.flatten()
            if (all.isEmpty()) return null
            val d = all[0].size
            val mu = DoubleArray(d)
            for (x in all) for (i in 0 until d) mu[i] += x[i]
            for (i in 0 until d) mu[i] /= all.size
            // Each look less the middle of its group: how looks of one animal differ.
            val xc = ArrayList<FloatArray>()
            for (g in groups) {
                if (g.size < 2) continue
                val mid = DoubleArray(d)
                for (x in g) for (i in 0 until d) mid[i] += x[i]
                for (i in 0 until d) mid[i] /= g.size
                for (x in g) xc.add(FloatArray(d) { i -> (x[i] - mid[i]).toFloat() })
            }
            val n = xc.size
            if (n < MIN_LOOKS) return null
            var total = 0.0
            for (x in xc) for (v in x) total += v * v
            total /= n
            if (total <= 1e-12) return null
            val average = total / d
            val k = min(directions, min(n - 1, d))
            val p = min(k + extra, min(n - 1, d))

            // Start from looks spread through the list, then: multiply by the looks, straighten up, repeat.
            val step = max(1, n / p)
            val q = Array(p) { r ->
                val x = xc[r * step]
                DoubleArray(d) { i -> x[i].toDouble() }
            }
            orthonormalise(q)
            val rows = xc.toTypedArray()
            val y = Array(n) { DoubleArray(p) }
            repeat(iterations) {
                project(rows, q, y)
                back(rows, y, q)
                orthonormalise(q)
            }
            // The exact answer within those directions.
            project(rows, q, y)
            val h = Array(p) { DoubleArray(p) }
            for (row in y) for (a in 0 until p) {
                val ya = row[a]
                val ha = h[a]
                for (b in a until p) ha[b] += ya * row[b]
            }
            for (a in 0 until p) for (b in a until p) {
                h[a][b] /= n
                h[b][a] = h[a][b]
            }
            val (w, v) = jacobi(h)
            val order = (0 until p).sortedByDescending { w[it] }.take(k)
            val spread = DoubleArray(k) { max(0.0, w[order[it]]) }
            val basis = Array(k) { j ->
                val col = order[j]
                val acc = DoubleArray(d)
                for (a in 0 until p) {
                    val c = v[a][col]
                    val qa = q[a]
                    for (i in 0 until d) acc[i] += c * qa[i]
                }
                FloatArray(d) { acc[it].toFloat() }
            }
            val rest = max(0.0, total - spread.sum()) / max(1, d - k)
            val restScale = 1.0 / sqrt(rest + shrink * average)
            val gains = FloatArray(k) { ((1.0 / sqrt(spread[it] + shrink * average)) / restScale - 1.0).toFloat() }
            return Tuning(FloatArray(d) { mu[it].toFloat() }, basis, gains, n, FloatArray(k) { spread[it].toFloat() }, rest.toFloat(), average.toFloat())
        }

        /** y = x times q transposed: each look's part along each direction. */
        private fun project(x: Array<FloatArray>, q: Array<DoubleArray>, y: Array<DoubleArray>) {
            val d = x[0].size
            for (r in x.indices) {
                val xr = x[r]
                val yr = y[r]
                for (a in q.indices) {
                    val qa = q[a]
                    var s = 0.0
                    for (i in 0 until d) s += xr[i] * qa[i]
                    yr[a] = s
                }
            }
        }

        /** q = y transposed times x, over the number of looks. */
        private fun back(x: Array<FloatArray>, y: Array<DoubleArray>, q: Array<DoubleArray>) {
            val d = x[0].size
            for (qa in q) java.util.Arrays.fill(qa, 0.0)
            for (r in x.indices) {
                val xr = x[r]
                val yr = y[r]
                for (a in q.indices) {
                    val c = yr[a]
                    val qa = q[a]
                    for (i in 0 until d) qa[i] += c * xr[i]
                }
            }
            val n = x.size.toDouble()
            for (qa in q) for (i in 0 until d) qa[i] /= n
        }

        /** Makes the rows unit length and at right angles to each other (modified Gram-Schmidt). */
        internal fun orthonormalise(q: Array<DoubleArray>) {
            val d = q[0].size
            for (i in q.indices) {
                val qi = q[i]
                fun clear() {
                    for (j in 0 until i) {
                        val qj = q[j]
                        var s = 0.0
                        for (t in 0 until d) s += qi[t] * qj[t]
                        for (t in 0 until d) qi[t] -= s * qj[t]
                    }
                }

                fun length(): Double {
                    var s = 0.0
                    for (t in 0 until d) s += qi[t] * qi[t]
                    return sqrt(s)
                }
                clear()
                var len = length()
                var axis = i
                while (len < 1e-9) {
                    // Nothing left of it: take an axis instead.
                    java.util.Arrays.fill(qi, 0.0)
                    qi[axis % d] = 1.0
                    axis++
                    clear()
                    len = length()
                }
                for (t in 0 until d) qi[t] /= len
            }
        }

        /** Eigenvalues and eigenvectors (as columns) of a small symmetric matrix, by Jacobi rotations. */
        internal fun jacobi(a: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
            val n = a.size
            val v = Array(n) { i -> DoubleArray(n).also { it[i] = 1.0 } }
            for (sweep in 0 until 40) {
                var off = 0.0
                var diag = 0.0
                for (i in 0 until n) {
                    diag = max(diag, abs(a[i][i]))
                    for (j in i + 1 until n) off += a[i][j] * a[i][j]
                }
                if (sqrt(off) < 1e-11 * max(diag, 1e-30)) break
                for (p in 0 until n - 1) for (q in p + 1 until n) {
                    val apq = a[p][q]
                    if (abs(apq) < 1e-300) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1)
                    val s = t * c
                    for (r in 0 until n) {
                        val arp = a[r][p]
                        val arq = a[r][q]
                        a[r][p] = c * arp - s * arq
                        a[r][q] = s * arp + c * arq
                    }
                    val rowP = a[p]
                    val rowQ = a[q]
                    for (r in 0 until n) {
                        val apr = rowP[r]
                        val aqr = rowQ[r]
                        rowP[r] = c * apr - s * aqr
                        rowQ[r] = s * apr + c * aqr
                    }
                    for (r in 0 until n) {
                        val vrp = v[r][p]
                        val vrq = v[r][q]
                        v[r][p] = c * vrp - s * vrq
                        v[r][q] = s * vrp + c * vrq
                    }
                }
            }
            return DoubleArray(n) { a[it][it] } to v
        }
    }
}
