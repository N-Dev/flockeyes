package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.Scan
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.count.Shown
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.herd.Herd
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.ReidConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Plays a video (a folder of its frames, in order) through the cow finder, the tracker and the recognition
 * model exactly as the app's video counting does, and says what came of it. With `draw`, saves frames with
 * the boxes and names drawn on, to look at.
 */
class Replay(
    val herd: Herd = Herd(),
    val cfg: ReidConfig = Repo.reidConfig,
    val far: Boolean = false,
    val gate: DoubleArray? = null,
    val fps: Double = 5.0,
    val tune: Boolean = true,
) {
    val opt = ScanOptions(match = cfg.match, fresh = cfg.fresh, margin = cfg.margin)

    init {
        // Looks are compared through the model's tuning, as in the app.
        if (tune && herd.tuning == null) herd.retune(Repo.tuning)
    }
    private val embedder = Embedder(cfg)
    val scan = Scan(herd, opt, embedder)
    private val detector = CowDetector()
    private val finder get() = Nets.load("models/cows-tiny.onnx")
    private val reid get() = Nets.load(Repo.reidPath)
    var t = 1000.0
    var now = 1_700_000_000_000L
    var frames = 0
    var looks = 0

    /** Called with every look (frame number, track, description), for looking into how alike looks are. */
    var dump: ((Int, Int, FloatArray) -> Unit)? = null

    /** What was in view at each frame: the pretend "truth" the caller can check against. */
    val inView = ArrayList<Int>()

    fun start() {
        gate?.let {
            scan.line = it
        }
        scan.start(gate != null)
    }

    fun frame(img: BufferedImage, draw: File? = null): List<Shown> {
        val f = AwtFrame(img)
        val aspect = img.height.toDouble() / img.width
        if (gate != null && scan.region == null) scan.region = Gate.region(gate, aspect)
        val rois = scan.region?.let { listOf(it) } ?: CowDetector.tiles(aspect, far)
        val res = detector.detect(f, finder, rois, 0.3)
        val out = scan.frame(res.dets, f, t, now, budget = 6)
        for (lk in out.looks) {
            val emb = embedder.embed(reid, lk.px)
            dump?.invoke(frames, lk.trackId, emb)
            scan.look(lk, emb, now)
            looks++
        }
        val shown = scan.shown(t)
        inView.add(shown.size)
        if (draw != null) draw(img, shown, res.dets.size, draw)
        frames++
        t += 1000.0 / fps
        now += (1000.0 / fps).toLong()
        return shown
    }

    /** A folder of frames (0001.jpg ...). `every`: draw every n-th frame into `drawTo`. */
    fun play(dir: File, drawTo: File? = null, every: Int = 5, limit: Int = Int.MAX_VALUE) {
        val files = dir.listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name }.take(limit)
        drawTo?.mkdirs()
        for ((i, f) in files.withIndex()) {
            frame(ImageIO.read(f), if (drawTo != null && i % every == 0) File(drawTo, "${dir.name}_${f.name}") else null)
        }
    }

    /** Nothing in view for a while: every track ends. */
    fun gap(ms: Double = 3000.0) {
        val blank = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB)
        var left = ms
        while (left > 0) {
            scan.frame(emptyList(), AwtFrame(blank), t, now, budget = 0)
            t += 200.0
            now += 200
            left -= 200
        }
    }

    fun stop() = scan.stop(now)

    fun summary(): String {
        val s = scan.session
        val bar = scan.bars()
        return "count ${s.count} (named ${s.seen.size}, learnt ${s.fresh.size}, unrecognised ${s.unknown}, most in view ${s.peak}, by place ${s.placed}); herd ${herd.size}; " +
            "$frames frames, $looks looks; " + (if (herd.tuning != null) "tuned" else "not tuned") + ", bars %.2f/%.2f clear %.2f".format(bar.match, bar.fresh, bar.margin)
    }

    private fun draw(img: BufferedImage, shown: List<Shown>, found: Int, to: File) {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        for (s in shown) {
            val x = (s.box[0] * img.width).toInt()
            val y = (s.box[1] * img.height).toInt()
            val w = ((s.box[2] - s.box[0]) * img.width).toInt()
            val h = ((s.box[3] - s.box[1]) * img.height).toInt()
            g.color = when (s.state) {
                "named" -> Color(0x3D, 0xDC, 0x84)
                "new" -> Color(0xFF, 0xC1, 0x07)
                "unknown" -> Color(0xFF, 0x52, 0x52)
                "far" -> Color(0x9E, 0x9E, 0x9E)
                else -> Color(0x40, 0xC4, 0xFF)
            }
            g.stroke = BasicStroke(3f)
            g.drawRect(x, y, w, h)
            val text = "#${s.id} ${if (s.cowId != null) s.label + (if (s.unsure) "?" else "") else s.state}" +
                " ${"%.2f".format(s.bestScore)}/${"%.2f".format(s.secondScore)} n${s.looks}" + (if (s.skipped.isNotEmpty()) " ${s.skipped}" else "")
            val tw = g.fontMetrics.stringWidth(text)
            val ty = if (y > 24) y - 4 else y + h + 20
            g.color = Color(0, 0, 0, 170)
            g.fillRect(x, ty - 18, tw + 8, 22)
            g.color = Color.WHITE
            g.drawString(text, x + 4, ty)
        }
        // The places cows have been counted at, where they'd be in this picture.
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        for (p in scan.placesInPicture()) {
            val x = (p.box[0] * img.width).toInt()
            val y = (p.box[1] * img.height).toInt()
            val w = ((p.box[2] - p.box[0]) * img.width).toInt()
            val h = ((p.box[3] - p.box[1]) * img.height).toInt()
            g.color = if (p.counts) Color.WHITE else Color(0xFF, 0x8A, 0x80)
            g.drawRect(x, y, w, h)
            g.drawString("p${p.id}" + (if (p.label.isEmpty()) "" else " " + p.label), x + 3, y + h - 4)
        }
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        val s = scan.session
        val line = "frame $frames  found $found  in view ${shown.size}  count ${s.count}  herd ${herd.size}  pan %.2f".format(scan.panX)
        g.color = Color(0, 0, 0, 170)
        g.fillRect(0, 0, g.fontMetrics.stringWidth(line) + 16, 28)
        g.color = Color.WHITE
        g.drawString(line, 8, 20)
        g.dispose()
        ImageIO.write(out, "jpg", to)
    }
}

/**
 * A phone panned across a scene wider than its view: a window `width` of the frame wide (and as much of its
 * height, from `top` down) is slid across the frames of a video that takes in the whole scene, each step
 * on the video's next frame. The cows move as cows do; the phone's turning is the window's sliding.
 */
class Sweep(private val replay: Replay, private val frames: List<File>, private val width: Double = 0.5, private val top: Double = 0.3) {
    /** The window's left edge, in frame widths, and how far the phone has then turned, in picture widths. */
    var x = 0.0
        private set
    val turned: Double get() = x / width
    private var at = 0
    private var step = 1

    /** The furthest the window can go. */
    val end: Double get() = 1.0 - width

    private fun shot() {
        val img = ImageIO.read(frames[at])
        // Out of frames: back through them, so nothing jumps.
        if (at + step < 0 || at + step >= frames.size) step = -step
        at += step
        val w = (img.width * width).toInt()
        val h = (img.height * width).toInt()
        val px = (x * img.width).toInt().coerceIn(0, img.width - w)
        val py = (top * img.height).toInt().coerceIn(0, img.height - h)
        val win = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = win.createGraphics()
        g.drawImage(img.getSubimage(px, py, w, h), 0, 0, null)
        g.dispose()
        replay.frame(win)
    }

    fun stay(n: Int) = repeat(n) { shot() }

    /** Pans to `to`, `speed` of the picture's width a frame. */
    fun to(to: Double, speed: Double = 0.07) {
        while (kotlin.math.abs(x - to) > 1e-9) {
            x += (to - x).coerceIn(-speed * width, speed * width)
            shot()
        }
    }
}

