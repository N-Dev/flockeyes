package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.FrameOut
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.Scan
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.count.Slide
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.herd.Cow
import io.github.ndev.flockeyes.core.herd.Herd
import io.github.ndev.flockeyes.core.herd.HerdCheck
import io.github.ndev.flockeyes.core.herd.HerdListener
import io.github.ndev.flockeyes.core.herd.View
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.ReidConfig
import io.github.ndev.flockeyes.core.reid.Tuning
import io.github.ndev.flockeyes.core.reid.normalize
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pretend field for the naming and counting logic: cows are boxes that move as the test says, and each
 * cow's "looks" are a fixed random vector plus a little noise, as a recognition model's answers would be
 * (two looks of one cow about 0.9 alike, looks of different cows about 0).
 */
class Field(
    val herd: Herd = Herd(), val opt: ScanOptions = ScanOptions(), val looks: HashMap<Int, FloatArray> = HashMap(), seed: Long = 7,
    /** A real place: see [Place]. Null: looks of different cows have nothing in common. */
    val place: Place? = null,
) {
    val scan = Scan(herd, opt, Embedder(ReidConfig(dim = DIM)))
    private val frame = BlankFrame()
    private val rnd = Random(seed)
    var t = 1000.0
    var now = 1_700_000_000_000L

    /** Which pretend cow each track is following. */
    val truth = HashMap<Int, Int>()

    /** Makes cow `like` look like cow `cow` for the recognition model (a jumped track, a double). */
    val disguise = HashMap<Int, Int>()

    companion object {
        const val DIM = 64

        /** A cow-sized box (fractions of a 1280 x 720 frame) with its left edge at x and its feet at y. */
        fun box(x: Double, feet: Double = 0.6, w: Double = 0.14, h: Double = 0.22) = doubleArrayOf(x, feet - h, x + w, feet)
    }

    private fun base(cow: Int): FloatArray = looks.getOrPut(cow) {
        val r = Random(1000L + cow)
        normalize(FloatArray(DIM) { r.nextGaussian().toFloat() })
    }

    fun lookOf(cow: Int, noise: Double = 0.3): FloatArray {
        val b = base(disguise[cow] ?: cow)
        val v = FloatArray(DIM) { (b[it] + noise * rnd.nextGaussian() / Math.sqrt(DIM.toDouble())).toFloat() }
        place?.add(v, rnd)
        return normalize(v)
    }

    /** One frame: the cows where the map says, and every look the scan asks for answered. */
    fun step(cows: Map<Int, DoubleArray>, dtMs: Double = 200.0): FrameOut {
        val out = scan.frame(cows.map { Det("cow", 0.9, it.value) }, frame, t, now, budget = 4)
        for (tr in out.live) cows.entries.firstOrNull { it.value === tr.box }?.let { truth[tr.id] = it.key }
        for (lk in out.looks) scan.look(lk, lookOf(truth[lk.trackId]!!), now)
        t += dtMs
        now += dtMs.toLong()
        return out
    }

    fun steps(n: Int, cows: Map<Int, DoubleArray>, dtMs: Double = 200.0) = repeat(n) { step(cows.mapValues { it.value.copyOf() }, dtMs) }

    /** The herd's cow each pretend cow is named as, from the tracks in view. */
    fun names(): Map<Int, Int?> = scan.tracker.tracks.associate { (truth[it.id] ?: -1) to it.cowId }
}

/**
 * What looks taken in one place have in common, as the real model's do: a large part that is the same in
 * every look (the grass, the light, "a black-and-white cow"), and a few things that change from look to
 * look and say nothing about which cow it is (how the box was cut, how the cow stands). Next to these the
 * part that is the cow itself is small.
 */
class Place(dim: Int = Field.DIM, val same: Double = 2.5, val changing: Double = 1.2, ways: Int = 3, seed: Long = 5) {
    private val r = Random(seed)
    private val common = normalize(FloatArray(dim) { r.nextGaussian().toFloat() })
    private val nuisance = Array(ways) { normalize(FloatArray(dim) { r.nextGaussian().toFloat() }) }

    fun add(v: FloatArray, rnd: Random) {
        for (i in v.indices) v[i] += (same * common[i]).toFloat()
        for (n in nuisance) {
            val g = changing * rnd.nextGaussian()
            for (i in v.indices) v[i] += (g * n[i]).toFloat()
        }
    }
}

class ScanLogicTest {
    private fun row(n: Int, first: Int = 1) = (0 until n).associate { (first + it) to Field.box(0.05 + 0.18 * it) }

    @Test
    fun learnsCowsAndKnowsThemAgain() {
        val a = Field()
        a.scan.start(gate = false)
        a.steps(12, row(5))
        assertEquals(5, a.herd.size, "five cows learnt")
        assertEquals(5, a.scan.session.seen.size)
        assertEquals(5, a.scan.session.fresh.size)
        assertEquals(5, a.scan.session.count)
        assertEquals(5, a.scan.session.peak)
        assertTrue(a.herd.cows.all { it.views.isNotEmpty() && it.seen == 1 }, "each has looks and was seen once")
        val learnt = a.names()
        assertEquals(5, learnt.values.filterNotNull().toSet().size, "each cow its own entry")
        val saved = a.scan.stop(a.now)
        assertTrue(saved.isEmpty(), "a field count's sightings are saved as they happen")

        // Another day, the cows in another order: the same five, none new.
        val b = Field(a.herd, looks = a.looks, seed = 99)
        b.scan.start(gate = false)
        val order = listOf(3, 5, 1, 4, 2)
        b.steps(12, order.withIndex().associate { (i, cow) -> cow to Field.box(0.05 + 0.18 * i, feet = 0.8) })
        assertEquals(5, b.herd.size, "no cow learnt twice")
        assertEquals(5, b.scan.session.seen.size)
        assertEquals(0, b.scan.session.fresh.size)
        assertEquals(learnt, b.names(), "every cow given the name it was learnt under")
        assertTrue(b.herd.cows.all { it.seen == 2 })
        val shown = b.scan.shown()
        assertEquals(5, shown.size)
        assertTrue(shown.all { it.state == "named" && it.counted && it.score > 0.6 }, shown.joinToString { "${it.state} ${it.score}" })
    }

    @Test
    fun aCowCountsOnceHoweverOftenItComesBack() {
        val f = Field()
        f.scan.start(gate = false)
        f.steps(10, row(2))
        // Both out of view for a while (the phone turned away), then back.
        f.steps(10, emptyMap(), dtMs = 300.0)
        f.steps(10, row(2))
        assertEquals(2, f.herd.size)
        assertEquals(2, f.scan.session.count)
        assertEquals(2, f.scan.session.sightings.size)
    }

    @Test
    fun oneCowCannotBeInTwoPlaces() {
        val f = Field()
        f.scan.start(gate = false)
        f.steps(10, mapOf(1 to Field.box(0.1)))
        assertEquals(1, f.herd.size)
        // A second animal that looks just like cow 1 walks in while cow 1 is still there.
        f.disguise[2] = 1
        f.steps(20, mapOf(1 to Field.box(0.1), 2 to Field.box(0.6)))
        val names = f.names()
        assertNotNull(names[1])
        // It isn't cow 1 (that's over there), and it isn't learnt either: an entry that can't be told
        // from another is no use. It still counts.
        assertNull(names[2], "not named as the cow that's standing elsewhere")
        assertEquals(1, f.herd.size, "and not learnt as a second entry that looks the same")
        assertEquals(2, f.scan.session.count, "but counted: two cows in view at once")
        assertEquals("looking", f.scan.shown().first { it.cowId == null }.state)
    }

    @Test
    fun withLearningOffStrangersAreCountedButNotLearnt() {
        val f = Field(opt = ScanOptions(learn = false))
        f.scan.start(gate = false)
        f.steps(14, row(3))
        assertEquals(0, f.herd.size)
        assertEquals(3, f.scan.session.unknown)
        assertEquals(3, f.scan.session.count)
        assertTrue(f.scan.shown().all { it.state == "unknown" })
        assertTrue(f.scan.session.sightings.all { it.cowId == null })
    }

    @Test
    fun cowsTooSmallToTellApartStillCountByHowManyAreInView() {
        val f = Field()
        f.scan.start(gate = false)
        // 38 pixels across: found, but too small to recognise.
        f.steps(8, (1..4).associateWith { Field.box(0.1 + 0.2 * it, w = 0.03, h = 0.05) })
        assertEquals(0, f.herd.size)
        assertEquals(0, f.scan.session.seen.size)
        assertEquals(4, f.scan.session.peak)
        assertEquals(4, f.scan.session.count)
        assertTrue(f.scan.shown().all { it.state == "far" && it.skipped == "small" })
    }

    @Test
    fun nothingIsLearntOrCountedUntilACountStarts() {
        val f = Field()
        f.steps(12, row(3))
        assertEquals(0, f.herd.size)
        assertEquals(0, f.scan.session.count)
        // Cows already known are still named while just watching.
        f.scan.start(gate = false)
        f.steps(12, row(3))
        f.scan.stop(f.now)
        assertEquals(3, f.herd.size)
        val g = Field(f.herd, looks = f.looks)
        g.steps(10, row(3))
        assertEquals(3, g.names().values.filterNotNull().size, "known cows named without a count running")
        assertEquals(0, g.scan.session.count)
        assertTrue(g.herd.cows.all { it.seen == 1 })
    }

    @Test
    fun cowsOverlappingEachOtherAreNotLookedAt() {
        val f = Field()
        f.scan.start(gate = false)
        f.steps(10, mapOf(1 to Field.box(0.30), 2 to Field.box(0.38)))
        assertEquals(0, f.herd.size, "no looks while one is in front of the other")
        assertTrue(f.scan.shown().all { it.skipped == "overlap" })
        f.steps(12, mapOf(1 to Field.box(0.20), 2 to Field.box(0.50)))
        assertEquals(2, f.herd.size, "learnt once they stand apart")
    }

    @Test
    fun aCowFollowedWithoutABreakKeepsItsNameAndIsLearntFromItsOtherSide() {
        val f = Field(opt = ScanOptions(checkGapMs = 300.0))
        f.scan.start(gate = false)
        f.steps(8, mapOf(1 to Field.box(0.3)))
        val id = f.names()[1]
        assertNotNull(id)
        val before = f.herd[id]!!.views.size
        // It turns round: to the recognition model its other side might as well be another animal.
        f.disguise[1] = 7
        f.steps(20, mapOf(1 to Field.box(0.3)))
        assertEquals(id, f.names()[1], "still the same cow: it was never out of sight")
        assertEquals(1, f.herd.size, "nothing new learnt")
        assertEquals(1, f.scan.session.count)
        assertTrue(f.herd[id]!!.views.size > before, "its other side is now part of what's known of it")
        f.scan.stop(f.now)
        // Another day it's seen from that side only, and known.
        val g = Field(f.herd, looks = f.looks, seed = 21)
        g.disguise[1] = 7
        g.scan.start(gate = false)
        g.steps(10, mapOf(1 to Field.box(0.5)))
        assertEquals(id, g.names()[1])
        assertEquals(1, g.herd.size)
    }

    @Test
    fun aTrackThatSlipsOntoAnotherCowIsRenamed() {
        val f = Field(opt = ScanOptions(checkGapMs = 300.0))
        f.scan.start(gate = false)
        f.steps(10, row(2))
        f.scan.stop(f.now)
        val learnt = f.names()
        // Next count: cow 1 alone; then cow 2 walks in front of it and the box that was following cow 1
        // carries on with cow 2 (the tracker slipped).
        val g = Field(f.herd, opt = ScanOptions(checkGapMs = 300.0), looks = f.looks)
        g.scan.start(gate = false)
        g.steps(6, mapOf(1 to Field.box(0.3)))
        assertEquals(learnt[1], g.names()[1])
        g.steps(4, mapOf(1 to Field.box(0.3), 2 to Field.box(0.36)))
        g.disguise[1] = 2
        g.steps(24, mapOf(1 to Field.box(0.3)))
        assertEquals(learnt[2], g.names()[1], "renamed to the cow it now looks like")
        assertEquals(2, g.herd.size, "and nothing new was learnt")
        assertEquals(2, g.scan.session.count, "both were seen")
        // Had it not been in among another animal, it would have kept its name whatever it looked like
        // (see the test above): being followed without a break counts for more than looks.
    }

    // ---------------------------------------------------------------- telling cows apart

    @Test
    fun aCowLearntTwiceIsStillNamed() {
        val f = Field()
        f.scan.start(gate = false)
        f.steps(10, row(3))
        f.scan.stop(f.now)
        assertEquals(3, f.herd.size)
        val first = f.names()[2]!!
        // Cow 2 gets a second entry (learnt again on a bad day).
        val again = f.herd.create(f.now)
        repeat(3) { f.herd.addView(again, f.lookOf(2), 1.0, f.now, null, 1.0, dup = 2.0) }
        // It looks like both entries: it's named as one of them (and marked unsure), not learnt a third time.
        val g = Field(f.herd, looks = f.looks, seed = 11)
        g.scan.start(gate = false)
        g.steps(14, mapOf(2 to Field.box(0.4)))
        assertEquals(4, g.herd.size, "not learnt a third time")
        assertTrue(g.names()[2] == first || g.names()[2] == again.id)
        assertTrue(g.scan.shown().single().unsure, "and it says it wasn't sure which")
        assertEquals(1, g.scan.session.count)
        // The two entries are offered for merging.
        assertEquals(setOf(first, again.id), HerdCheck.duplicates(g.herd, g.scan.bars().twin).first().let { setOf(it.a.id, it.b.id) })
    }

    @Test
    fun aCowThatLooksLikeTwoKnownCowsIsLearntAsNewNotGuessedAt() {
        // A wide margin, so that which of the two comes out ahead by a whisker doesn't matter.
        fun opt() = ScanOptions(margin = 0.15)
        val f = Field(opt = opt())
        f.scan.start(gate = false)
        f.steps(10, row(2))
        f.scan.stop(f.now)
        val learnt = f.names()
        // Cow 3 looks half like cow 1 and half like cow 2: neither stands out.
        val a = f.lookOf(1, noise = 0.0)
        val b = f.lookOf(2, noise = 0.0)
        f.looks[3] = normalize(FloatArray(Field.DIM) { a[it] + b[it] })
        val g = Field(f.herd, opt = opt(), looks = f.looks, seed = 12)
        g.scan.start(gate = false)
        g.steps(6, mapOf(3 to Field.box(0.4)))
        assertNull(g.names()[3], "no name while two cows look as likely")
        val s = g.scan.shown().single()
        assertTrue(s.bestScore > g.scan.bars().match && s.bestScore - s.secondScore < g.scan.bars().margin, "${s.bestScore} ${s.secondScore}")
        g.steps(30, mapOf(3 to Field.box(0.4)))
        assertEquals(3, g.herd.size, "learnt as a cow of its own")
        assertTrue(g.names()[3] != null && g.names()[3] !in learnt.values)
    }

    @Test
    fun inARealPlaceCowsAreToldApartThroughATuning() {
        val place = Place()
        // A tuning made beforehand from looks of other cows in such a place, each cow's looks together.
        val before = Field(place = place, seed = 3)
        val tuning = Tuning.fit((101..116).map { cow -> List(8) { before.lookOf(cow) } })
        assertNotNull(tuning)

        /** Twenty cows come past five at a time; later they come past again in another order. */
        fun count(tuned: Boolean): Field {
            // Without a tuning the bars must be strict: looks of different cows in one place are very alike.
            fun opt() = if (tuned) ScanOptions(match = 0.5, fresh = 0.4, margin = 0.1) else ScanOptions(match = 0.9, fresh = 0.8, margin = 0.04)
            val herd = Herd()
            if (tuned) herd.retune(tuning)
            val a = Field(herd, opt = opt(), place = place)
            a.scan.start(gate = false)
            val name = HashMap<Int, Int?>()
            for (g in 0 until 4) {
                a.steps(14, (0 until 5).associate { (1 + g * 5 + it) to Field.box(0.05 + 0.18 * it) })
                name.putAll(a.names())
                a.steps(10, emptyMap())
            }
            a.scan.stop(a.now)
            if (tuned) assertEquals(20, name.values.filterNotNull().toSet().size, "each cow learnt under its own name")
            val b = Field(herd, opt = opt(), looks = a.looks, seed = 31, place = place)
            b.scan.start(gate = false)
            val order = (1..20).shuffled(Random(4))
            var right = 0
            for (g in 0 until 4) {
                b.steps(16, (0 until 5).associate { order[g * 5 + it] to Field.box(0.05 + 0.18 * it, feet = 0.8) })
                right += b.names().count { (cow, id) -> id != null && id == name[cow] }
                b.steps(10, emptyMap())
            }
            b.scan.stop(b.now)
            println("  ${if (tuned) "through the tuning" else "plain"}: $right of 20 named right the second time, ${b.scan.session.fresh.size} learnt again, herd ${b.herd.size}")
            if (tuned) assertEquals(20, right, "every cow given the name it was learnt under")
            return b
        }
        val b = count(tuned = true)
        assertEquals(20, b.herd.size, "through the tuning: every cow known again, none learnt twice")
        assertEquals(0, b.scan.session.fresh.size)
        assertEquals(20, b.scan.session.count)
        val plain = count(tuned = false)
        assertTrue(plain.herd.size >= 25, "without it many of the same cows are learnt again as new ones (${plain.herd.size} entries for 20 cows)")
    }

    // ---------------------------------------------------------------- where the cows stand

    /**
     * A field wider than the picture, and a phone panning across it: cows stand where `cows` says (boxes in
     * picture widths and heights, the field starting at 0), the phone's view starts `at` picture widths
     * along and shows one width of it, magnified `zoom` times about its middle.
     */
    private class Pan(val f: Field, val cows: MutableMap<Int, DoubleArray>) {
        var at = 0.0
        var zoom = 1.0
        private var last = 0.0

        init {
            f.scan.slide = { Slide(-(at - last) * zoom, 0.0, sure = true).also { last = at } }
        }

        fun step() {
            f.scan.zoom = zoom
            val seen = LinkedHashMap<Int, DoubleArray>()
            for ((cow, b) in cows) {
                val x1 = 0.5 + (b[0] - at - 0.5) * zoom
                val x2 = 0.5 + (b[2] - at - 0.5) * zoom
                val y1 = 0.5 + (b[1] - 0.5) * zoom
                val y2 = 0.5 + (b[3] - 0.5) * zoom
                // In view if most of it is in the picture; what's outside is cut off.
                val inside = (minOf(1.0, x2) - maxOf(0.0, x1)) / (x2 - x1)
                if (inside < 0.6 || y1 < 0 || y2 > 1) continue
                seen[cow] = doubleArrayOf(maxOf(0.0, x1), y1, minOf(1.0, x2), y2)
            }
            f.step(seen)
        }

        fun to(x: Double, by: Double = 0.03) {
            while (kotlin.math.abs(at - x) > 1e-9) {
                at += (x - at).coerceIn(-by, by)
                step()
            }
        }

        fun stay(n: Int) = repeat(n) { step() }
    }

    @Test
    fun cowsStandingTooCloseToTellApartAreCountedByWhereTheyStandAsThePhonePans() {
        val f = Field()
        // Twelve in a row, shoulder to shoulder, across three picture widths.
        val pan = Pan(f, (1..12).associateWith { doubleArrayOf(0.02 + 0.25 * (it - 1), 0.4, 0.02 + 0.25 * (it - 1) + 0.24, 0.62) }.toMutableMap())
        f.scan.start(gate = false)
        pan.stay(6)
        pan.to(2.05)
        pan.stay(4)
        assertEquals(0, f.herd.size, "none stands clear enough to be looked at")
        assertEquals(12, f.scan.session.count, "each counted once on the way across")
        assertTrue(f.scan.session.peak <= 5, "though never more than four or five in view (${f.scan.session.peak})")
        pan.to(0.0)
        pan.to(1.0)
        assertEquals(12, f.scan.session.count, "and not again on the way back")
        assertEquals(12, f.scan.places.size)
        assertEquals(1.0, f.scan.panX, 1e-6)
    }

    @Test
    fun aCowWhoseBoxIsLostAndFoundIsCountedOnce() {
        val f = Field()
        // Three small cows far off (too small to recognise), seen on and off, never all three together.
        val a = Field.box(0.2, w = 0.03, h = 0.05)
        val b = Field.box(0.5, w = 0.03, h = 0.05)
        val c = Field.box(0.8, w = 0.03, h = 0.05)
        f.scan.start(gate = false)
        repeat(3) {
            f.steps(8, mapOf(1 to a, 2 to b))
            f.steps(10, emptyMap())
            f.steps(8, mapOf(2 to b, 3 to c))
            f.steps(10, emptyMap())
            f.steps(8, mapOf(1 to a, 3 to c))
            f.steps(10, emptyMap())
        }
        assertEquals(0, f.herd.size)
        assertEquals(2, f.scan.session.peak)
        assertEquals(3, f.scan.session.count, "three places, however often each came and went")
    }

    @Test
    fun aNamedCowThatHasMovedIsStillOneCow() {
        val f = Field()
        val pan = Pan(f, mutableMapOf(1 to Field.box(0.3), 2 to Field.box(0.6, w = 0.03, h = 0.05)))
        f.scan.start(gate = false)
        pan.stay(10)
        assertEquals(1, f.herd.size, "the cow standing clear is learnt; the far one is only counted")
        assertEquals(2, f.scan.session.count)
        // The phone turns away; meanwhile the cow walks along the field; the phone finds it again there.
        pan.to(1.2)
        pan.cows[1] = Field.box(1.9)
        pan.to(1.5)
        pan.stay(10)
        assertEquals(1, f.herd.size, "known by its looks")
        assertEquals(2, f.scan.session.count, "one cow, though it has stood in two places")
        assertEquals(3, f.scan.places.size)
    }

    @Test
    fun aKnownCowThatHasMovedIsNotCountedAsAnotherWhileItIsBeingLookedAt() {
        val f = Field()
        f.scan.start(gate = false)
        // A cow far off (so that the count isn't simply the most in view at once), and one learnt.
        f.steps(8, mapOf(9 to Field.box(0.45, feet = 0.3, w = 0.03, h = 0.05)))
        f.steps(10, emptyMap())
        f.steps(10, mapOf(1 to Field.box(0.1)))
        f.steps(10, emptyMap())
        assertEquals(2, f.scan.session.count)
        // The learnt cow turns up somewhere else. The recognition model is slow: its answers come a
        // second and a half late, long after the cow has been seen standing in its new place.
        val late = ArrayDeque<Pair<Int, io.github.ndev.flockeyes.core.count.Look>>()
        var most = 0
        for (i in 0 until 25) {
            val out = f.scan.frame(listOf(Det("cow", 0.9, Field.box(0.6))), BlankFrame(), f.t, f.now, budget = 4)
            for (lk in out.looks) late.addLast(i to lk)
            while (late.isNotEmpty() && late.first().first <= i - 7) f.scan.look(late.removeFirst().second, f.lookOf(1), f.now)
            f.t += 200.0
            f.now += 200
            most = maxOf(most, f.scan.session.count)
        }
        assertEquals(1, f.herd.size, "known again")
        assertEquals(2, most, "and never counted as a third cow while it waited to be named")
        // A cow that can't be looked at (too far off) doesn't wait: it counts as soon as it's surely there.
        f.steps(7, mapOf(8 to Field.box(0.85, feet = 0.3, w = 0.03, h = 0.05)))
        assertEquals(3, f.scan.session.count)
    }

    @Test
    fun aCowFoundAStepFromWhereItStoodTakesItsOldPlace() {
        val f = Field()
        // Two cows far off (too small to recognise). One stays in view throughout.
        val other = Field.box(0.80, feet = 0.3, w = 0.03, h = 0.05)
        f.scan.start(gate = false)
        f.steps(8, mapOf(1 to Field.box(0.300, w = 0.03, h = 0.05), 9 to other))
        assertEquals(2, f.scan.session.count)
        // The first one's box is lost for two seconds, its place in plain view with no cow on it; then
        // there is a cow most of a cow's length along from it.
        f.steps(10, mapOf(9 to other))
        f.steps(8, mapOf(1 to Field.box(0.325, w = 0.03, h = 0.05), 9 to other))
        assertEquals(2, f.scan.session.count, "the same cow, a step along: not another")
        assertEquals(2, f.scan.places.size)
        // But a cow turning up beside a place that emptied only a moment ago is another cow.
        f.steps(2, mapOf(9 to other))
        f.steps(8, mapOf(2 to Field.box(0.350, w = 0.03, h = 0.05), 9 to other))
        assertEquals(3, f.scan.session.count)
    }

    @Test
    fun zoomingDoesNotMoveTheCows() {
        val f = Field()
        val pan = Pan(f, (1..4).associateWith { doubleArrayOf(0.30 + 0.105 * (it - 1), 0.45, 0.30 + 0.105 * (it - 1) + 0.1, 0.55) }.toMutableMap())
        f.scan.start(gate = false)
        pan.stay(8)
        assertEquals(4, f.scan.session.count)
        pan.zoom = 2.0
        pan.stay(8)
        pan.zoom = 1.0
        pan.stay(8)
        assertEquals(4, f.scan.session.count, "the same four, bigger and smaller")
        assertEquals(4, f.scan.places.size)
    }

    @Test
    fun whenThePictureGivesNothingToGoByTheCowsThemselvesDo() {
        val f = Field()
        val pan = Pan(f, (1..12).associateWith { doubleArrayOf(0.02 + 0.25 * (it - 1), 0.4, 0.02 + 0.25 * (it - 1) + 0.24, 0.62) }.toMutableMap())
        // Mist, or bare ground: no fit to be had from the picture.
        f.scan.slide = { Slide(0.0, 0.0, sure = false) }
        f.scan.start(gate = false)
        pan.stay(6)
        pan.to(2.05)
        pan.stay(4)
        assertEquals(2.05, f.scan.panX, 0.02, "the phone's turning is told from how the cows slide across the picture")
        assertEquals(12, f.scan.session.count)
        pan.to(0.0)
        pan.stay(4)
        assertEquals(12, f.scan.session.count, "and not again on the way back")
    }

    @Test
    fun aBoxThatFlickersInForAMomentIsNotACow() {
        val f = Field()
        val cow = Field.box(0.2, w = 0.03, h = 0.05)
        val bush = Field.box(0.7, w = 0.03, h = 0.05)
        f.scan.start(gate = false)
        f.steps(6, mapOf(1 to cow))
        f.steps(4, mapOf(1 to cow, 2 to bush))
        f.steps(12, mapOf(1 to cow))
        assertEquals(1, f.scan.session.count, "a box there for under a second isn't counted")
        f.steps(8, mapOf(1 to cow, 2 to bush))
        assertEquals(2, f.scan.session.count, "one that stays is")
    }

    @Test
    fun cowsInAHeapAreCountedByHowManyBoxesThereWereAtOnce() {
        val f = Field()
        // Three cows standing half in front of each other: the cow finder boxes them now singly, now two as one.
        val a = doubleArrayOf(0.30, 0.40, 0.50, 0.62)
        val b = doubleArrayOf(0.42, 0.38, 0.62, 0.60)
        val c = doubleArrayOf(0.55, 0.42, 0.75, 0.64)
        val ab = doubleArrayOf(0.30, 0.38, 0.62, 0.62)
        val bc = doubleArrayOf(0.42, 0.38, 0.75, 0.64)
        f.scan.start(gate = false)
        repeat(4) {
            f.steps(6, mapOf(1 to a, 2 to b, 3 to c))
            f.steps(5, mapOf(4 to ab, 3 to c))
            f.steps(5, mapOf(1 to a, 5 to bc))
            f.steps(5, mapOf(2 to b, 3 to c))
            // Singly and as one at the same time: the box round two of them isn't a fourth cow.
            f.steps(5, mapOf(1 to a, 2 to b, 4 to ab, 3 to c))
            f.steps(9, emptyMap())
        }
        assertEquals(3, f.scan.session.peak)
        assertEquals(3, f.scan.session.count, "three, however the boxes came and went")
        assertEquals(0, f.herd.size, "none stood clear enough to be looked at")
    }

    @Test
    fun aNamedBoxThatSlidesOntoTheNextCowDoesNotHideIt() {
        val f = Field()
        val a = doubleArrayOf(0.30, 0.38, 0.52, 0.62)
        val b = doubleArrayOf(0.46, 0.40, 0.62, 0.62)
        val both = doubleArrayOf(0.30, 0.38, 0.62, 0.62)
        f.scan.start(gate = false)
        // A cow far off, seen for a while (so that the count isn't simply the most in view at once).
        f.steps(8, mapOf(9 to Field.box(0.85, w = 0.03, h = 0.05)))
        f.steps(10, emptyMap())
        f.steps(10, mapOf(1 to a))
        assertEquals(1, f.herd.size, "a cow standing clear is learnt")
        // Another comes to stand half behind it: counted by its place, never looked at.
        f.steps(8, mapOf(1 to a, 2 to b))
        assertEquals(3, f.scan.session.count)
        // The two are boxed as one for a while, and then the box settles on the second cow alone.
        f.steps(9, mapOf(3 to both))
        f.steps(12, mapOf(2 to b))
        val onB = f.scan.tracker.tracks.single()
        assertEquals(1, f.herd.size)
        assertTrue(onB.cowId != null && f.truth[onB.id] == 2, "the box that was on the first cow is on the second now, still under the first cow's name")
        assertEquals(2, f.scan.session.peak)
        assertEquals(3, f.scan.session.count, "but the second cow's place isn't taken for the first cow's: still three")
    }

    @Test
    fun aLookAlikeTakenForACowElsewhereStillCountsOnceBothAreSeen() {
        fun field(): Field {
            val f = Field()
            // The second cow looks, to the recognition model, just like the first.
            f.disguise[2] = 1
            f.scan.start(gate = false)
            // A cow far off, so that the count isn't simply the most in view at once.
            f.steps(8, mapOf(9 to Field.box(0.45, feet = 0.3, w = 0.03, h = 0.05)))
            f.steps(10, emptyMap())
            f.steps(10, mapOf(1 to Field.box(0.1)))
            f.steps(10, emptyMap())
            f.steps(10, mapOf(2 to Field.box(0.7)))
            assertEquals(1, f.herd.size, "taken for the first cow")
            assertEquals(2, f.scan.session.count, "which has moved, for all the app can tell")
            return f
        }
        // Both in view at once: two places with a cow on each are two cows.
        val a = field()
        a.steps(10, mapOf(1 to Field.box(0.1), 2 to Field.box(0.7)))
        assertEquals(3, a.scan.session.count)
        assertEquals(1, a.herd.size, "the look-alike isn't learnt: the two can't be told apart")
        // Or the first cow is found again where it was: it never moved, so the other is a look-alike.
        val b = field()
        b.steps(10, emptyMap())
        b.steps(10, mapOf(1 to Field.box(0.1)))
        assertEquals(3, b.scan.session.count)
        // And once that is known, the look-alike isn't taken for it again.
        b.steps(10, emptyMap())
        b.steps(10, mapOf(2 to Field.box(0.7)))
        assertEquals(3, b.scan.session.count)
    }

    @Test
    fun swungRoundTooFastToFollowWhatWasCountedStaysCounted() {
        val f = Field()
        var lost = false
        f.scan.slide = { Slide(0.0, 0.0, sure = !lost, lost = lost) }
        val far = (1..3).associateWith { Field.box(0.1 + 0.3 * (it - 1), w = 0.03, h = 0.05) }
        f.scan.start(gate = false)
        f.steps(8, far)
        assertEquals(3, f.scan.session.count)
        // The picture can't be followed for a moment, but the cows are still in view, where they were:
        // they vouch for where the phone points, and nothing is lost.
        lost = true
        f.steps(1, far)
        lost = false
        f.steps(6, far)
        assertEquals(3, f.scan.session.count)
        assertEquals(0, f.scan.swings)
        // Swung away and back in one go: where the phone points now can't be known, so cows seen from
        // here on are taken for others. (Counting twice is the lesser evil: it shows, and can be put right.)
        f.steps(8, emptyMap())
        lost = true
        f.steps(1, emptyMap())
        lost = false
        f.steps(8, far)
        assertEquals(6, f.scan.session.count)
        assertEquals(1, f.scan.swings)
        // Lost with one cow still followed (one alone can't vouch: it may be walking): it keeps its place.
        val one = mapOf(1 to far.getValue(1))
        f.steps(12, one)
        lost = true
        f.steps(1, one)
        lost = false
        f.steps(6, one)
        assertEquals(2, f.scan.swings)
        assertEquals(6, f.scan.session.count, "a cow followed through it isn't counted again")
    }

    // ---------------------------------------------------------------- gate

    private fun gate(opt: ScanOptions = ScanOptions()): Field {
        val f = Field(opt = opt)
        f.scan.line = Gate.default()
        f.scan.region = Gate.region(Gate.default(), 9.0 / 16)
        f.scan.start(gate = true)
        return f
    }

    /** Walks a cow from x0 to x1 (left edge of its box) in `n` frames, then out of view until its track ends. */
    private fun walk(f: Field, cow: Int, x0: Double, x1: Double, n: Int = 24) {
        for (i in 0..n) f.step(mapOf(cow to Field.box(x0 + (x1 - x0) * i / n, feet = 0.8, w = 0.2, h = 0.3)))
        f.steps(10, emptyMap())
    }

    @Test
    fun gateCountsEachWayAndKnowsWhoWentThrough() {
        val f = gate()
        walk(f, 1, 0.05, 0.75)
        assertEquals(listOf(1, 1, 0), f.scan.session.tally.toList())
        assertEquals(1, f.herd.size, "learnt as it went through")
        val s = f.scan.session.sightings.single()
        assertEquals(1, s.dir)
        assertTrue(s.fresh)
        val id = s.cowId
        assertNotNull(id)
        // A second cow the other way, then the first one back.
        walk(f, 2, 0.75, 0.05)
        walk(f, 1, 0.75, 0.05)
        assertEquals(listOf(3, 1, 2), f.scan.session.tally.toList())
        assertEquals(2, f.herd.size)
        val back = f.scan.session.sightings.last()
        assertEquals(id, back.cowId, "the first cow recognised on its way back")
        assertEquals(2, back.dir)
        assertTrue(!back.fresh)
        assertEquals(mapOf(id to 2, f.scan.session.sightings[1].cowId!! to 2), f.scan.lastDirections())
        assertEquals(2, f.herd[id]!!.seen)
    }

    @Test
    fun aCowStandingOnTheLineIsNotCountedBackAndForth() {
        val f = gate()
        val r = Random(3)
        // Feet on the line, the box wobbling a little either way.
        for (i in 0 until 40) f.step(mapOf(1 to Field.box(0.4 + (r.nextDouble() - 0.5) * 0.02, feet = 0.8, w = 0.2, h = 0.3)))
        assertEquals(0, f.scan.session.tally[0])
        f.steps(10, emptyMap())

        // One that turns up just short of the line, dithers, then walks on through: counted once.
        for (i in 0 until 20) f.step(mapOf(2 to Field.box(0.385 + (r.nextDouble() - 0.5) * 0.02, feet = 0.8, w = 0.2, h = 0.3)))
        assertEquals(0, f.scan.session.tally[0])
        for (i in 0..12) f.step(mapOf(2 to Field.box(0.385 + 0.3 * i / 12, feet = 0.8, w = 0.2, h = 0.3)))
        assertEquals(listOf(1, 1, 0), f.scan.session.tally.toList())
    }

    @Test
    fun gateDoesNotLearnCowsThatOnlyStandNearby() {
        val f = gate()
        f.steps(30, mapOf(1 to Field.box(0.1, feet = 0.8, w = 0.2, h = 0.3)))
        f.steps(10, emptyMap())
        assertEquals(0, f.herd.size)
        assertEquals(0, f.scan.session.tally[0])
        assertTrue(f.scan.session.sightings.isEmpty())
    }

    @Test
    fun aCowPassingBeyondTheEndOfTheLineIsNotCounted() {
        val f = gate()
        // The line runs from y = 0.2 to 0.95; this one walks along the very top of the picture.
        for (i in 0..24) f.step(mapOf(1 to Field.box(0.05 + 0.7 * i / 24, feet = 0.1, w = 0.1, h = 0.08)))
        assertEquals(0, f.scan.session.tally[0])
    }

    @Test
    fun gateWithLearningOffCountsStrangersAsUnrecognised() {
        val f = gate(ScanOptions(learn = false))
        walk(f, 1, 0.05, 0.75)
        assertEquals(1, f.scan.session.tally[0])
        assertEquals(0, f.herd.size)
        assertNull(f.scan.session.sightings.single().cowId)
        assertEquals(1, f.scan.session.unknown)
    }

    @Test
    fun stoppingAGateCountSavesCowsStillInView() {
        val f = gate()
        for (i in 0..24) f.step(mapOf(1 to Field.box(0.05 + 0.7 * i / 24, feet = 0.8, w = 0.2, h = 0.3)))
        assertTrue(f.scan.session.sightings.isEmpty(), "not saved while the cow is still in view")
        val saved = f.scan.stop(f.now)
        assertEquals(1, saved.size)
        assertNotNull(saved[0].cowId)
    }

    @Test
    fun gateGeometry() {
        val up = Gate.default()
        val a = 9.0 / 16
        assertTrue(!Gate.isLevel(up, a))
        assertEquals("Left to right" to "Right to left", Gate.directionNames(up, a))
        val right = Gate.where(up, doubleArrayOf(0.7, 0.5), a)
        val left = Gate.where(up, doubleArrayOf(0.3, 0.5), a)
        assertTrue(right.first * left.first < 0, "either side has opposite signs")
        assertEquals(0.2, Math.abs(right.first), 1e-9)
        assertEquals(0.4, right.second, 1e-9)
        assertEquals(if (right.first > 0) 1 else -1, Gate.sideOfDirection1(up, a))
        val level = Gate.level()
        assertTrue(Gate.isLevel(level, a))
        assertEquals("Towards me" to "Away from me", Gate.directionNames(level, a))
        val below = Gate.where(level, doubleArrayOf(0.5, 0.9), a)
        assertEquals(if (below.first > 0) 1 else -1, Gate.sideOfDirection1(level, a))
        val r = Gate.region(up, a)
        assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 1.0 + 1e-9 && r[1] + r[3] <= 1.0 + 1e-9)
        // A level line drawn right to left behaves the same.
        val flipped = doubleArrayOf(0.85, 0.6, 0.15, 0.6)
        val below2 = Gate.where(flipped, doubleArrayOf(0.5, 0.9), a)
        assertEquals(if (below2.first > 0) 1 else -1, Gate.sideOfDirection1(flipped, a))
    }

    // ---------------------------------------------------------------- herd

    @Test
    fun mergingTwoEntriesOfOneCow() {
        val events = ArrayList<String>()
        val herd = Herd(maxViews = 4)
        herd.listener = object : HerdListener {
            override fun cowAdded(cow: Cow) { events.add("added ${cow.id}") }
            override fun cowRemoved(id: Int) { events.add("removed $id") }
            override fun merged(into: Cow, from: Int) { events.add("merged $from into ${into.id}") }
            override fun viewAdded(cow: Cow, view: View, px: IntArray?, aspect: Double) { events.add("view ${cow.id}") }
            override fun viewRemoved(cow: Cow, view: View) { events.add("unview ${cow.id}") }
        }
        val f = Field(herd)
        val a = herd.create(1)
        val b = herd.create(2)
        repeat(3) { herd.addView(a, f.lookOf(1), 1.0, 1, null, 1.0, dup = 2.0) }
        repeat(3) { herd.addView(b, f.lookOf(2), 1.0, 2, null, 1.0, dup = 2.0) }
        herd.rename(b, "Daisy", "IE 1234")
        b.seen = 4
        val v = herd.version
        herd.merge(a, b)
        assertEquals(1, herd.size)
        assertNull(herd[b.id])
        assertEquals("Daisy", a.name)
        assertEquals("IE 1234", a.tag)
        assertEquals("IE 1234", a.label)
        assertEquals(4, a.seen)
        assertEquals(4, a.views.size, "thinned to the limit")
        assertTrue(herd.version > v)
        assertTrue("merged ${b.id} into ${a.id}" in events)
        assertEquals(2, events.count { it == "unview ${a.id}" })
        // A new cow never takes a merged cow's number.
        assertEquals(3, herd.create(3).id)
    }

    @Test
    fun aFullSetOfLooksDropsTheOneThatAddsLeast() {
        val herd = Herd(maxViews = 3)
        val f = Field(herd)
        val c = herd.create(1)
        val first = f.lookOf(1, noise = 0.0)
        assertTrue(herd.addView(c, first, 1.0, 1, null, 1.0))
        assertTrue(!herd.addView(c, f.lookOf(1, noise = 0.05), 1.0, 1, null, 1.0), "nearly the same look isn't kept twice")
        assertTrue(herd.addView(c, f.lookOf(1, noise = 0.6), 1.0, 1, null, 1.0))
        assertTrue(herd.addView(c, f.lookOf(1, noise = 0.6), 1.0, 1, null, 1.0))
        herd.addView(c, f.lookOf(1, noise = 0.6), 1.0, 1, null, 1.0)
        assertEquals(3, c.views.size)
    }

    @Test
    fun cowsLearntTwiceAreFound() {
        val herd = Herd()
        val f = Field(herd)
        val cows = (1..4).map { k -> herd.create(1).also { c -> repeat(2) { herd.addView(c, f.lookOf(k), 1.0, 1, null, 1.0, dup = 2.0) } } }
        // A fifth entry that is really cow 2 again.
        val again = herd.create(1)
        herd.addView(again, f.lookOf(2), 1.0, 1, null, 1.0)
        val dups = HerdCheck.duplicates(herd, 0.5)
        assertEquals(1, dups.size)
        assertEquals(setOf(cows[1].id, again.id), setOf(dups[0].a.id, dups[0].b.id))
        assertEquals(again.id, HerdCheck.nearest(herd, cows[1], 1)[0].b.id)
        val nn = HerdCheck.nearestScores(herd)
        assertEquals(5, nn.size)
        assertTrue(nn.first() < 0.5 && nn.last() > 0.5)
    }
}
