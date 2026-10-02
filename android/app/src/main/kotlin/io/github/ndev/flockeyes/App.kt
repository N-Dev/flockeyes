package io.github.ndev.flockeyes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.mutableIntStateOf
import io.github.ndev.flockeyes.ai.Engine
import io.github.ndev.flockeyes.camera.CameraHost
import io.github.ndev.flockeyes.core.Json
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.herd.Herd
import io.github.ndev.flockeyes.data.Db
import io.github.ndev.flockeyes.data.Updates
import io.github.ndev.flockeyes.herd.HerdStore
import io.github.ndev.flockeyes.scan.Scanner
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** App-wide singletons: settings, storage, the AI engine, the herd and the two live counters. */
class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var db: Db
        private set
    lateinit var engine: Engine
        private set

    /** The back camera, shared by the screens and the background service. */
    lateinit var camera: CameraHost
        private set

    /** The cows the app knows (in memory, for the camera thread), and what keeps them saved. */
    val herd = Herd()
    lateinit var store: HerdStore
        private set

    /** Counting a field, and counting at a gate. */
    val field: Scanner by lazy { Scanner(this, gate = false) }
    val gate: Scanner by lazy { Scanner(this, gate = true) }

    /** A video shared to the app ("Share → Flock Eyes"), or picked, waiting to be counted. */
    val openVideo = MutableStateFlow<Uri?>(null)

    /** A saved count to show in History (after "See it"). */
    val openCount = MutableStateFlow<Long?>(null)

    /** A cow to show in the Herd tab. */
    val openCow = MutableStateFlow<Int?>(null)

    /** Checks GitHub for a newer release. */
    val updates: Updates by lazy { Updates(this) }

    /** Bumped when counts or cows are saved or deleted, so lists reload. */
    val dataVersion = MutableStateFlow(0)

    fun dataChanged() {
        dataVersion.update { it + 1 }
    }

    /** Background work that mustn't hold up the camera: tidying, exports, backups. */
    val io = Executors.newSingleThreadExecutor()

    /** The same thread, for coroutines. */
    val ioDispatcher by lazy { io.asCoroutineDispatcher() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        db = Db(this)
        engine = Engine(this)
        camera = CameraHost(this)
        store = HerdStore(this)
        channels()
        // The herd is read before anything can look at a cow.
        store.load()
        updates.checkIfDue()
        io.execute {
            // Start loading the models so the camera is ready sooner; then bring looks saved with an
            // older recognition model up to date.
            runCatching { engine.warmUp() }
            runCatching { store.refreshOldLooks() }
        }
    }

    fun haptic(ms: Long = 18) {
        if (!prefs.haptics) return
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BACKGROUND, "Counting at a gate", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Flock Eyes counts cows at a gate with the screen off"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_BACKGROUND = "background"

        lateinit var instance: App
            private set
    }
}

/**
 * Settings, kept in SharedPreferences. Reading one from a screen subscribes it to changes (through a
 * snapshot state), so screens redraw when a setting changes; `version` does the same for other code.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("flockeyes", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val changes = mutableIntStateOf(0)

    private fun bump() {
        _version.update { it + 1 }
        changes.intValue += 1
    }

    /** Marks the caller as depending on the settings (for Compose). */
    private fun track() {
        changes.intValue
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String {
            track()
            return sp.getString(key, def) ?: def
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
            bump()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            track()
            return sp.getBoolean(key, def)
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
            bump()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            track()
            return sp.getInt(key, def)
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
            bump()
        }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long {
            track()
            return sp.getLong(key, def)
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            sp.edit().putLong(key, value).apply()
            bump()
        }
    }

    private fun dbl(key: String, def: Double) = object : ReadWriteProperty<Any?, Double> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Double {
            track()
            return sp.getString(key, null)?.toDoubleOrNull() ?: def
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) {
            sp.edit().putString(key, value.toString()).apply()
            bump()
        }
    }

    // ---------------------------------------------------------------- recognising cows
    /** loose | normal | strict: how alike two looks must be to be the same cow. */
    var strictness by str("strictness", "normal")

    /** Debug mode: exactly how far a cow must stand out from the next most alike one (0 = use the "How sure" setting). */
    var gapOverride by dbl("gap_override", 0.0)

    /** Learn cows the app doesn't know (off: they're counted as unrecognised). */
    var learn by bool("learn", true)

    // ---------------------------------------------------------------- finding cows
    /** auto | tiny | nano */
    var finderModel by str("finder", "auto")

    /** low | medium | high */
    var sensitivity by str("sensitivity", "medium")

    /** Animals the finder calls a horse or a sheep count as cows too. */
    var lookalikes by bool("lookalikes", true)

    /** Field counts analyse the frame in two halves, so distant cows show bigger. */
    var far by bool("far", false)
    var haptics by bool("haptics", true)

    // ---------------------------------------------------------------- field
    /** Field screen zoom (below 1 is the ultra-wide lens). */
    var fieldZoom by dbl("f_zoom", 1.0)

    /** Field names used before, newest first (JSON list), offered when a count is saved. */
    private var fieldNamesJson by str("f_names", "[]")
    var fieldNames: List<String>
        get() = runCatching { (Json.parse(fieldNamesJson) as List<*>).mapNotNull { it as? String } }.getOrDefault(emptyList())
        set(v) {
            fieldNamesJson = Json.write(v.take(12))
        }

    // ---------------------------------------------------------------- gate
    private var gateLineJson by str("g_line", "")
    var gateLine: DoubleArray
        get() = runCatching { (Json.parse(gateLineJson) as List<*>).map { (it as Number).toDouble() }.toDoubleArray().also { require(it.size == 4) } }
            .getOrElse { Gate.default() }
        set(v) {
            gateLineJson = Json.write(v.toList())
        }

    /** What the two directions mean here, e.g. "Out to the field" and "Back in". */
    var dir1 by str("g_dir1", "Left to right")
    var dir2 by str("g_dir2", "Right to left")
    var gateName by str("g_name", "")
    var gateSetupDone by bool("g_setup", false)

    /** portrait | landscape: which way round the line was set up ("" before the first set-up). */
    var gateOrientation by str("g_orient", "")

    /** The zoom the line was set up at. */
    var gateZoom by dbl("g_zoom", 1.0)

    /** Keep counting with the screen off or another app open (a notification shows meanwhile). */
    var backgroundCounting by bool("g_background", true)

    /** Seconds without a touch before the screen dims during a gate count (0 = never). */
    var dimAfter by int("g_dim", 60)

    // ---------------------------------------------------------------- display
    /** auto | portrait | landscape: which way round the app is (the Gate tab follows its set-up). */
    var orientation by str("orientation", "auto")

    /** Debug mode: overlays, timings, match scores and the log. */
    var debug by bool("debug", false)

    // ---------------------------------------------------------------- app
    /** The speed test was offered (once, at first launch). */
    var speedTestOffered by bool("speed_offered", false)

    /** The "how it tells cows apart" note was read. */
    var introSeen by bool("intro_seen", false)

    /** Look for a newer release on GitHub once a day. */
    var updateCheck by bool("update_check", true)
    var lastUpdateCheck by long("update_last", 0L)

    /** The latest release the last check found (JSON), so the update banner shows between checks. */
    var latestRelease by str("update_latest", "")

    // ---------------------------------------------------------------- AI engine
    /** auto | CPU | XNNPACK | NNAPI */
    var accel by str("accel", "auto")

    /** CPU threads; 0 = automatic */
    var threads by int("threads", 0)

    /** Per-model settings found fastest by the speed test, e.g. {"detTiny": "XNNPACK:4"}. */
    var tuned by str("tuned", "")

    // ---------------------------------------------------------------- backup
    /** Settings that belong to this phone rather than to the user (not copied by a backup). */
    private val phoneOnly = setOf("tuned", "update_last", "update_latest", "speed_offered")

    /** Every setting, with its type, for a backup: {"learn": {"t": "b", "v": true}, ...}. */
    fun export(): Map<String, Any?> = sp.all.filterKeys { it !in phoneOnly }.mapNotNull { (k, v) ->
        val t = when (v) {
            is String -> "s"
            is Boolean -> "b"
            is Int -> "i"
            is Long -> "l"
            is Float -> "f"
            else -> return@mapNotNull null
        }
        k to mapOf("t" to t, "v" to v)
    }.toMap()

    /** Puts back settings from a backup (settings it doesn't mention keep their values). */
    fun import(m: Map<String, Any?>) {
        val e = sp.edit()
        for ((k, raw) in m) {
            if (k in phoneOnly) continue
            val o = raw as? Map<*, *> ?: continue
            val v = o["v"]
            when (o["t"]) {
                "s" -> (v as? String)?.let { e.putString(k, it) }
                "b" -> (v as? Boolean)?.let { e.putBoolean(k, it) }
                "i" -> (v as? Number)?.let { e.putInt(k, it.toInt()) }
                "l" -> (v as? Number)?.let { e.putLong(k, it.toLong()) }
                "f" -> (v as? Number)?.let { e.putFloat(k, it.toFloat()) }
            }
        }
        e.commit()
        bump()
    }
}

/** How sure the cow finder must be, by the sensitivity setting. */
val FINDER_CONF = mapOf("low" to 0.4, "medium" to 0.3, "high" to 0.22)

/** How the "How sure" setting scales the gap a cow must stand out by from the next most alike one. */
val STRICTNESS = mapOf("loose" to 0.7, "normal" to 1.0, "strict" to 1.4)
