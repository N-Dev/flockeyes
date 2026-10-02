package io.github.ndev.flockeyes.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.FINDER_CONF
import io.github.ndev.flockeyes.ai.BitmapFrame
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.core.count.Scan
import io.github.ndev.flockeyes.core.count.ScanOptions
import io.github.ndev.flockeyes.core.count.Shown
import io.github.ndev.flockeyes.core.count.Sighting
import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.debug.DebugLog
import io.github.ndev.flockeyes.scan.CowOverlay
import io.github.ndev.flockeyes.scan.clock
import io.github.ndev.flockeyes.scan.fieldColors
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.cows
import io.github.ndev.flockeyes.ui.whenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How far through a video the AI is, and what it has seen so far. */
class VideoProgress(
    /** Video time analysed (ms) and the video's length. */
    val t: Double,
    val duration: Double,
    val frames: Int,
    /** How many times faster than real time. */
    val speed: Double,
    /** A small copy of the frame, and the cows on it. */
    val preview: Bitmap?,
    val boxes: List<Shown>,
    val count: Int,
    val named: Int,
    val fresh: Int,
    val peak: Int,
)

/** A small copy of a frame for the screen (the decoder reuses its bitmap). */
private fun previewOf(b: Bitmap, width: Int = 640): Bitmap {
    val w = minOf(width, b.width)
    return Bitmap.createScaledBitmap(b, w, maxOf(1, Math.round(b.height * w.toDouble() / b.width).toInt()), true)
}

/**
 * Counts the cows in a video with the same AI as the live camera, about eight frames a second of the
 * video. Unlike the camera it takes its time over each frame, so every cow gets its looks. Saved as a
 * field count (marked as from a video) when it gets to the end.
 */
class VideoCountJob(private val app: App, private val uri: Uri, private val info: VideoInfo, private val name: String) {
    @Volatile
    var cancelled = false
    val progress = MutableStateFlow<VideoProgress?>(null)

    /** Counts the whole video and saves it. Returns the count's id, or null if stopped. Run off the main thread. */
    fun run(): Long? {
        val p = app.prefs
        val e = app.engine
        val opt = ScanOptions().also { e.configure(it) }
        val embedder = Embedder(e.reid)
        val scan = Scan(app.herd, opt, embedder)
        val detector = CowDetector()
        val model = if (p.finderModel == "nano") Model.DET_NANO else Model.DET_TINY
        val conf = FINDER_CONF[p.sensitivity] ?: 0.3
        // When the video was recorded, if the file says; else it ends now.
        val start = info.recorded ?: (System.currentTimeMillis() - info.durationMs)
        val sightings = ArrayList<Sighting>()
        var frame: BitmapFrame? = null
        val wall0 = System.nanoTime()
        var n = 0
        var shownAt = 0L
        DebugLog.add("video", "Counting a video: ${info.width}x${info.height}, ${info.durationMs / 1000} s, ${model.key}")
        scan.start(gate = false)
        VideoDecoder(app, uri).frames(maxSide = 1920, stepMs = 125.0) { bmp, t ->
            if (cancelled) return@frames false
            val f = frame?.also { it.bitmap = bmp } ?: BitmapFrame(bmp).also { frame = it }
            val now = start + t.toLong()
            val res = detector.detect(f, e.net(model), CowDetector.tiles(info.aspect, p.far), conf, p.lookalikes)
            val out = scan.frame(res.dets, f, t, now, budget = 6)
            sightings.addAll(out.saved)
            for (lk in out.looks) sightings.addAll(scan.look(lk, embedder.embed(e.net(Model.REID), lk.px), now).saved)
            n++
            val wall = System.nanoTime()
            if (wall - shownAt > 300_000_000L) {
                shownAt = wall
                val s = scan.session
                progress.value = VideoProgress(t, info.durationMs.toDouble(), n, t / ((wall - wall0) / 1e6).coerceAtLeast(1.0), previewOf(bmp), scan.shown(t), s.count, s.seen.size, s.fresh.size, s.peak)
            }
            true
        }
        if (cancelled) {
            scan.stop(System.currentTimeMillis())
            DebugLog.add("video", "Video counting stopped after $n frames")
            return null
        }
        val end = start + info.durationMs
        sightings.addAll(scan.stop(end))
        val s = scan.session
        val id = app.db.newCount("field", name, start, "video", "", "", e.reid.key)
        app.db.addSightings(id, sightings)
        app.db.updateCount(id, end, s.count, s.peak, s.unknown, s.fresh.size, app.herd.size, 0, 0)
        if (name.isNotBlank()) p.fieldNames = (listOf(name) + p.fieldNames.filter { it != name })
        app.dataChanged()
        val secs = (System.nanoTime() - wall0) / 1e9
        DebugLog.add("video", "Video counted: ${s.count} cows (${s.seen.size} told apart, ${s.fresh.size} new) in $n frames, ${"%.0f".format(secs)} s (count $id)")
        progress.value = VideoProgress(info.durationMs.toDouble(), info.durationMs.toDouble(), n, info.durationMs / 1000.0 / secs.coerceAtLeast(0.001), progress.value?.preview, emptyList(), s.count, s.seen.size, s.fresh.size, s.peak)
        return id
    }
}

private class Loaded(val info: VideoInfo, val still: Bitmap)

/** Counting the cows in a video: a look at it first, then the count with a progress bar, then the result. */
@Composable
fun VideoCountScreen(uri: Uri, onClose: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var loaded by remember(uri) { mutableStateOf<Loaded?>(null) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    var job by remember(uri) { mutableStateOf<VideoCountJob?>(null) }
    var done by remember(uri) { mutableStateOf<Long?>(null) }
    var name by rememberSaveable(uri) { mutableStateOf("") }
    val none = remember { MutableStateFlow<VideoProgress?>(null) }
    val progress = (job?.progress ?: none).collectAsState().value
    val running = job != null && done == null

    LaunchedEffect(uri) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val d = VideoDecoder(context.applicationContext, uri)
                val info = d.info()
                Loaded(info, d.still(1280) ?: throw IllegalStateException("no frames could be read"))
            }
        }
        r.onSuccess { loaded = it }.onFailure { failure = "That video can’t be read here (${it.message ?: it.javaClass.simpleName})." }
    }
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(uri) { onDispose { job?.cancelled = true } }
    BackHandler {
        job?.cancelled = true
        onClose()
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                job?.cancelled = true
                onClose()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text("Count the cows in a video", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        val l = loaded
        if (failure != null) {
            Text(failure!!, color = C.amber, modifier = Modifier.padding(16.dp))
        } else if (l == null) {
            Text("Reading the video…", color = C.muted, modifier = Modifier.padding(16.dp))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                val shown = progress?.preview ?: l.still
                Box(Modifier.fillMaxWidth().background(Color.Black)) {
                    Image(shown.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    if (progress != null && running) {
                        CowOverlay(progress.boxes, emptyList(), l.info.aspect, doubleArrayOf(0.0, 0.0, 1.0, 1.0), app.prefs.debug, Modifier.matchParentSize())
                    }
                }
                Text(
                    "${clock(l.info.durationMs)} · ${l.info.width}×${l.info.height}" + (l.info.recorded?.let { " · recorded ${whenLabel(it).lowercase()}" } ?: ""),
                    color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                when {
                    done != null -> {
                        Text("Done: ${cows(progress?.count ?: 0)} counted", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${progress?.named ?: 0} told apart, ${progress?.fresh ?: 0} learnt as new; the most in view at once was ${progress?.peak ?: 0}.",
                            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val id = done
                                    onClose()
                                    app.openCount.value = id
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                            ) { Text("See it", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = onClose) { Text("Close") }
                        }
                    }
                    running -> {
                        val frac = if (progress != null && progress.duration > 0) (progress.t / progress.duration).toFloat().coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { frac }, color = C.accent, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
                        Text("Counting… keep this screen open", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            if (progress == null) "Starting…" else "${cows(progress.count)} so far (${progress.fresh} new) · ${clock(progress.t.toLong())} of ${clock(progress.duration.toLong())} · %.1f× speed".format(progress.speed),
                            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                job?.cancelled = true
                                job = null
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Stop") }
                    }
                    else -> {
                        Text(
                            "The whole video is gone through, a few frames a second, with the same cow finder and recognition as the camera. " +
                                (if (app.prefs.learn) "Cows it doesn’t know are learnt." else "Learning is off: cows it doesn’t know are counted as not recognised."),
                            color = C.muted, fontSize = 13.sp,
                        )
                        OutlinedTextField(
                            name, { name = it.take(60) }, singleLine = true, label = { Text("Which field? (optional)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = fieldColors(),
                        )
                        Button(
                            onClick = {
                                val j = VideoCountJob(app, uri, l.info, name.trim())
                                job = j
                                scope.launch {
                                    val r = withContext(Dispatchers.Default) { runCatching { j.run() } }
                                    r.onSuccess { id -> if (id != null && job === j) done = id }
                                        .onFailure {
                                            DebugLog.error("video", "Counting the video failed", it)
                                            if (job === j) {
                                                job = null
                                                failure = "Counting stopped: ${it.message ?: it.javaClass.simpleName}"
                                            }
                                        }
                                }
                            },
                            modifier = Modifier.padding(top = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                        ) { Text("Count the cows", fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
