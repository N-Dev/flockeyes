package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.PanEstimator
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Plays folders of video frames through the whole chain and says what was counted: for measuring the app
 * on footage that isn't in the repository (docs/counting.md was made with it). Does nothing unless told
 * where the clips are:
 *
 *     ./gradlew :core:test --tests '*ClipsTest*' -Dflockeyes.clips=DIR [-Dflockeyes.clip=a,b] ...
 *
 * DIR holds one folder of frames (0001.jpg ...) per clip, as the Research workflow's clips.zip and
 * pans.zip unpack to. Further settings:
 * - `-Dflockeyes.out=DIR`: saves frames with the boxes, names and places drawn on (every tenth, or
 *   `-Dflockeyes.every=N`).
 * - `-Dflockeyes.far=1`: the picture analysed in two halves, as "Far away" does.
 * - `-Dflockeyes.sweep=clip:width:top:speed,...`: instead, slides a window across each clip as a phone
 *   would be panned ([Sweep]): across, back and across again.
 * - `-Dflockeyes.turning=1`: instead, only how the camera moves in each clip (no cows are looked for): quick.
 */
class ClipsTest {
    private val root: File? = System.getProperty("flockeyes.clips")?.let { File(it) }
    private fun frames(name: String) = File(root, name).listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name }
    private fun names(): List<String> =
        System.getProperty("flockeyes.clip")?.split(',') ?: root!!.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

    /** Each clip as it is: what was counted, how far the camera turned, and whether the thread was ever lost. */
    @Test
    fun counts() {
        if (root == null || System.getProperty("flockeyes.sweep") != null || System.getProperty("flockeyes.turning") != null) return
        val out = System.getProperty("flockeyes.out")?.let { File(it) }
        val every = (System.getProperty("flockeyes.every") ?: "10").toInt()
        for (n in names()) {
            val r = Replay(far = System.getProperty("flockeyes.far") != null)
            r.start()
            out?.mkdirs()
            var furthest = 0.0
            for ((i, f) in frames(n).withIndex()) {
                r.frame(ImageIO.read(f), if (out != null && i % every == 0) File(out, "${n}_${f.name}") else null)
                if (kotlin.math.abs(r.scan.panX) > kotlin.math.abs(furthest)) furthest = r.scan.panX
            }
            println("  $n: ${r.summary().substringBefore("; tuned")}; turned %+.2f (furthest %+.2f) across, %+.2f down; lost ${r.scan.swings}".format(r.scan.panX, furthest, r.scan.panY))
            r.stop()
        }
    }

    /** A window slid across each clip named in `flockeyes.sweep`, as a phone would be panned across the scene. */
    @Test
    fun sweeps() {
        if (root == null) return
        val specs = System.getProperty("flockeyes.sweep")?.split(',') ?: return
        for (spec in specs) {
            val q = spec.split(':')
            val r = Replay()
            r.start()
            val pan = Sweep(r, frames(q[0]), width = q[1].toDouble(), top = q[2].toDouble())
            val speed = q[3].toDouble()
            pan.stay(6)
            pan.to(pan.end, speed)
            pan.stay(6)
            val across = r.scan.session.count
            pan.to(0.0, speed)
            pan.stay(6)
            val back = r.scan.session.count
            pan.to(pan.end, speed)
            pan.stay(6)
            println("  $spec: across $across, back $back, across again ${r.scan.session.count}; ${r.summary().substringBefore("; tuned")}; turned %.3f (really %.3f)".format(r.scan.panX, pan.turned))
            r.stop()
        }
    }

    /** How the camera moves in each clip, from the pictures alone (no cows are looked for): quick. */
    @Test
    fun turning() {
        if (root == null || System.getProperty("flockeyes.turning") == null) return
        for (n in names()) {
            val p = PanEstimator()
            var x = 0.0
            var y = 0.0
            var minX = 0.0
            var maxX = 0.0
            var sure = 0
            var still = 0
            var lost = 0
            val fs = frames(n)
            for (f in fs) {
                val s = p.update(AwtFrame(ImageIO.read(f)))
                if (s.lost) lost++
                if (s.sure) {
                    sure++
                    if (s.dx == 0.0 && s.dy == 0.0) still++
                    x -= s.dx
                    y -= s.dy
                }
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
            }
            println("  %-14s %3d frames: followed in %3d (held still in %3d), lost %d; turned %+.2f to %+.2f across (ends %+.2f), ends %+.2f down".format(n, fs.size, sure, still, lost, minX, maxX, x, y))
        }
    }
}
