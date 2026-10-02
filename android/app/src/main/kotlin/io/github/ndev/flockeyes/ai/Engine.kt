package io.github.ndev.flockeyes.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.STRICTNESS
import io.github.ndev.flockeyes.core.Json
import io.github.ndev.flockeyes.core.count.Bars
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.image.Frame
import io.github.ndev.flockeyes.core.net.OrtNet
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.ReidConfig
import io.github.ndev.flockeyes.core.reid.Tuning
import io.github.ndev.flockeyes.core.reid.dot
import io.github.ndev.flockeyes.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.EnumSet

/** The AI models in the app (assets/models, copied from the repository's models folder at build time). */
enum class Model(val key: String, val asset: String, val label: String) {
    DET_NANO("detNano", "models/cows-nano.onnx", "Cow finder (light)"),
    DET_TINY("detTiny", "models/cows-tiny.onnx", "Cow finder (standard)"),
    REID("reid", "models/cow-reid.onnx", "Cow recogniser"),
    ;

    companion object {
        fun of(key: String): Model = entries.first { it.key == key }
    }
}

/** Where a model runs: ONNX Runtime's own CPU kernels, XNNPACK's (also CPU), or NNAPI (Android's route to the GPU and AI chip). */
enum class Accel(val label: String) {
    CPU("CPU"),
    XNNPACK("XNNPACK"),
    NNAPI("NNAPI"),
}

data class RunConfig(val accel: Accel, val threads: Int) {
    override fun toString() = "${accel.name}:$threads"
    val label: String get() = if (accel == Accel.NNAPI) "NNAPI" else "${accel.label}, $threads thread${if (threads == 1) "" else "s"}"

    companion object {
        fun parse(s: String?): RunConfig? {
            val p = s?.split(':') ?: return null
            if (p.size != 2) return null
            val a = runCatching { Accel.valueOf(p[0]) }.getOrNull() ?: return null
            val t = p[1].toIntOrNull() ?: return null
            return RunConfig(a, t)
        }
    }
}

/** Loads the models with ONNX Runtime, each with the setup that suits this phone. */
class Engine(private val app: App) {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** How the recognition model is fed and judged (models/cow-reid.json). */
    val reid: ReidConfig by lazy { ReidConfig.parse(app.assets.open("models/cow-reid.json").bufferedReader().use { it.readText() }) }
    val embedder: Embedder by lazy { Embedder(reid) }
    private val nets = HashMap<Model, Pair<RunConfig, OrtNet>>()

    /** Problems worth showing, e.g. "NNAPI isn't available, using the CPU". */
    val notice = MutableStateFlow<String?>(null)

    /** The speed test is running: live scanning pauses so it doesn't slow the test down (a count doesn't). */
    @Volatile
    var testing = false

    val cores: Int = Runtime.getRuntime().availableProcessors()
    val defaultThreads: Int = minOf(4, cores)

    /**
     * How far a cow must stand out from the next most alike one, as a multiple of the model's measured
     * gap: the "How sure" setting, or the exact gap set in debug mode.
     */
    fun gapScale(): Double {
        val p = app.prefs
        if (p.gapOverride > 0) return p.gapOverride / reid.margin
        return STRICTNESS[p.strictness] ?: 1.0
    }

    /** The bars a look must clear. */
    fun bars(): Bars = gapScale().let { Bars(reid.match, reid.fresh, reid.margin * it) }

    /** The yardstick looks are compared with (see Tuning), built in with the recognition model; null if it has none. */
    val tuning: Tuning? by lazy {
        if (reid.tuning.isEmpty()) return@lazy null
        runCatching { Tuning.read(app.assets.open("models/${reid.tuning}").use { it.readBytes() }).takeIf { it.mean.size == reid.dim } }
            .onFailure { DebugLog.error("engine", "The tuning couldn't be read", it) }.getOrNull()
    }

    /** Sets a scan's bars from the model's measured ones and the settings. */
    fun configure(opt: ScanOptions) {
        val k = gapScale()
        opt.match = reid.match
        opt.fresh = reid.fresh
        opt.margin = reid.margin * k
        opt.learn = app.prefs.learn
    }

    fun tunedConfigs(): Map<String, RunConfig> = runCatching {
        Json.obj(app.prefs.tuned).mapNotNull { (k, v) -> RunConfig.parse(v as? String)?.let { k to it } }.toMap()
    }.getOrElse { emptyMap() }

    /**
     * The setup a model runs with: the one chosen in Settings, or the speed test's pick, or ONNX Runtime's
     * own CPU code (dependable everywhere; which accelerator is faster depends on the phone, which is what
     * the speed test finds out).
     */
    fun configFor(m: Model): RunConfig {
        val p = app.prefs
        val threads = if (p.threads > 0) p.threads else defaultThreads
        if (p.accel != "auto") {
            val a = runCatching { Accel.valueOf(p.accel) }.getOrDefault(Accel.CPU)
            return RunConfig(a, threads)
        }
        return tunedConfigs()[m.key] ?: RunConfig(Accel.CPU, threads)
    }

    fun options(cfg: RunConfig): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (cfg.accel) {
            Accel.CPU -> o.setIntraOpNumThreads(cfg.threads)
            Accel.XNNPACK -> {
                // XNNPACK has its own thread pool; ONNX Runtime's is kept to one thread that doesn't spin.
                o.addXnnpack(mapOf("intra_op_num_threads" to cfg.threads.toString()))
                o.setIntraOpNumThreads(1)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            Accel.NNAPI -> {
                o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                o.setIntraOpNumThreads(cfg.threads)
            }
        }
        return o
    }

    fun modelBytes(m: Model): ByteArray = app.assets.open(m.asset).use { it.readBytes() }

    /** A new session for a model with a given setup (used by the speed test too). */
    fun create(m: Model, cfg: RunConfig, bytes: ByteArray = modelBytes(m)): OrtNet = OrtNet(env, env.createSession(bytes, options(cfg)))

    /** The loaded model, loading it (a second or so) the first time or after the setup changed. */
    @Synchronized
    fun net(m: Model): OrtNet {
        val cfg = configFor(m)
        val cur = nets[m]
        if (cur != null) {
            if (cur.first == cfg) return cur.second
            cur.second.close()
        }
        val bytes = modelBytes(m)
        val t0 = System.nanoTime()
        val net = try {
            create(m, cfg, bytes)
        } catch (e: Throwable) {
            if (cfg.accel == Accel.CPU) {
                DebugLog.error("engine", "The ${m.label.lowercase()} couldn't load", e)
                throw e
            }
            DebugLog.error("engine", "${cfg.accel.label} couldn't run the ${m.label.lowercase()}, using the CPU", e)
            notice.value = "${cfg.accel.label} couldn’t run the ${m.label.lowercase()}, so it’s using the CPU"
            create(m, RunConfig(Accel.CPU, cfg.threads), bytes)
        }
        DebugLog.add("engine", "Loaded the ${m.label.lowercase()} (${m.key}) with ${cfg.label} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        nets[m] = cfg to net
        return net
    }

    @Synchronized
    fun isLoaded(m: Model): Boolean = nets.containsKey(m)

    /** Frees models that aren't needed right now. */
    @Synchronized
    fun release(keep: Set<Model>) {
        val drop = nets.keys.filter { it !in keep }
        for (m in drop) nets.remove(m)?.second?.close()
        if (drop.isNotEmpty()) DebugLog.add("engine", "Freed ${drop.joinToString { it.key }}")
    }

    /** Loads the live models in the background at start-up. */
    fun warmUp() {
        net(Model.DET_TINY)
        net(Model.REID)
    }
}

/** An Android bitmap as a [Frame]: areas are cut and scaled with bilinear filtering on the CPU. */
class BitmapFrame(var bitmap: Bitmap) : Frame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val scratch = HashMap<Long, Pair<Bitmap, Canvas>>()

    @Synchronized
    override fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray {
        val key = (outW.toLong() shl 32) or outH.toLong()
        val (bmp, canvas) = scratch.getOrPut(key) {
            if (scratch.size > 6) scratch.clear()
            val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            b to Canvas(b)
        }
        canvas.drawColor(Color.BLACK)
        matrix.setTranslate(-x.toFloat(), -y.toFloat())
        matrix.postScale((outW / w).toFloat(), (outH / h).toFloat())
        canvas.drawBitmap(bitmap, matrix, paint)
        bmp.getPixels(out, 0, outW, 0, 0, outW, outH)
        return out
    }
}

/** A result of the speed test: one model with one setup. */
class SpeedResult(val model: Model, val cfg: RunConfig, val ms: Double?, val ok: Boolean, val note: String?)

/**
 * Finds the fastest setup for each model on this phone: every setup is checked on a sample photo (it
 * must still find the cow, and describe it the same way) and timed.
 */
class SpeedTest(private val app: App) {
    private val engine = app.engine

    /** Set from another thread to stop after the current setup. */
    @Volatile
    var cancelled = false

    fun configs(m: Model): List<RunConfig> {
        val c = engine.cores
        val threads = listOf(2, 4, 6, 8).filter { it <= c }.ifEmpty { listOf(1) }
        val cpu = threads.map { RunConfig(Accel.CPU, it) }
        return cpu + listOfNotNull(RunConfig(Accel.XNNPACK, minOf(4, c)), RunConfig(Accel.XNNPACK, minOf(6, c)).takeIf { c >= 6 }, RunConfig(Accel.NNAPI, minOf(4, c)))
    }

    fun run(models: List<Model>, progress: (String) -> Unit): List<SpeedResult> {
        engine.testing = true
        try {
            return runAll(models, progress)
        } finally {
            engine.testing = false
        }
    }

    private fun runAll(models: List<Model>, progress: (String) -> Unit): List<SpeedResult> {
        DebugLog.add("engine", "Speed test started")
        val sample = app.assets.open(SAMPLE).use { BitmapFactory.decodeStream(it) }
        val frame = BitmapFrame(sample)
        val all = listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0))
        // Where the cow is and what the recogniser makes of it, found once on the CPU, to check the others against.
        val cpu = RunConfig(Accel.CPU, engine.defaultThreads)
        val box = engine.create(Model.DET_TINY, cpu).use { n -> CowDetector().detect(frame, n, all, 0.25).dets.maxByOrNull { it.score }?.box } ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        val crop = engine.embedder.crop(frame, box)
        val ref = engine.create(Model.REID, cpu).use { n -> Embedder(engine.reid).embed(n, crop) }
        val out = ArrayList<SpeedResult>()
        val total = models.sumOf { configs(it).size }
        var done = 0
        for (m in models) {
            val bytes = engine.modelBytes(m)
            for (cfg in configs(m)) {
                if (cancelled) break
                progress("${m.label} · ${cfg.label} (${++done} of $total)")
                out.add(runOne(m, cfg, bytes, frame, all, crop, ref))
            }
        }
        sample.recycle()
        DebugLog.add("engine", "Speed test ${if (cancelled) "stopped" else "finished"}: " + out.joinToString { "${it.model.key} ${it.cfg} ${it.ms?.let { ms -> "%.0f ms".format(ms) } ?: "-"}${if (it.ok) "" else " (${it.note})"}" })
        return out
    }

    private fun runOne(m: Model, cfg: RunConfig, bytes: ByteArray, frame: BitmapFrame, all: List<DoubleArray>, crop: IntArray, ref: FloatArray): SpeedResult {
        val net = try {
            engine.create(m, cfg, bytes)
        } catch (e: Throwable) {
            return SpeedResult(m, cfg, null, false, "not available")
        }
        net.use { n ->
            try {
                val finder = CowDetector()
                val embedder = Embedder(engine.reid)
                val once: () -> Boolean = when (m) {
                    Model.DET_NANO, Model.DET_TINY -> { { finder.detect(frame, n, all, 0.25).dets.isNotEmpty() } }
                    Model.REID -> { { dot(embedder.embed(n, crop), ref) > 0.98 } }
                }
                val ok = once() && once() // the first run also warms up
                val times = ArrayList<Double>()
                repeat(if (m == Model.REID) 5 else 8) {
                    val t0 = System.nanoTime()
                    once()
                    times.add((System.nanoTime() - t0) / 1e6)
                }
                times.sort()
                return SpeedResult(m, cfg, times[times.size / 2], ok, if (ok) null else "wrong results")
            } catch (e: Throwable) {
                return SpeedResult(m, cfg, null, false, e.message?.take(60) ?: "failed")
            }
        }
    }

    /** Saves the fastest correct setup per model (used when the accelerator setting is Auto). */
    fun useFastest(results: List<SpeedResult>) {
        val best = results.filter { it.ok && it.ms != null }.groupBy { it.model }.mapValues { (_, v) -> v.minBy { it.ms!! }.cfg }
        DebugLog.add("engine", "Speed test: using ${best.entries.joinToString { "${it.key.key} ${it.value}" }}")
        app.prefs.tuned = Json.write(best.map { (m, c) -> m.key to c.toString() }.toMap())
        app.prefs.accel = "auto"
        app.prefs.threads = 0
    }

    companion object {
        const val SAMPLE = "samples/cow_a1.jpg"
    }
}
