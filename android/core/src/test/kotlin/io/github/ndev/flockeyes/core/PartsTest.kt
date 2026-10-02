package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.PanEstimator
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.report.CountInfo
import io.github.ndev.flockeyes.core.report.CowInfo
import io.github.ndev.flockeyes.core.report.Csv
import io.github.ndev.flockeyes.core.report.SightingInfo
import io.github.ndev.flockeyes.core.reid.ReidConfig
import io.github.ndev.flockeyes.core.reid.Tuning
import io.github.ndev.flockeyes.core.reid.dot
import io.github.ndev.flockeyes.core.reid.normalize
import io.github.ndev.flockeyes.core.track.Tracker
import org.junit.Test
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The smaller parts: joining the two halves of a frame, following boxes, exports and settings. */
class PartsTest {
    private fun det(x1: Double, y1: Double, x2: Double, y2: Double, score: Double = 0.8, cut: Boolean = false) = Det("cow", score, doubleArrayOf(x1, y1, x2, y2), cut)

    @Test
    fun halvesOfTheFrame() {
        assertEquals(1, CowDetector.tiles(9.0 / 16, far = false).size)
        val wide = CowDetector.tiles(9.0 / 16, far = true)
        assertEquals(listOf(0.0, 0.0, 0.6, 1.0), wide[0].toList())
        assertEquals(listOf(0.4, 0.0, 0.6, 1.0), wide[1].toList())
        val tall = CowDetector.tiles(16.0 / 9, far = true)
        assertEquals(listOf(0.0, 0.4, 1.0, 0.6), tall[1].toList())
    }

    @Test
    fun aCowAcrossTheJoinIsPutBackTogether() {
        // The left half sees its front (cut off at 0.6), the right half its back (cut off at 0.4).
        val pieces = listOf(det(0.30, 0.40, 0.60, 0.70, 0.7, cut = true), det(0.40, 0.41, 0.72, 0.69, 0.8, cut = true), det(0.05, 0.4, 0.2, 0.6))
        val out = CowDetector.nms(CowDetector.stitch(pieces, vertical = false))
        assertEquals(2, out.size)
        val whole = out.first { it.box[0] > 0.2 }
        assertEquals(listOf(0.30, 0.40, 0.72, 0.70), whole.box.toList())
        assertTrue(!whole.cut)
        assertEquals(0.8, whole.score)
    }

    @Test
    fun twoCowsEitherSideOfTheJoinStayTwo() {
        // One above the other: not the same cow.
        val a = det(0.30, 0.10, 0.60, 0.30, cut = true)
        val b = det(0.40, 0.60, 0.70, 0.85, cut = true)
        assertEquals(2, CowDetector.stitch(listOf(a, b), vertical = false).size)
        // Halves one above the other: pieces must line up across, and meet up and down.
        val top = det(0.30, 0.35, 0.50, 0.60, cut = true)
        val bottom = det(0.31, 0.40, 0.51, 0.75, cut = true)
        val joined = CowDetector.stitch(listOf(top, bottom), vertical = true)
        assertEquals(1, joined.size)
        assertEquals(listOf(0.30, 0.35, 0.51, 0.75), joined[0].box.toList())
    }

    @Test
    fun aPieceOfACowThatIsWholeInTheOtherHalfIsDropped() {
        val whole = det(0.42, 0.4, 0.62, 0.6, 0.7)
        val piece = det(0.42, 0.4, 0.60, 0.6, 0.9, cut = true)
        val out = CowDetector.nms(listOf(whole, piece))
        assertEquals(1, out.size)
        assertTrue(!out[0].cut, "the whole box wins even with the lower score")
        // And a small box inside a bigger one goes.
        assertEquals(1, CowDetector.nms(listOf(det(0.1, 0.1, 0.5, 0.5, 0.9), det(0.2, 0.2, 0.4, 0.4, 0.6))).size)
        assertEquals(2, CowDetector.nms(listOf(det(0.1, 0.1, 0.3, 0.3), det(0.5, 0.1, 0.7, 0.3))).size)
    }

    @Test
    fun boxesAreFollowedFromFrameToFrame() {
        val tr = Tracker()
        // Two cows walking towards each other's side, 5 frames a second.
        for (i in 0..10) {
            val x = 0.1 + 0.02 * i
            tr.update(listOf(det(x, 0.3, x + 0.15, 0.5), det(0.7 - 0.02 * i, 0.6, 0.85 - 0.02 * i, 0.8)), 1000.0 + i * 200)
        }
        assertEquals(listOf(1, 2), tr.tracks.map { it.id })
        assertEquals(listOf(11, 11), tr.tracks.map { it.hits })
        assertTrue(tr.tracks[0].vx > 0 && tr.tracks[1].vx < 0)
        // A jump (the phone swung round): still the same cows, as they're near where they were and the same size.
        tr.update(listOf(det(0.42, 0.3, 0.57, 0.5), det(0.60, 0.6, 0.75, 0.8)), 3200.0)
        assertEquals(listOf(12, 12), tr.tracks.map { it.hits })
        // Out of view: tracks end after a second and a half.
        val u1 = tr.update(emptyList(), 4000.0)
        assertEquals(2, tr.tracks.size)
        assertTrue(u1.lost.isEmpty())
        val u2 = tr.update(emptyList(), 4800.0)
        assertEquals(2, u2.lost.size)
        assertTrue(tr.tracks.isEmpty())
        // A cow that turns up afterwards is a new track.
        tr.update(listOf(det(0.42, 0.3, 0.57, 0.5)), 5000.0)
        assertEquals(3, tr.tracks.single().id)
        // And so is one standing in the same place after a pause with no frames at all (the camera stopped).
        val u3 = tr.update(listOf(det(0.42, 0.3, 0.57, 0.5)), 65_000.0)
        assertEquals(4, tr.tracks.single().id)
        assertEquals(listOf(3), u3.lost.map { it.id })
    }

    @Test
    fun exportsOpenInASpreadsheet() {
        val utc = ZoneId.of("UTC")
        val field = CountInfo(1, "field", "Top field, by the \"oak\"", 1_700_000_000_000, 1_700_000_090_000, "live", 23, 21, 1, 2, 40)
        val gate = CountInfo(2, "gate", "Parlour", 1_700_003_600_000, 1_700_007_200_000, "live", 30, 0, 0, 0, 40, "Out to the field", "Back in", 28, 2)
        val csv = Csv.counts(listOf(field, gate), utc).split("\r\n")
        assertEquals("count,type,name,started,ended,from,cows,most_in_view,unrecognised,new_cows,herd_size,direction_1,went_1,direction_2,went_2,note", csv[0])
        assertEquals("1,field,\"Top field, by the \"\"oak\"\"\",2023-11-14 22:13:20,2023-11-14 22:14:50,live,23,21,1,2,40,,,,,", csv[1])
        assertEquals("2,gate,Parlour,2023-11-14 23:13:20,2023-11-15 00:13:20,live,30,,0,0,40,Out to the field,28,Back in,2,", csv[2])
        val rows = Csv.sightings(
            gate,
            listOf(SightingInfo(1_700_003_700_000, 7, "Daisy", "IE 1234", 1, 0.8349, true), SightingInfo(1_700_003_800_000, null, "", "", 2, 0.0, false)),
            utc,
        ).split("\r\n")
        assertEquals("time,cow,tag,cow_number,direction,match,new", rows[0])
        assertEquals("2023-11-14 23:15:00,Daisy,IE 1234,7,Out to the field,0.83,yes", rows[1])
        assertEquals("2023-11-14 23:16:40,Unrecognised,,,Back in,,", rows[2])
        val herd = Csv.herd(listOf(CowInfo(7, "Daisy", "IE 1234", "lame, left hind", 1_700_000_000_000, 1_700_003_700_000, 3, 5)), utc).split("\r\n")
        assertEquals("7,Daisy,IE 1234,\"lame, left hind\",2023-11-14 22:13:20,2023-11-14 23:15:00,3,5", herd[1])
    }

    @Test
    fun recognitionSettingsAreRead() {
        val c = ReidConfig.parse("""{"key":"megadescriptor-t-224-int8-1","size":224,"mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5],"dim":768,"match":0.62,"fresh":0.54,"margin":0.04,"name":"MegaDescriptor-T-224","licence":"CC BY-NC 4.0"}""")
        assertEquals("megadescriptor-t-224-int8-1", c.key)
        assertEquals(0.62, c.match)
        assertEquals(0.54, c.fresh)
        assertEquals(768, c.dim)
        // Without a "fresh" threshold it sits a little under "match".
        assertEquals(0.5, ReidConfig.parse("""{"match":0.6}""").fresh, 1e-9)
    }

    // ---------------------------------------------------------------- the tuning

    @Test
    fun aTuningNeedsEnoughLooksOfTheSameAnimals() {
        val r = Random(1)
        fun look() = normalize(FloatArray(32) { r.nextGaussian().toFloat() })
        assertNull(Tuning.fit(List(13) { List(3) { look() } }), "39 looks are too few")
        assertNotNull(Tuning.fit(List(14) { List(3) { look() } }))
        // Looks on their own say nothing about how one animal's looks differ.
        assertNull(Tuning.fit(List(200) { listOf(look()) }))
        // Nor do looks that are all the same.
        val one = look()
        assertNull(Tuning.fit(List(20) { List(3) { one } }))
    }

    @Test
    fun aTuningScalesDownWhatLooksOfOneAnimalVaryAlongAndLeavesTheRestAlone() {
        // 200 animals, four looks each. An animal's looks vary a lot along the first three axes (5, 3 and
        // 2 times the rest). Which animal it is shows along axis 20; axis 7 is the same for every look.
        val r = Random(2)
        val d = 40
        val sd = DoubleArray(d) { 0.1 }
        sd[0] = 0.5
        sd[1] = 0.3
        sd[2] = 0.2
        val groups = List(200) {
            val who = 2.0 * r.nextGaussian()
            List(4) { FloatArray(d) { i -> ((if (i == 7) 3.0 else if (i == 20) who else 0.0) + sd[i] * r.nextGaussian()).toFloat() } }
        }
        val t = Tuning.fit(groups, directions = 6, shrink = 0.0)!!
        assertEquals(800, t.looks)
        assertEquals(6, t.basis.size)
        assertEquals(3.0, t.mean[7].toDouble(), 0.02)
        for (k in 0..2) assertTrue(abs(t.basis[k][k]) > 0.98, "direction $k is axis $k (${t.basis[k][k]})")
        for (a in 0 until 6) for (b in 0 until 6) assertEquals(if (a == b) 1.0 else 0.0, dot(t.basis[a], t.basis[b]), 1e-4)
        for (k in 0..2) assertTrue(abs(t.basis[k][20]) < 0.1, "what tells the animals apart isn't one of them")
        // Each is scaled by one over its spread, relative to the rest's (0.1): 0.1/0.5 - 1, and so on.
        assertEquals(0.1 / 0.5 - 1, t.gains[0].toDouble(), 0.04)
        assertEquals(0.1 / 0.3 - 1, t.gains[1].toDouble(), 0.04)
        assertEquals(0.1 / 0.2 - 1, t.gains[2].toDouble(), 0.04)
        // A look with as much along axis 0 (how it stands) as along axis 20 (who it is): after tuning,
        // who it is counts five times as much.
        val x = FloatArray(d).also { it[7] = 3f; it[0] = 1f; it[20] = 1f }
        val y = t.apply(x)
        assertEquals(1.0, sqrt(dot(y, y)), 1e-4)
        assertEquals(5.0, (y[20] / y[0]).toDouble(), 0.6)
        assertTrue(abs(y[7]) < 0.08, "what every look has in common is gone (${y[7]})")
        // Shrinking pulls every scale towards the average, so nothing is blown up.
        val shrunk = Tuning.fit(groups, directions = 6, shrink = 0.3)!!
        assertTrue(shrunk.gains[0] > t.gains[0] && shrunk.gains[0] < 0)
        // Saved and read back, it does the same.
        val back = Tuning.read(shrunk.toBytes())
        assertEquals(shrunk.looks, back.looks)
        assertTrue(dot(shrunk.apply(x), back.apply(x)) > 0.999999)
        assertTrue(runCatching { Tuning.read(ByteArray(40)) }.isFailure)
    }

    @Test
    fun aTuningTellsCowsApartWhereThePlainDescriptionsCant() {
        val place = Place(dim = 64, changing = 2.0)
        val f = Field(place = place)
        // Made from looks of twenty cows; tried on twelve others.
        val t = Tuning.fit((101..120).map { cow -> List(8) { f.lookOf(cow) } })!!
        val cows = 12
        val looks = (1..cows).flatMap { cow -> List(12) { cow to f.lookOf(cow) } }
        fun nearestIsSameCow(see: (FloatArray) -> FloatArray): Int {
            val e = looks.map { see(it.second) }
            return looks.indices.count { i ->
                val j = looks.indices.filter { it != i }.maxByOrNull { dot(e[i], e[it]) }!!
                looks[j].first == looks[i].first
            }
        }
        val plain = nearestIsSameCow { it }
        val tuned = nearestIsSameCow { t.apply(it) }
        val same = (0 until 200).map { dot(t.apply(f.lookOf(1 + it % cows)), t.apply(f.lookOf(1 + it % cows))) }.average()
        val other = (0 until 200).map { dot(t.apply(f.lookOf(1 + it % cows)), t.apply(f.lookOf(1 + (it + 1) % cows))) }.average()
        println("  nearest look is the same cow: plain $plain of ${looks.size}, tuned $tuned; tuned: two looks of one cow %.2f alike on average, of different cows %.2f".format(same, other))
        assertTrue(plain < looks.size * 0.75, "plain: $plain")
        assertTrue(tuned > looks.size * 0.95, "tuned: $tuned")
        assertTrue(same > 0.6, "same cow $same")
        assertTrue(abs(other) < 0.15, "different cows $other")
    }

    // ---------------------------------------------------------------- the phone panning

    @Test
    fun theSlideOfThePictureIsWorkedOutFromThePictures() {
        val wide = ground(2400, 700)
        val pan = PanEstimator()
        // The phone's view (960 x 540) moves over the ground: right, right faster, still, back left, down a little.
        val at = listOf(100 to 60, 130 to 60, 205 to 60, 205 to 60, 160 to 60, 160 to 84, 80 to 84)
        var sumX = 0.0
        var sumY = 0.0
        for ((i, p) in at.withIndex()) {
            val s = pan.update(window(wide, p.first, p.second, 960, 540))
            if (i == 0) {
                assertTrue(!s.sure && s.dx == 0.0, "nothing to go on at the first frame")
                continue
            }
            // The view moving right slides the scene left in the picture.
            val wantX = -(p.first - at[i - 1].first) / 960.0
            val wantY = -(p.second - at[i - 1].second) / 540.0
            assertTrue(s.sure, "frame $i")
            assertEquals(wantX, s.dx, 0.004, "frame $i across")
            assertEquals(wantY, s.dy, 0.006, "frame $i up and down")
            sumX += s.dx
            sumY += s.dy
        }
        assertEquals(-(80 - 100) / 960.0, sumX, 0.01, "and they add up to where the phone ended")
        assertEquals(-24 / 540.0, sumY, 0.012)
        // A frame that doesn't fit (a hand across the lens) costs nothing: the next one fits the same key frame.
        assertTrue(!pan.update(AwtFrame(ground(960, 540, seed = 77))).sure)
        val after = pan.update(window(wide, 110, 84, 960, 540))
        assertTrue(after.sure, "fits again")
        assertEquals(-(110 - 80) / 960.0, after.dx, 0.004, "and the slide across the frame that didn't is all there")
        // Pointed somewhere else altogether: the first frame there fits nothing, and the next, fitting it but
        // not what went before, says the thread is lost. From there it follows again.
        val there = (0 until 6).map { pan.update(window(wide, 1300, 84, 960, 540)) }
        assertTrue(!there[0].sure && !there[0].lost)
        assertTrue(there[1].lost)
        assertTrue(there.drop(2).all { it.sure && !it.lost && it.dx == 0.0 }, "held still there: nothing added up")
        // The light changing isn't movement.
        val dim = java.awt.image.RescaleOp(0.8f, 12f, null).filter(wide.getSubimage(1300, 84, 960, 540), null)
        val s = pan.update(AwtFrame(dim))
        assertTrue(s.sure && kotlin.math.abs(s.dx) < 0.003 && kotlin.math.abs(s.dy) < 0.004, "${s.dx} ${s.dy} ${s.sure}")
        // A fast pan, a fifth of the picture a frame, is still followed.
        val fast = PanEstimator()
        fast.update(window(wide, 100, 60, 960, 540))
        var x = 100
        var sum = 0.0
        repeat(6) {
            x += 190
            val f = fast.update(window(wide, x, 60, 960, 540))
            assertTrue(f.sure, "fast frame $it")
            sum += f.dx
        }
        assertEquals(-6 * 190 / 960.0, sum, 0.02)

        // A phone held still doesn't drift, however long.
        val still = PanEstimator()
        var drift = 0.0
        repeat(200) { drift += still.update(window(wide, 400 + it % 2, 60, 960, 540)).dx }
        assertTrue(kotlin.math.abs(drift) < 0.003, "drift $drift")
        // A blank picture gives nothing to go on, and nothing to say the phone moved.
        val blank = PanEstimator()
        repeat(12) {
            val b = blank.update(BlankFrame())
            assertTrue(!b.sure && !b.lost)
        }
    }
}
