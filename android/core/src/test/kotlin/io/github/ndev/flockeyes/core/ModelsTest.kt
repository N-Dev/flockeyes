package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.image.iou
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.dot
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A picture placed on a pretend frame: left edge, top edge and width in pixels of the frame. */
class Placed(val photo: BufferedImage, val x: Int, val y: Int, val w: Int)

/**
 * The real models on real pictures: the cow finder and the recognition model against what the Python tools
 * made of the same photos; and the whole chain (find, follow, look, name, count) on frames of a real video
 * and on pretend frames with a cow walking through a gate.
 *
 * The pictures: tests/assets (see the README there for where each came from). These tests need the
 * recognition model, which the Models workflow puts in the repository.
 */
class ModelsTest {
    private val all = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
    private val tiny get() = Nets.load("models/cows-tiny.onnx")
    private val nano get() = Nets.load("models/cows-nano.onnx")
    private val reid get() = Nets.load(Repo.reidPath)

    private fun canvas(w: Int, h: Int, placed: List<Placed>): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0x6B, 0x8E, 0x4E)
        g.fillRect(0, 0, w, h)
        g.color = Color(0x9F, 0xB8, 0xC8)
        g.fillRect(0, 0, w, h / 5)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        for (p in placed) g.drawImage(p.photo, p.x, p.y, p.w, p.w * p.photo.height / p.photo.width, null)
        g.dispose()
        return img
    }

    @Test
    fun finderBoxesTheCowsInTheBarnPhotosAsThePythonToolsDo() {
        val photos = Repo.photos
        assertTrue(photos.size >= 9, "test photos are there (${photos.size})")
        var same = 0
        var light = 0
        for (p in photos) {
            val res = CowDetector().detect(AwtFrame(p.image), tiny, listOf(all), 0.25)
            val best = res.dets.maxByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) }
            assertNotNull(best, "a cow in ${p.file}")
            if (iou(best.box, p.box) > 0.8) same++
            if (CowDetector().detect(AwtFrame(p.image), nano, listOf(all), 0.25).dets.isNotEmpty()) light++
        }
        println("  the same box as Python in $same of ${photos.size} photos; the light model finds a cow in $light")
        // The pictures are scaled a little differently here and there, which now and then tips which of two boxes wins.
        assertTrue(same >= photos.size * 0.8, "the same box as the Python tools in most photos ($same of ${photos.size})")
        assertTrue(light >= photos.size * 0.5, "the light model finds a good many too ($light of ${photos.size})")
    }

    @Test
    fun recognitionModelGivesTheSameAnswersAsThePythonTools() {
        val cfg = Repo.reidConfig
        if (!Repo.realReid || Repo.photosModel != cfg.key) {
            println("  (skipped: the test photos' answers are for '${Repo.photosModel}', the model here is '${cfg.key}')")
            return
        }
        val e = Embedder(cfg)
        val agree = ArrayList<Double>()
        for (p in Repo.photos) {
            val want = p.embedding ?: continue
            val got = e.embed(reid, e.crop(AwtFrame(p.image), p.box))
            assertEquals(cfg.dim, got.size)
            agree.add(dot(got, want))
        }
        println("  agreement with Python, photo by photo: " + agree.joinToString(" ") { "%.3f".format(it) })
        assertTrue(agree.size >= 3)
        // Not exactly the same: Python cuts the box out at whole pixels and scales it with OpenCV, and on
        // these photos (mostly rails) a pixel's difference in the box shows in the description.
        assertTrue(agree.average() > 0.97, "on average ${agree.average()}")
        assertTrue(agree.min() > 0.94, "the least alike ${agree.min()}")
    }

    @Test
    fun picturesOfOneCowAreMoreAlikeThanPicturesOfDifferentCows() {
        val cfg = Repo.reidConfig
        val e = Embedder(cfg)
        val names = listOf("cow_a1", "cow_a2", "cow_c1", "cow_c2", "cow_d1", "cow_d2")
        val embs = names.map { n ->
            val f = AwtFrame(Repo.single(n))
            val box = CowDetector().detect(f, tiny, listOf(all), 0.3).dets.maxByOrNull { it.score }?.box
            assertNotNull(box, "the cow in $n is found")
            e.embed(reid, e.crop(f, box))
        }
        val tuning = Repo.tuning
        assertNotNull(tuning, "the tuning that goes with the model")
        val tuned = embs.map { tuning.apply(it) }
        val same = ArrayList<Double>()
        val other = ArrayList<Double>()
        for (i in names.indices) {
            println("  ${names[i]}: plain " + names.indices.joinToString(" ") { "%.2f".format(dot(embs[i], embs[it])) } +
                "   tuned " + names.indices.joinToString(" ") { "%.2f".format(dot(tuned[i], tuned[it])) })
            for (j in names.indices) if (i < j) (if (names[i][4] == names[j][4]) same else other).add(dot(tuned[i], tuned[j]))
            val nearest = names.indices.filter { it != i }.maxByOrNull { dot(tuned[i], tuned[it]) }!!
            assertEquals(names[i][4], names[nearest][4], "${names[i]} is most like ${names[nearest]}")
        }
        // Either side of the bar, with room for the margin.
        assertTrue(same.min() > cfg.match + cfg.margin, "two pictures of one cow: ${same.min()}")
        assertTrue(other.max() < cfg.match, "pictures of different cows: ${other.max()}")
    }

    @Test
    fun farDetailFindsEachCowOnceAcrossTheJoin() {
        // Four copies of a real frame side by side and one above the other: cows a quarter the size, some
        // of them across the join between the two halves the frame is analysed in.
        val tile = ImageIO.read(Repo.clip("heap")[20])
        val w = 1920
        val h = 1080
        val f = AwtFrame(canvas(w, h, listOf(Placed(tile, 0, 0, 960), Placed(tile, 960, 0, 960), Placed(tile, 0, 540, 960), Placed(tile, 960, 540, 960))))
        val det = CowDetector()
        // The cows that are plain to see in the frame on its own.
        val clear = det.detect(AwtFrame(tile), tiny, listOf(all), 0.3).dets.filter { it.score >= 0.8 }
        assertTrue(clear.size >= 2, "two cows plain to see in the frame (${clear.size})")
        val normal = det.detect(f, tiny, CowDetector.tiles(h.toDouble() / w, false), 0.3)
        val far = det.detect(f, tiny, CowDetector.tiles(h.toDouble() / w, true), 0.3)
        assertEquals(1, normal.tiles)
        assertEquals(2, far.tiles)
        var once = 0
        for (ty in 0..1) for (tx in 0..1) for (c in clear) {
            val want = doubleArrayOf((tx + c.box[0]) / 2, (ty + c.box[1]) / 2, (tx + c.box[2]) / 2, (ty + c.box[3]) / 2)
            val hits = far.dets.count { iou(it.box, want) > 0.6 }
            assertTrue(hits <= 1, "a cow boxed $hits times")
            if (hits == 1) once++
        }
        println("  far detail: ${far.dets.size} boxes (whole frame at once: ${normal.dets.size}); $once of ${clear.size * 4} clear cows boxed once")
        assertEquals(clear.size * 4, once, "every clear cow boxed once")
        assertTrue(far.dets.none { it.cut }, "no pieces left over")
    }

    @Test
    fun aRealVideoIsCountedAndItsCowsKnownAgain() {
        // Cows at a heap of silage: two stand clear, others are partly hidden behind them.
        val frames = Repo.clip("heap")
        val first = Replay(fps = 2.5)
        first.start()
        val names = HashMap<Int, MutableSet<Int>>()
        for (f in frames) for (s in first.frame(ImageIO.read(f))) s.cowId?.let { names.getOrPut(s.id) { HashSet() }.add(it) }
        println("  first: ${first.summary()}")
        val s1 = first.scan.session
        assertEquals(2, first.herd.size, "the two cows standing clear are learnt, once each")
        assertEquals(2, s1.seen.size)
        assertTrue(s1.count in 3..4, "counted with the ones behind: ${s1.count}")
        assertTrue(names.values.all { it.size == 1 }, "a cow followed without a break keeps its name ($names)")
        assertTrue(first.herd.cows.all { it.views.size >= 3 }, "each is known from several looks as it moves: " + first.herd.cows.map { it.views.size })
        first.gap()
        first.stop()

        // The same video again, as another day's count: the same two, known.
        val again = Replay(herd = first.herd, fps = 2.5)
        again.start()
        for (f in frames) again.frame(ImageIO.read(f))
        println("  again: ${again.summary()}")
        assertEquals(2, again.herd.size, "nothing learnt twice")
        assertEquals(0, again.scan.session.fresh.size)
        assertEquals(s1.seen.keys, again.scan.session.seen.keys)
        again.stop()
    }

    @Test
    fun aPhonePannedAcrossTheCowsCountsEachOnce() {
        // Seven cattle about a yard (one more is half out of the picture at its edge). The phone sees half
        // the yard's width at a time: across, back, and across again.
        val r = Replay()
        r.start()
        val pan = Sweep(r, Repo.clip("yard"), width = 0.5, top = 0.35)
        pan.stay(6)
        pan.to(pan.end)
        pan.stay(6)
        val across = r.scan.session.count
        pan.to(0.0)
        pan.stay(6)
        val back = r.scan.session.count
        pan.to(pan.end)
        pan.stay(6)
        val s = r.scan.session
        println("  panned: across $across, back $back, across again ${s.count}; ${r.summary()}; turned %.3f (really %.3f)".format(r.scan.panX, pan.turned))
        assertTrue(across in 6..7, "each counted on the way across: $across")
        assertTrue(s.count in 6..7 && s.count - across <= 1, "and not again on the way back or the second time across: $across, $back, ${s.count}")
        assertTrue(s.count > s.seen.size, "most by where they stand: few stand clear enough to be told apart by their markings (${s.seen.size})")
        assertEquals(pan.turned, r.scan.panX, 0.03, "how far the phone turned, from the pictures alone")
        assertEquals(0, r.scan.swings)
        r.stop()
    }

    @Test
    fun aGateCountsCowsWalkingThroughAndKnowsOneOnItsWayBack() {
        val line = Gate.default()
        val r = Replay(gate = line, fps = 5.0)
        r.start()
        val a = Repo.single("cow_a1")
        val c = Repo.single("cow_c1")
        fun walk(p: BufferedImage, from: Int, to: Int, n: Int = 22) {
            for (i in 0..n) r.frame(canvas(1280, 720, listOf(Placed(p, from + (to - from) * i / n, 250, 400))))
            r.gap(2000.0)
        }
        walk(a, 20, 860)
        val s = r.scan.session
        assertEquals(listOf(1, 1, 0), s.tally.toList(), "one cow, left to right")
        assertEquals(1, r.herd.size, "learnt as it went through")
        walk(c, 860, 20)
        // The first cow comes back, in a picture taken half a minute later (its head down).
        walk(Repo.single("cow_a2"), 860, 20)
        assertEquals(listOf(3, 1, 2), s.tally.toList())
        val seen = s.sightings
        println("  gate: ${seen.map { "cow ${it.cowId} dir ${it.dir} ${"%.2f".format(it.score)}${if (it.fresh) " new" else ""}" }}; ${r.summary()}")
        assertEquals(3, seen.size)
        assertEquals(listOf(1, 2, 2), seen.map { it.dir })
        assertEquals(2, r.herd.size, "the first cow was known on its way back")
        assertEquals(seen[0].cowId, seen[2].cowId)
        assertTrue(seen[0].fresh && seen[1].fresh && !seen[2].fresh)
        r.stop()
    }
}
