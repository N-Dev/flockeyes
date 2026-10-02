package io.github.ndev.flockeyes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ndev.flockeyes.ai.Accel
import io.github.ndev.flockeyes.ai.BitmapFrame
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.ai.RunConfig
import io.github.ndev.flockeyes.ai.SpeedTest
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.dot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The AI models on this device through the app's own engine: the same answers as on the JVM. */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val all = listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0))

    @Test
    fun cowFinderFindsTheCowInTheTestPictures() {
        val n = Shots.SINGLES.size
        for (m in listOf(Model.DET_TINY, Model.DET_NANO)) {
            var found = 0
            var ms = 0.0
            for (name in Shots.SINGLES) {
                val frame = BitmapFrame(Shots.single(name))
                val det = CowDetector()
                val net = app.engine.net(m)
                det.detect(frame, net, all, 0.3) // warm-up
                val res = det.detect(frame, net, all, 0.3)
                if (res.dets.isNotEmpty()) found++
                ms += res.msInfer
            }
            Shots.log("${m.key}: a cow found in $found of $n test pictures · AI ${(ms / n).toInt()} ms (${app.engine.configFor(m).label})")
            assertTrue("${m.key} found $found of $n", found >= if (m == Model.DET_TINY) n else n - 2)
        }
        // And in a frame of a real video: the two cows standing clear.
        val res = CowDetector().detect(BitmapFrame(Shots.frame(Shots.clip("heap")[20])), app.engine.net(Model.DET_TINY), all, 0.3)
        Shots.log("a video frame: ${res.dets.size} cows found, scores ${res.dets.map { "%.2f".format(it.score) }}")
        assertTrue("two clear cows in the frame", res.dets.count { it.score >= 0.8 } >= 2)
    }

    @Test
    fun recogniserDescribesAPhotoTheSameWayEveryTime() {
        val cfg = app.engine.reid
        val e = Embedder(cfg)
        val net = app.engine.net(Model.REID)
        val embs = ArrayList<FloatArray>()
        val cows = ArrayList<Char>()
        var ms = 0.0
        for (name in Shots.SINGLES) {
            val n = name[4]
            val frame = BitmapFrame(Shots.single(name))
            val box = CowDetector().detect(frame, app.engine.net(Model.DET_TINY), all, 0.25).dets.maxByOrNull { it.score }?.box ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            val px = e.crop(frame, box)
            val t0 = System.nanoTime()
            val a = e.embed(net, px)
            ms += (System.nanoTime() - t0) / 1e6
            val b = e.embed(net, px)
            assertEquals(cfg.dim, a.size)
            assertEquals("a unit vector", 1.0, dot(a, a), 1e-3)
            assertTrue("the same picture gives the same answer", dot(a, b) > 0.9999)
            // As the app compares looks: through the tuning that comes with the model.
            embs.add(app.herd.see(a))
            cows.add(n)
        }
        val same = ArrayList<Double>()
        val other = ArrayList<Double>()
        for (i in embs.indices) for (j in i + 1 until embs.size) (if (cows[i] == cows[j]) same else other).add(dot(embs[i], embs[j]))
        Shots.log(
            "recogniser (${cfg.key}${if (app.herd.tuning != null) ", tuned" else ", no tuning"}): ${(ms / embs.size).toInt()} ms a look (${app.engine.configFor(Model.REID).label}); pictures of the same cow %.2f alike on average (lowest %.2f), of different cows %.2f (highest %.2f); same cow at %.2f"
                .format(same.average(), same.min(), other.average(), other.max(), cfg.match),
        )
        assertTrue("the tuning that comes with the model is in use", app.herd.tuning != null)
        assertTrue("every pair of pictures of one cow is more alike than any pair of different cows", same.min() > other.max())
        assertTrue("and they sit either side of the bar", same.min() > cfg.match && other.max() < cfg.match)
    }

    /** The speed test in Settings: every setup checked on the sample photo and timed. */
    @Test
    fun speedTestFindsWorkingSetups() {
        val results = SpeedTest(app).run(Model.entries) {}
        for (r in results) Shots.log("speed test: ${r.model.key} on ${r.cfg.label}: ${r.ms?.let { "%.0f ms".format(it) } ?: "-"}${if (r.ok) "" else " (${r.note})"}")
        for (m in Model.entries) {
            assertTrue("$m works on the CPU", results.any { it.model == m && it.cfg.accel == Accel.CPU && it.ok })
        }
    }

    @Test
    fun acceleratorsRunOrFallBack() {
        val frame = BitmapFrame(Shots.single("cow_a1"))
        for (m in listOf(Model.DET_TINY, Model.REID)) {
            for (cfg in listOf(RunConfig(Accel.CPU, 2), RunConfig(Accel.XNNPACK, 2), RunConfig(Accel.NNAPI, 2))) {
                val r = runCatching {
                    app.engine.create(m, cfg).use { n ->
                        val t0 = System.nanoTime()
                        if (m == Model.REID) {
                            val e = Embedder(app.engine.reid)
                            e.embed(n, e.crop(frame, doubleArrayOf(0.1, 0.1, 0.9, 0.9)))
                        } else {
                            CowDetector().detect(frame, n, all, 0.25)
                        }
                        "ran in ${(System.nanoTime() - t0) / 1_000_000} ms"
                    }
                }
                Shots.log("${m.key} on ${cfg.label}: ${r.getOrElse { "not available (${it.message?.take(80)})" }}")
                // The other accelerators depend on the device; the app falls back to the CPU without them.
                if (cfg.accel == Accel.CPU) assertTrue(r.exceptionOrNull()?.toString() ?: "", r.isSuccess)
            }
        }
    }
}
