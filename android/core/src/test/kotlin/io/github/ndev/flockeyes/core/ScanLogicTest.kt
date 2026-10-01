package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.FrameOut
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.Scan
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.herd.Cow
import io.github.ndev.flockeyes.core.herd.Herd
import io.github.ndev.flockeyes.core.herd.HerdCheck
import io.github.ndev.flockeyes.core.herd.HerdListener
import io.github.ndev.flockeyes.core.herd.View
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.ReidConfig
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
class Field(val herd: Herd = Herd(), val opt: ScanOptions = ScanOptions(), val looks: HashMap<Int, FloatArray> = HashMap(), seed: Long = 7) {
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
        return normalize(FloatArray(DIM) { (b[it] + noise * rnd.nextGaussian() / Math.sqrt(DIM.toDouble())).toFloat() })
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
        f.steps(14, mapOf(1 to Field.box(0.1), 2 to Field.box(0.6)))
        val names = f.names()
        assertNotNull(names[1])
        assertNotNull(names[2], "the double is learnt as a cow of its own")
        assertNotEquals(names[1], names[2])
        assertEquals(2, f.scan.session.count)
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
    fun aTrackThatJumpsToAnotherCowIsRenamed() {
        val f = Field(opt = ScanOptions(checkGapMs = 300.0))
        f.scan.start(gate = false)
        f.steps(10, row(2))
        f.scan.stop(f.now)
        val learnt = f.names()
        // Next count: cow 1 alone, then the same track starts looking like cow 2 (the tracker slipped).
        val g = Field(f.herd, opt = ScanOptions(checkGapMs = 300.0), looks = f.looks)
        g.scan.start(gate = false)
        g.steps(6, mapOf(1 to Field.box(0.3)))
        assertEquals(learnt[1], g.names()[1])
        g.disguise[1] = 2
        g.steps(20, mapOf(1 to Field.box(0.3)))
        assertEquals(learnt[2], g.names()[1], "renamed to the cow it now looks like")
        assertEquals(2, g.herd.size, "and nothing new was learnt")
        assertEquals(2, g.scan.session.count, "both were seen")
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
