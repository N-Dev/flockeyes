package io.github.ndev.flockeyes.scan

import android.graphics.Bitmap
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.FINDER_CONF
import io.github.ndev.flockeyes.ai.BitmapFrame
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.camera.FrameSink
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.Look
import io.github.ndev.flockeyes.core.count.PlaceShown
import io.github.ndev.flockeyes.core.count.Scan
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.count.Shown
import io.github.ndev.flockeyes.core.count.Sighting
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.track.Track
import io.github.ndev.flockeyes.debug.DebugLog
import io.github.ndev.flockeyes.service.BackgroundService
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** The last cow named or counted, for the line above the counts. */
class LastCow(val label: String, val fresh: Boolean, val dir: Int, val at: Long)

/** Everything a counting screen shows. */
data class ScanUi(
    val ready: Boolean = false,
    val status: String = "Starting the AI…",
    val aspect: Double = 9.0 / 16,
    /** The part of the frame being analysed (x, y, w, h), and how many pieces it's analysed in. */
    val roi: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
    val tiles: Int = 1,
    val boxes: List<Shown> = emptyList(),
    val fps: Double = 0.0,
    val msFind: Double = 0.0,
    val msPrep: Double = 0.0,
    val msPost: Double = 0.0,
    /** The recognition model: how long a look takes, and how many are waiting. */
    val msLook: Double = 0.0,
    val waiting: Int = 0,
    val model: String = "",
    val modelKey: String = "",
    val running: Boolean = false,
    val started: Long = 0,
    val countId: Long? = null,
    /** Field: cows counted. Gate: crossings. */
    val count: Int = 0,
    /** Field: cows named, cows in view now, the most in view at once. */
    val named: Int = 0,
    val inView: Int = 0,
    val peak: Int = 0,
    val unknown: Int = 0,
    val fresh: Int = 0,
    /** Gate: went each way. */
    val n1: Int = 0,
    val n2: Int = 0,
    val last: LastCow? = null,
    /** Bumped when something is counted (for a little buzz of the picture). */
    val counted: Long = 0,
    /** Frames analysed since the app started, and looks taken. */
    val frames: Long = 0,
    val looks: Long = 0,
    /** Debug mode: what the finder found this frame (before tracking) and recent frame times. */
    val raw: List<Det> = emptyList(),
    val times: List<Float> = emptyList(),
    val frameW: Int = 0,
    val frameH: Int = 0,
    /** The bars in force: how alike, and how far clear of the next cow. */
    val match: Double = 0.0,
    val margin: Double = 0.0,
    /** Field: times the phone was turned too fast to follow during this count. */
    val swings: Int = 0,
    /** Debug mode, field: cows counted by where they stand; those places as they lie in the picture; how far the phone has turned. */
    val placed: Int = 0,
    val places: List<PlaceShown> = emptyList(),
    val panX: Double = 0.0,
    val panY: Double = 0.0,
)

/**
 * Live counting from the camera, for a field or at a gate: each frame goes through the cow finder; the
 * cows are followed from frame to frame; pictures of them go through the recognition model on another
 * thread (so the picture keeps moving), and the answers name them. Only who was counted is kept.
 */
class Scanner(private val app: App, val gate: Boolean) : FrameSink {
    val ui = MutableStateFlow(ScanUi())
    private val lock = Any()
    private val detector = CowDetector()
    private val opt = ScanOptions()
    val scan: Scan by lazy { Scan(app.herd, opt, app.engine.embedder) }

    /** Runs the recognition model, one look at a time. */
    private val lookThread = Executors.newSingleThreadExecutor { r -> Thread(r, "looks").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val lookEmbedder: Embedder by lazy { Embedder(app.engine.reid) }
    private val waiting = AtomicInteger(0)

    @Volatile
    private var msLook = 0.0

    @Volatile
    private var looks = 0L
    private var frame: BitmapFrame? = null
    private var aspect = 0.0

    @Volatile
    private var running = false
    private var countId: Long? = null
    private var started = 0L
    private var name = ""
    private var lastT = 0.0
    private var lastMove = 0.0
    private var fps = 0.0
    private var nextAt = 0.0
    private var model: Model = Model.DET_TINY
    private val recent = ArrayList<Double>()
    private val times = ArrayDeque<Float>()

    @Volatile
    private var last: LastCow? = null

    @Volatile
    private var countedAt = 0L
    private var savedAt = 0L
    private var swings = 0

    /** Settings changed (the line moved, strictness, learning): take them up. */
    fun reconfigure() {
        synchronized(lock) { applySettings() }
    }

    private fun applySettings() {
        val p = app.prefs
        app.engine.configure(opt)
        if (gate) {
            val a = if (aspect > 0) aspect else 9.0 / 16
            scan.line = p.gateLine
            scan.region = Gate.region(p.gateLine, a)
        } else {
            scan.line = null
            scan.region = null
        }
    }

    /** Starts a count. `name`: the field's or gate's name (a field count is named when it's saved). */
    fun start(name: String = "") {
        synchronized(lock) {
            if (running) return
            val p = app.prefs
            applySettings()
            this.name = name
            started = System.currentTimeMillis()
            countId = app.db.newCount(if (gate) "gate" else "field", name, started, "live", if (gate) p.dir1 else "", if (gate) p.dir2 else "", app.engine.reid.key)
            scan.start(gate)
            running = true
            last = null
            savedAt = started
            swings = 0
            ui.value = ui.value.copy(running = true, started = started, countId = countId, count = 0, named = 0, peak = 0, unknown = 0, fresh = 0, n1 = 0, n2 = 0, last = null, swings = 0, placed = 0)
            val bar = scan.bars()
            DebugLog.add(
                if (gate) "gate" else "field",
                "Count started (${countId}); learning ${if (opt.learn) "on" else "off"}, herd ${app.herd.size}, " +
                    "same cow at %.2f and %.2f clear of the next${if (app.herd.tuning == null) " (no tuning)" else ""}".format(bar.match, bar.margin),
            )
        }
        // A gate count carries on with the screen off (Android shows a notification meanwhile).
        if (gate && app.prefs.backgroundCounting) BackgroundService.start(app)
    }

    /** Ends the count and saves it. Returns its id. */
    fun stop(): Long? {
        synchronized(lock) {
            if (!running) return null
            // Looks still waiting for the recognition model are let finish first, so no cow is cut short.
            drainLooks()
            val now = System.currentTimeMillis()
            save(scan.stop(now))
            running = false
            val id = countId
            id?.let { writeCount(it, now) }
            countId = null
            ui.value = ui.value.copy(running = false, countId = null)
            DebugLog.add(if (gate) "gate" else "field", "Count stopped ($id): ${scan.session.count}")
            if (gate && BackgroundService.running.value) BackgroundService.stop(app)
            app.dataChanged()
            return id
        }
    }

    /** Throws the running count away (nothing was wanted from it). */
    fun discard() {
        val id = stop() ?: return
        app.db.deleteCount(id)
        app.dataChanged()
    }

    /** Gives a saved count its name (a field count is named when it's finished). */
    fun rename(id: Long, name: String) {
        app.db.renameCount(id, name, "")
        val p = app.prefs
        if (!gate && name.isNotBlank()) p.fieldNames = (listOf(name) + p.fieldNames.filter { it != name })
        app.dataChanged()
    }

    private fun drainLooks() {
        runCatching { lookThread.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Waits for the looks in hand to be answered (tests feed frames faster than a camera does). */
    fun awaitLooks() = drainLooks()

    private fun writeCount(id: Long, now: Long) {
        val s = scan.session
        app.db.updateCount(id, now, s.count, s.peak, s.unknown, s.fresh.size, app.herd.size, s.tally[1], s.tally[2])
    }

    /** Saves the count's figures now and then, so a closed app loses nothing. */
    fun touch() {
        val id = countId ?: return
        app.io.execute { runCatching { synchronized(lock) { if (running) writeCount(id, System.currentTimeMillis()) } } }
    }

    // While the speed test runs, the picture pauses unless a count is running.
    override fun wants(timeMs: Double): Boolean = timeMs >= nextAt && (running || !app.engine.testing)

    override fun onFrame(bitmap: Bitmap, timeMs: Double) {
        synchronized(lock) { analyse(bitmap, timeMs) }
    }

    private fun analyse(bitmap: Bitmap, t: Double) {
        val p = app.prefs
        val a = bitmap.height.toDouble() / bitmap.width
        if (abs(a - aspect) > 0.01) {
            // First frame, or the phone turned round: the tracks don't fit the new picture.
            aspect = a
            scan.reset()
            applySettings()
        }
        val now = System.currentTimeMillis()
        // Auto: the standard finder while the phone keeps up, else the light one.
        val want = when (p.finderModel) {
            "nano" -> Model.DET_NANO
            "tiny" -> Model.DET_TINY
            else -> model
        }
        if (want != model) {
            model = want
            recent.clear()
        }
        val net = app.engine.net(model)
        val f = frame?.also { it.bitmap = bitmap } ?: BitmapFrame(bitmap).also { frame = it }
        val rois = if (gate) listOf(scan.region ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)) else CowDetector.tiles(a, p.far)
        val res = detector.detect(f, net, rois, FINDER_CONF[p.sensitivity] ?: 0.3, p.lookalikes)
        // The recognition model takes one look at a time: don't queue more than it can soon get through.
        val budget = (3 - waiting.get()).coerceIn(0, 2)
        // A field count keeps each cow's place as the phone pans: for that it must know the zoom.
        if (!gate) scan.zoom = app.camera.zoom.value?.ratio?.toDouble() ?: 1.0
        val out = scan.frame(res.dets, f, t, now, budget)
        for (lk in out.looks) submit(lk)
        if (running && scan.swings != swings) {
            swings = scan.swings
            DebugLog.add("field", "Turned too fast to follow ($swings): cows seen from here on are counted afresh")
        }
        if (running) {
            save(out.saved)
            for (tr in out.named) announce(tr, now)
            for (tr in out.crossed) {
                countedAt = now
                app.haptic(14)
                val cow = tr.cowId?.let { app.herd[it] }
                last = LastCow(cow?.label ?: "A cow", tr.created, tr.crossings.lastOrNull()?.dir ?: 0, now)
                if (p.debug) DebugLog.add("gate", "#${tr.id} crossed, direction ${tr.crossings.lastOrNull()?.dir} (${cow?.label ?: "not named yet"})")
            }
            if (now - savedAt > 30_000) {
                savedAt = now
                countId?.let { writeCount(it, now) }
            }
        }
        // Cows moving keep the frame rate up; a still field doesn't need it.
        if (out.live.any { abs(it.vx) + abs(it.vy) > 0.00003 } || out.looks.isNotEmpty()) lastMove = t
        val idle = t - lastMove > 4000
        if (lastT > 0) {
            val inst = 1000 / maxOf(1.0, t - lastT)
            fps = if (fps == 0.0) inst else fps * 0.85 + inst * 0.15
        }
        lastT = t
        recent.add(res.msInfer)
        if (recent.size > 40) recent.removeAt(0)
        if (p.finderModel == "auto" && model == Model.DET_TINY && recent.size >= 30 && recent.sorted()[recent.size / 2] > 90 * rois.size) {
            DebugLog.add("finder", "Switched to the light cow finder: the standard one took ${recent.sorted()[recent.size / 2].toInt()} ms a frame")
            model = Model.DET_NANO
            recent.clear()
        }
        val debug = p.debug
        if (debug) {
            times.addLast(res.msTotal.toFloat())
            while (times.size > 90) times.removeFirst()
        } else if (times.isNotEmpty()) {
            times.clear()
        }
        nextAt = t + (if (idle && !running) 150.0 else 0.0) - 5
        publish(res.dets, res.width, res.height, res.tiles, res.msInfer, res.msPrep, res.msPost, debug, rois)
    }

    private fun publish(raw: List<Det>, w: Int, h: Int, tiles: Int, msFind: Double, msPrep: Double, msPost: Double, debug: Boolean, rois: List<DoubleArray>) {
        val s = scan.session
        val shown = scan.shown()
        val bar = scan.bars()
        ui.value = ui.value.copy(
            ready = true,
            status = "",
            aspect = aspect,
            roi = if (rois.size == 1) rois[0] else doubleArrayOf(0.0, 0.0, 1.0, 1.0),
            tiles = tiles,
            boxes = shown,
            fps = fps,
            msFind = msFind,
            msPrep = msPrep,
            msPost = msPost,
            msLook = msLook,
            waiting = waiting.get(),
            model = if (model == Model.DET_TINY) "Standard" else "Light",
            modelKey = model.key,
            running = running,
            count = if (running) s.count else ui.value.count,
            named = if (running) s.seen.size else ui.value.named,
            inView = shown.size,
            peak = if (running) s.peak else ui.value.peak,
            unknown = if (running) s.unknown else ui.value.unknown,
            fresh = if (running) s.fresh.size else ui.value.fresh,
            n1 = if (running) s.tally[1] else ui.value.n1,
            n2 = if (running) s.tally[2] else ui.value.n2,
            last = last,
            counted = countedAt,
            frames = ui.value.frames + 1,
            looks = looks,
            raw = if (debug) raw else emptyList(),
            times = if (debug) times.toList() else emptyList(),
            frameW = w,
            frameH = h,
            match = bar.match,
            margin = bar.margin,
            swings = if (running) scan.swings else ui.value.swings,
            placed = if (running) s.placed else ui.value.placed,
            places = if (debug && !gate && running) scan.placesInPicture() else emptyList(),
            panX = scan.panX,
            panY = scan.panY,
        )
    }

    /** A picture of a cow goes to the recognition model's thread; its answer comes back to the scan. */
    private fun submit(look: Look) {
        waiting.incrementAndGet()
        lookThread.execute {
            try {
                val t0 = System.nanoTime()
                val emb = lookEmbedder.embed(app.engine.net(Model.REID), look.px)
                val ms = (System.nanoTime() - t0) / 1e6
                msLook = if (msLook == 0.0) ms else msLook * 0.8 + ms * 0.2
                looks++
                val now = System.currentTimeMillis()
                val out = scan.look(look, emb, now)
                if (running) {
                    save(out.saved)
                    out.named?.let { announce(it, now) }
                }
            } catch (e: Throwable) {
                DebugLog.error("looks", "A look failed", e)
            } finally {
                waiting.decrementAndGet()
            }
        }
    }

    /** A cow was just named or learnt. */
    private fun announce(tr: Track, now: Long) {
        val cow = tr.cowId?.let { app.herd[it] } ?: return
        if (!gate) {
            countedAt = now
            last = LastCow(cow.label, tr.created, 0, now)
            app.haptic(if (tr.created) 26 else 12)
        } else if (tr.crossings.isNotEmpty()) {
            last = LastCow(cow.label, tr.created, tr.crossings.last().dir, now)
        }
        if (app.prefs.debug) {
            DebugLog.add(
                if (gate) "gate" else "field",
                "#${tr.id} is ${cow.label}${if (tr.created) " (new)" else " %.2f".format(tr.matchScore)}${if (tr.unsure) ", unsure" else ""}" +
                    (tr.second?.let { s -> app.herd[s.cowId]?.let { " · next: ${it.label} %.2f".format(s.score) } } ?: ""),
            )
        }
    }

    private fun save(list: List<Sighting>) {
        if (list.isEmpty()) return
        val id = countId ?: return
        runCatching { app.db.addSightings(id, list) }
        app.dataChanged()
    }
}
