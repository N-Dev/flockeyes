package io.github.ndev.flockeyes.core.herd

import io.github.ndev.flockeyes.core.reid.dot

/** Two cows in the herd and how alike their most alike looks are (as the herd's tuning sees them). */
class Alike(val a: Cow, val b: Cow, val score: Double)

/** Looks for cows learnt twice, and says how well the herd's cows are told apart. */
object HerdCheck {
    /** How alike two cows are: their most alike pair of looks. */
    fun alike(a: Cow, b: Cow): Double {
        var best = -1.0
        for (va in a.views) for (vb in b.views) {
            val s = dot(va.tuned, vb.tuned)
            if (s > best) best = s
        }
        return best
    }

    /** Pairs of cows at least `threshold` alike, most alike first: probably one cow learnt twice. */
    fun duplicates(herd: Herd, threshold: Double, limit: Int = 50): List<Alike> {
        val cows = herd.cows
        val out = ArrayList<Alike>()
        for (i in cows.indices) for (j in i + 1 until cows.size) {
            val s = alike(cows[i], cows[j])
            if (s >= threshold) out.add(Alike(cows[i], cows[j], s))
        }
        out.sortByDescending { it.score }
        return out.take(limit)
    }

    /** The cows most like this one, most alike first. */
    fun nearest(herd: Herd, cow: Cow, n: Int = 5): List<Alike> =
        herd.cows.filter { it !== cow && it.views.isNotEmpty() }.map { Alike(cow, it, alike(cow, it)) }.sortedByDescending { it.score }.take(n)

    /**
     * For each cow, how alike its nearest other cow is: sorted, lowest first. If the "same cow" threshold
     * sits below many of these, different cows are being taken for each other.
     */
    fun nearestScores(herd: Herd): List<Double> {
        val cows = herd.cows.filter { it.views.isNotEmpty() }
        return cows.map { c -> cows.filter { it !== c }.maxOfOrNull { alike(c, it) } ?: -1.0 }.filter { it > -1.0 }.sorted()
    }
}
