package io.github.ndev.flockeyes.scan

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.CameraGate
import io.github.ndev.flockeyes.camera.CameraHost
import io.github.ndev.flockeyes.camera.CameraPreview
import io.github.ndev.flockeyes.camera.CameraUse
import io.github.ndev.flockeyes.camera.zoomSteps
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.Confirm
import io.github.ndev.flockeyes.ui.Pill
import io.github.ndev.flockeyes.ui.RoundButton
import io.github.ndev.flockeyes.ui.cows
import io.github.ndev.flockeyes.video.VideoCountScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** The Field tab: a live count (once the camera is allowed), or counting the cows in a video. */
@Composable
fun FieldTab() {
    val app = App.instance
    val video by app.openVideo.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) app.openVideo.value = uri }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val v = video
    if (v != null) {
        VideoCountScreen(v, onClose = { app.openVideo.value = null })
        return
    }
    CameraGate(extra = { OutlinedButton(onClick = pick) { Text("Count the cows in a video instead") } }) { FieldScreen(onVideo = pick) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldScreen(onVideo: () -> Unit = {}) {
    val app = App.instance
    val prefs = app.prefs
    val view = LocalView.current
    val ui by app.field.ui.collectAsState()
    val notice by app.engine.notice.collectAsState()
    val data by app.dataVersion.collectAsState()
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val host = app.camera
    val zoomInfo by host.zoom.collectAsState()
    val bg by host.background.collectAsState()
    // A gate count is running in the background: the camera can't count a field at the same time.
    val busy = bg != null && bg?.sink !== app.field
    val use = remember { CameraUse(app.field, CameraHost.FIELD_RES, "field") }
    var zoom by rememberSaveable { mutableFloatStateOf(prefs.fieldZoom.toFloat()) }
    var menu by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<Long?>(null) }
    var namingCount by rememberSaveable { mutableStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val aspect = if (ui.ready) ui.aspect else if (landscape) 9.0 / 16 else 16.0 / 9

    // Settings changed elsewhere (strictness, learning): the scanner takes them up.
    val prefsVersion by prefs.version.collectAsState()
    LaunchedEffect(prefsVersion) { app.field.reconfigure() }
    LaunchedEffect(zoom) { prefs.fieldZoom = zoom.toDouble() }
    LaunchedEffect(ui.running) {
        while (ui.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Turned too fast for the app to follow: cows it comes back to would be counted again.
    LaunchedEffect(ui.swings) {
        if (ui.running && ui.swings > 0) snack.showSnackbar("Turned too fast to follow. Pan slowly: cows you go back over now may be counted twice.")
    }

    BackHandler(enabled = ui.running) {
        scope.launch { snack.showSnackbar("Tap Finish to save the count, or Discard") }
    }

    // The screen stays on while the camera is watching.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Cows of the herd not seen yet in this count.
    val missing = remember(ui.named, ui.running, data) {
        if (!ui.running) emptyList() else synchronized(app.herd) {
            val seen = app.field.scan.session.seen.keys
            app.herd.cows.filter { it.id !in seen }.map { it.label }
        }
    }
    val herdSize = remember(data, ui.fresh) { app.herd.size }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val stage: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                if (busy) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The camera is counting at the gate", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Stop the gate count to count a field.", color = C.muted)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { app.gate.stop() }) { Text("Stop the gate count") }
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            // Pinch to zoom, from the ultra-wide lens (if the phone has one) to the longest zoom.
                            detectTransformGestures { _, _, change, _ ->
                                val z = host.zoom.value
                                val next = zoom * change
                                zoom = if (z != null) next.coerceIn(z.min, z.max) else next.coerceIn(0.5f, 10f)
                            }
                        },
                    ) {
                        CameraPreview(Modifier.fillMaxSize(), use, zoom)
                        CowOverlay(ui.boxes, ui.raw, aspect, ui.roi, prefs.debug, Modifier.fillMaxSize(), places = ui.places)
                    }
                }
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val status = when {
                            busy -> "Counting at the gate"
                            !ui.ready -> ui.status
                            else -> "${if (ui.running) "Counting" else "Watching"} · ${fpsText(ui.fps)} fps" + (if (ui.waiting > 0) " · looking" else "")
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Pill(
                                status, dot = if (busy || !ui.ready) C.amber else if (ui.running) C.red else C.accent,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        snack.showSnackbar(
                                            if (ui.ready) "${ui.model} cow finder ${ui.msFind.toInt()} ms a frame · a look at a cow ${ui.msLook.toInt()} ms" else ui.status,
                                        )
                                    }
                                },
                            )
                        }
                        Box {
                            RoundButton(Icons.Filled.MoreVert, "More") { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Far away: look at the picture in two halves") },
                                    leadingIcon = { Checkbox(checked = prefs.far, onCheckedChange = null) },
                                    onClick = {
                                        menu = false
                                        prefs.far = !prefs.far
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Learn cows it doesn’t know") },
                                    leadingIcon = { Checkbox(checked = prefs.learn, onCheckedChange = null) },
                                    enabled = !ui.running,
                                    onClick = {
                                        menu = false
                                        prefs.learn = !prefs.learn
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Count the cows in a video") },
                                    enabled = !ui.running,
                                    onClick = {
                                        menu = false
                                        onVideo()
                                    },
                                )
                            }
                        }
                    }
                    if (prefs.debug && !busy && ui.ready) ScanDebug(ui, Modifier.padding(horizontal = 12.dp))
                }
                val steps = zoomSteps(zoomInfo)
                if (!busy && steps.isNotEmpty()) {
                    ZoomChips(steps, zoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { zoom = it }
                }
                notice?.let {
                    Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 46.dp))
                }
            }
        }
        // The counts scroll; the buttons under them are always in view.
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.background(C.bg).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (ui.running) "${ui.count}" else "–", color = C.accent, fontSize = 54.sp, fontWeight = FontWeight.Bold, style = TNUM, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f).padding(bottom = 9.dp)) {
                            Text(
                                if (ui.running) (if (ui.count == 1) "cow counted" else "cows counted") else "Not counting",
                                color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (ui.running) clock(now - ui.started) else if (herdSize == 0) "No cows learnt yet" else "${cows(herdSize)} in the herd",
                                color = C.muted, fontSize = 12.5.sp, style = TNUM,
                            )
                        }
                        ui.last?.takeIf { ui.running }?.let { last ->
                            Pill(if (last.fresh) "New: ${last.label}" else last.label, color = if (last.fresh) C.amber else C.text, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mini("${ui.inView}", "in view", Modifier.weight(1f).fillMaxHeight())
                        Mini(if (ui.running) "${ui.named}" else "–", "known by markings", Modifier.weight(1f).fillMaxHeight())
                        Mini(if (ui.running) "${ui.fresh}" else "–", "new", Modifier.weight(1f).fillMaxHeight())
                        Mini(if (ui.running && herdSize > 0) "${missing.size}" else "–", "not seen yet", Modifier.weight(1f).fillMaxHeight())
                    }
                    if (ui.running && ui.count > ui.named + ui.unknown) {
                        Text(
                            (if (ui.named > 0) "${ui.named} known by their markings; the others are" else "They are") +
                                " counted by where they stand: too far off, hidden or too close together to tell apart. " +
                                "Stay in one spot and pan slowly, so each is counted once. The most in view at once was ${ui.peak}.",
                            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (ui.running && ui.unknown > 0) {
                        Text("${ui.unknown} not recognised (learning is off).", color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (ui.running && missing.isNotEmpty() && missing.size <= 40 && ui.named > 0) {
                        Text("Not seen yet", color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (name in missing) Chip(name)
                        }
                    }
                    if (!ui.running && !prefs.introSeen) Intro { prefs.introSeen = true }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.running) {
                        OutlinedButton(onClick = { discard = true }, modifier = Modifier.weight(1f)) { Text("Discard") }
                        Button(
                            onClick = {
                                val count = ui.count
                                val id = app.field.stop()
                                if (id != null) {
                                    namingCount = count
                                    naming = id
                                }
                            },
                            modifier = Modifier.weight(1.6f),
                            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                        ) { Text("Finish and save", fontWeight = FontWeight.Bold) }
                    } else {
                        Button(
                            onClick = { app.field.start() },
                            enabled = ui.ready && !busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                        ) { Text("Start counting", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(320.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().heightIn(max = (config.screenHeightDp * 0.46f).dp.coerceAtMost(400.dp)))
            }
        }

        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)) { d ->
            Snackbar(d, containerColor = C.surface2, contentColor = C.text, actionColor = C.accent)
        }
    }

    naming?.let { id ->
        NameCount(
            count = namingCount,
            suggestions = prefs.fieldNames,
            onDone = { name ->
                naming = null
                app.field.rename(id, name.ifBlank { "Field" })
                scope.launch {
                    val r = snack.showSnackbar("Saved: ${cows(namingCount)} in ${name.ifBlank { "the field" }}", actionLabel = "See it", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) app.openCount.value = id
                }
            },
        )
    }
    if (discard) {
        Confirm("Discard this count?", "Cows learnt during it stay in the herd.", "Discard", onDismiss = { discard = false }) {
            discard = false
            app.field.discard()
        }
    }
}

@Composable
private fun Mini(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = TNUM)
        Text(label, color = C.muted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
internal fun Chip(text: String, color: Color = C.amber, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(C.surface2).border(1.dp, C.line2, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, color = color, fontSize = 12.5.sp, maxLines = 1) }
}

/** Shown once: how the app tells cows apart, and what that means for using it. */
@Composable
private fun Intro(onDismiss: () -> Unit) {
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Text("How it counts and tells cows apart", color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(
            "Start a count, stand in one spot and pan slowly across the field. Each cow is counted once, by where it stands, however often " +
                "the phone passes over it; cows it has told apart by their markings are counted once wherever they wander. For a herd on the " +
                "move, use the Gate tab.\n\n" +
                "It tells cows apart by the markings on their sides, so it only learns a cow that is big enough in the picture (zoom in), side-on " +
                "and standing clear of the others. A cow’s two sides are marked differently: it knows both once it has watched the cow turn " +
                "round; if one is learnt twice, merge the two in the Herd tab.",
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Got it", color = C.accent) }
    }
}

/** After a count: which field was that? */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameCount(count: Int, suggestions: List<String>, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(name.trim()) },
        containerColor = C.surface,
        title = { Text("${cows(count)} counted. Which field?", color = C.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    placeholder = { Text("e.g. Top field") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.accent, unfocusedBorderColor = C.line2, cursorColor = C.accent,
                        focusedTextColor = C.text, unfocusedTextColor = C.text, focusedPlaceholderColor = C.muted, unfocusedPlaceholderColor = C.muted,
                    ),
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in suggestions.take(8)) Chip(s, color = C.text) { name = s }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }) { Text("Save", color = C.accent, fontWeight = FontWeight.Bold) } },
    )
}
