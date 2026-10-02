package io.github.ndev.flockeyes.scan

import android.app.Activity
import android.content.res.Configuration
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.CameraGate
import io.github.ndev.flockeyes.camera.CameraPreview
import io.github.ndev.flockeyes.camera.zoomLabel
import io.github.ndev.flockeyes.camera.zoomSteps
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.service.BackgroundService
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.Pill
import io.github.ndev.flockeyes.ui.RoundButton
import io.github.ndev.flockeyes.ui.Segmented
import io.github.ndev.flockeyes.ui.rememberNotificationRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** A line end (0 or 1), or the whole line (-1), being dragged. */
internal class Grab(val end: Int, val from: Offset)

/** The Gate tab: cows counted as they cross a line, by direction, and named as they pass. */
@Composable
fun GateTab() {
    CameraGate { GateScreen() }
}

@Composable
fun GateScreen() {
    val app = App.instance
    val prefs = app.prefs
    val context = LocalContext.current
    val view = LocalView.current
    val ui by app.gate.ui.collectAsState()
    val notice by app.engine.notice.collectAsState()
    val prefsVersion by prefs.version.collectAsState()
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(prefs.gateLine) }
    var dir1 by rememberSaveable { mutableStateOf("") }
    var dir2 by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var draftZoom by rememberSaveable { mutableFloatStateOf(prefs.gateZoom.toFloat()) }
    var menu by remember { mutableStateOf(false) }
    val host = app.camera
    val zoomInfo by host.zoom.collectAsState()
    val bg by host.background.collectAsState()
    val use = remember { BackgroundService.use(app) }
    val askNotifications = rememberNotificationRequest()
    val here = if (landscape) "landscape" else "portrait"

    // Until the first frame arrives, assume the picture is the shape of the screen.
    val aspect = if (ui.ready) ui.aspect else if (landscape) 9.0 / 16 else 16.0 / 9
    val saved = remember(prefsVersion) { prefs.gateLine }
    val line = if (editing) draft else saved
    val roi = if (editing) Gate.region(draft, aspect) else ui.roi

    LaunchedEffect(prefsVersion) { app.gate.reconfigure() }

    fun openEditor(level: Boolean? = null) {
        if (ui.running) return
        draft = when (level) {
            true -> Gate.level()
            false -> Gate.default()
            null -> prefs.gateLine.copyOf()
        }
        draftZoom = prefs.gateZoom.toFloat()
        dir1 = prefs.dir1
        dir2 = prefs.dir2
        place = prefs.gateName
        if (level != null) {
            val n = Gate.directionNames(draft, aspect)
            dir1 = n.first
            dir2 = n.second
        }
        editing = true
    }

    fun saveEditor() {
        val names = Gate.directionNames(draft, aspect)
        prefs.gateLine = draft
        prefs.dir1 = dir1.trim().ifEmpty { names.first }
        prefs.dir2 = dir2.trim().ifEmpty { names.second }
        prefs.gateName = place.trim()
        prefs.gateZoom = draftZoom.toDouble()
        // The line fits the picture this way round (and at this zoom): the Gate tab keeps to both.
        prefs.gateOrientation = here
        prefs.gateSetupDone = true
        app.gate.reconfigure()
        editing = false
    }

    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = ui.running && !editing) {
        scope.launch { snack.showSnackbar("Tap Stop to finish counting") }
    }

    // The screen stays on while the camera is watching the gate.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Without background counting, the camera stops while the app is in the background (or the screen
    // is off), and so does counting.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var stoppedAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && app.gate.ui.value.running && !BackgroundService.running.value) stoppedAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && stoppedAt > 0) {
                val gap = (System.currentTimeMillis() - stoppedAt) / 1000
                stoppedAt = 0
                if (gap >= 3 && app.gate.ui.value.running) {
                    scope.launch { snack.showSnackbar("Counting was paused for ${clock(gap * 1000)} while Flock Eyes wasn’t on screen", duration = SnackbarDuration.Long) }
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Dimming: after a while without a touch, the screen goes dark to save battery and heat.
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var warnedBattery by remember { mutableStateOf(false) }
    LaunchedEffect(ui.running, editing, prefsVersion) {
        lastTouch[0] = System.currentTimeMillis()
        var tick = 0
        while (true) {
            now = System.currentTimeMillis()
            val dimAfter = prefs.dimAfter
            dimmed = ui.running && !editing && dimAfter > 0 && now - lastTouch[0] > dimAfter * 1000L
            if (tick++ % 30 == 0) battery = batteryLevel(context)
            val b = battery
            if (ui.running && b != null && !b.second && b.first < 25 && !warnedBattery) {
                warnedBattery = true
                scope.launch { snack.showSnackbar("Battery below 25%. Plug the phone in to keep counting.", duration = SnackbarDuration.Long) }
            }
            delay(1000)
        }
    }
    val window = (context as? Activity)?.window
    DisposableEffect(dimmed) {
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (dimmed) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    fun startStop() {
        if (ui.running) {
            val n = ui.count
            val id = app.gate.stop()
            scope.launch {
                val r = snack.showSnackbar("Saved: $n crossing${if (n == 1) "" else "s"}", actionLabel = "See it", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed && id != null) app.openCount.value = id
            }
        } else {
            if (editing) saveEditor()
            // Counting keeps to the way round it started, so a knock doesn't move the line.
            if (prefs.gateOrientation.isEmpty()) prefs.gateOrientation = here
            if (prefs.backgroundCounting) askNotifications()
            app.gate.start(prefs.gateName.ifBlank { "Gate" })
            lastTouch[0] = System.currentTimeMillis()
            warnedBattery = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            // Every touch anywhere counts as activity (without taking the touch from what's under it).
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch[0] = System.currentTimeMillis()
                }
            }
        },
    ) {
        val stage: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                CameraPreview(Modifier.fillMaxSize(), use, if (editing) draftZoom else prefs.gateZoom.toFloat())
                CowOverlay(
                    boxes = ui.boxes, raw = ui.raw, aspect = aspect, roi = roi, debug = prefs.debug,
                    line = line, editing = editing,
                    dir1 = if (editing) dir1.ifBlank { Gate.directionNames(draft, aspect).first } else prefs.dir1,
                    dir2 = if (editing) dir2.ifBlank { Gate.directionNames(draft, aspect).second } else prefs.dir2,
                    modifier = Modifier.fillMaxSize().pointerInput(editing, aspect) {
                        if (!editing) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val r = videoRect(size.width.toFloat(), size.height.toFloat(), aspect)
                            val grab = pick(draft, down.position, r, 30.dp.toPx(), 24.dp.toPx()) ?: return@awaitEachGesture
                            down.consume()
                            val start = draft
                            drag(down.id) { change ->
                                draft = moved(start, grab, change.position, r)
                                change.consume()
                            }
                        }
                    },
                )
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val status = if (!ui.ready) ui.status else "${if (ui.running) "Counting" else "Watching"} · ${fpsText(ui.fps)} fps"
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Pill(
                                status, dot = if (!ui.ready) C.amber else if (ui.running) C.red else C.accent,
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
                                val other = if (landscape) "portrait" else "landscape"
                                DropdownMenuItem(
                                    text = { Text("Set up for $other") },
                                    enabled = !ui.running,
                                    onClick = {
                                        menu = false
                                        // The line only fits the picture one way round: turn it and set it again.
                                        prefs.gateOrientation = other
                                        openEditor(level = false)
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
                                    text = { Text("Keep counting with the screen off") },
                                    leadingIcon = { Checkbox(checked = prefs.backgroundCounting, onCheckedChange = null) },
                                    enabled = !ui.running,
                                    onClick = {
                                        menu = false
                                        prefs.backgroundCounting = !prefs.backgroundCounting
                                    },
                                )
                            }
                        }
                    }
                    if (prefs.debug && !editing && ui.ready) ScanDebug(ui, Modifier.padding(horizontal = 12.dp))
                }
                if (editing) {
                    Text(
                        "Drag the ends of the line across the gap the cows walk through",
                        color = C.text, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC0B110A)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                notice?.let {
                    Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
        }
        // The counts (or the set-up form) scroll; the buttons under them are always in view.
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.background(C.bg).imePadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (editing) {
                        SetupForm(
                            level = Gate.isLevel(draft, aspect),
                            onLevel = { l ->
                                draft = if (l) Gate.level() else Gate.default()
                                val n = Gate.directionNames(draft, aspect)
                                dir1 = n.first
                                dir2 = n.second
                            },
                            zoomSteps = zoomSteps(zoomInfo), zoom = draftZoom, onZoom = { draftZoom = it },
                            dir1 = dir1, onDir1 = { dir1 = it }, dir2 = dir2, onDir2 = { dir2 = it },
                            names = Gate.directionNames(draft, aspect),
                            place = place, onPlace = { place = it },
                        )
                    } else {
                        GatePanel(ui, now)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editing) {
                        OutlinedButton(
                            onClick = {
                                draft = if (Gate.isLevel(draft, aspect)) Gate.level() else Gate.default()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Reset line") }
                        Button(onClick = { saveEditor() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent)) {
                            Text("Done", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(onClick = { openEditor() }, enabled = !ui.running, modifier = Modifier.weight(1f)) { Text("Set up") }
                        Button(
                            onClick = { startStop() },
                            enabled = ui.ready || ui.running,
                            modifier = Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (ui.running) C.red else C.accent, contentColor = C.onAccent),
                        ) { Text(if (ui.running) "Stop" else "Start counting", fontWeight = FontWeight.Bold) }
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
                val share = if (editing) 0.55f else 0.42f
                panel(Modifier.fillMaxWidth().heightIn(max = (config.screenHeightDp * share).dp.coerceAtMost(if (editing) 460.dp else 360.dp)))
            }
        }

        if (dimmed) DimScreen(ui, now, battery) {
            lastTouch[0] = System.currentTimeMillis()
            dimmed = false
        }
        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)) { d ->
            Snackbar(d, containerColor = C.surface2, contentColor = C.text, actionColor = C.accent)
        }
    }
}

private fun batteryLevel(context: android.content.Context): Pair<Int, Boolean>? = runCatching {
    val bm = context.getSystemService(BatteryManager::class.java) ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level < 0 || level > 100) null else level to bm.isCharging
}.getOrNull()

@Composable
private fun ColumnScope.GatePanel(ui: ScanUi, now: Long) {
    val app = App.instance
    val prefs = app.prefs
    val data by app.dataVersion.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (ui.running) clock(now - ui.started) else "Not counting", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = TNUM)
            val sub = when {
                ui.running -> "counting" + (prefs.gateName.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                prefs.gateName.isNotEmpty() -> prefs.gateName
                !prefs.gateSetupDone -> "Tap Set up to put the line across the gap"
                else -> ""
            }
            if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 12.5.sp, maxLines = 1)
        }
        ui.last?.takeIf { ui.running }?.let { last ->
            Pill((if (last.fresh) "New: " else "") + last.label + (if (last.dir == 1) " →" else if (last.dir == 2) " ←" else ""), color = if (last.fresh) C.amber else C.text)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tally("${ui.n1}", prefs.dir1, Modifier.weight(1f))
        Tally("${ui.n2}", prefs.dir2, Modifier.weight(1f))
    }
    if (ui.running) {
        // Who has gone which way so far (cows that went one way and came back show where they ended up).
        val where = remember(ui.count, ui.counted, data) {
            synchronized(app.herd) { app.gate.scan.lastDirections().mapNotNull { (id, dir) -> app.herd[id]?.let { it.label to dir } } }
        }
        val unknown = ui.unknown
        Text(
            "${where.size} recognised" + (if (unknown > 0) " · $unknown crossing${if (unknown == 1) "" else "s"} by cows not recognised" else ""),
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
        )
        for ((dir, name) in listOf(1 to prefs.dir1, 2 to prefs.dir2)) {
            val list = where.filter { it.second == dir }.map { it.first }
            if (list.isNotEmpty()) {
                Text("$name: " + list.takeLast(30).joinToString(", "), color = C.text, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    } else if (prefs.gateOrientation.isNotEmpty()) {
        Text(
            "Line set up in ${prefs.gateOrientation} at ${zoomLabel(prefs.gateZoom.toFloat())}. Put the phone where cows pass one at a time, side-on and close.",
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Tally(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, color = C.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold, style = TNUM)
        Text(label, color = C.muted, fontSize = 12.5.sp, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.SetupForm(
    level: Boolean,
    onLevel: (Boolean) -> Unit,
    zoomSteps: List<Float>,
    zoom: Float,
    onZoom: (Float) -> Unit,
    dir1: String,
    onDir1: (String) -> Unit,
    dir2: String,
    onDir2: (String) -> Unit,
    names: Pair<String, String>,
    place: String,
    onPlace: (String) -> Unit,
) {
    Text("Set up the counting line", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "Fix the phone where it sees the gap side-on, close enough that a cow fills a good part of the picture. " +
            "Drag the line so cows cross it as they walk through.",
        color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )
    Label("The cows walk")
    Segmented(listOf("across" to "Across the picture", "towards" to "Towards or away"), if (level) "towards" else "across") { onLevel(it == "towards") }
    Spacer(Modifier.height(12.dp))
    if (zoomSteps.isNotEmpty()) {
        Label("Zoom")
        ZoomChips(zoomSteps, zoom) { onZoom(it) }
        Text("The line is set on this view, so counting keeps this zoom.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
    }
    Label("Cows going ${names.first.lowercase()} are going…")
    OutlinedTextField(dir1, { onDir1(it.take(30)) }, singleLine = true, placeholder = { Text("e.g. Out to the field") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
    Spacer(Modifier.height(8.dp))
    Label("Cows going ${names.second.lowercase()} are going…")
    OutlinedTextField(dir2, { onDir2(it.take(30)) }, singleLine = true, placeholder = { Text("e.g. Back in") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
    Spacer(Modifier.height(8.dp))
    Label("Where is this?")
    OutlinedTextField(place, { onPlace(it.take(60)) }, singleLine = true, placeholder = { Text("e.g. Parlour exit") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
}

@Composable
private fun Label(text: String) {
    Text(text, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.accent, unfocusedBorderColor = C.line2, cursorColor = C.accent,
    focusedTextColor = C.text, unfocusedTextColor = C.text, focusedPlaceholderColor = C.muted, unfocusedPlaceholderColor = C.muted,
)

/** The dark screen while counting: the running totals, big, and how to wake it. */
@Composable
private fun DimScreen(ui: ScanUi, now: Long, battery: Pair<Int, Boolean>?, onWake: () -> Unit) {
    val prefs = App.instance.prefs
    Column(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { onWake() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${ui.n1} · ${ui.n2}", color = Color(0xFF3F5233), fontSize = 64.sp, fontWeight = FontWeight.Bold, style = TNUM)
        Text("${prefs.dir1} · ${prefs.dir2}", color = Color(0xFF3A4636), fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        val b = battery?.let { " · battery ${it.first}%${if (it.second) ", charging" else ""}" } ?: ""
        Text(
            "Counting ${clock(now - ui.started)}$b · tap to wake", color = Color(0xFF323A2E), fontSize = 12.5.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** The line end or the line under the finger (ends first), or null. */
internal fun pick(line: DoubleArray, p: Offset, r: VideoRect, endReach: Float, lineReach: Float): Grab? {
    val fx = (p.x - r.x) / r.w
    val fy = (p.y - r.y) / r.h
    var best: Grab? = null
    var bestD = Float.MAX_VALUE
    for (end in 0..1) {
        val d = hypot((line[end * 2] - fx).toFloat() * r.w, (line[end * 2 + 1] - fy).toFloat() * r.h)
        if (d < endReach && d < bestD) {
            best = Grab(end, p)
            bestD = d
        }
    }
    if (best != null) return best
    val dx = (line[2] - line[0]).toFloat() * r.w
    val dy = (line[3] - line[1]).toFloat() * r.h
    val len2 = max(1f, dx * dx + dy * dy)
    val u = (((fx - line[0]).toFloat() * r.w) * dx + ((fy - line[1]).toFloat() * r.h) * dy) / len2
    val px = line[0] + u * (line[2] - line[0])
    val py = line[1] + u * (line[3] - line[1])
    val d = hypot((px - fx).toFloat() * r.w, (py - fy).toFloat() * r.h)
    return if (u > 0 && u < 1 && d < lineReach) Grab(-1, p) else null
}

/** The line with the grabbed end (or the whole line) moved to follow the finger at `p`, kept inside the picture. */
internal fun moved(start: DoubleArray, g: Grab, p: Offset, r: VideoRect): DoubleArray {
    val fx = ((p.x - r.x) / r.w).toDouble()
    val fy = ((p.y - r.y) / r.h).toDouble()
    if (g.end >= 0) {
        val l = start.copyOf()
        l[g.end * 2] = fx.coerceIn(0.0, 1.0)
        l[g.end * 2 + 1] = fy.coerceIn(0.0, 1.0)
        return l
    }
    val dx = fx - (g.from.x - r.x) / r.w
    val dy = fy - (g.from.y - r.y) / r.h
    val mx = max(-min(start[0], start[2]), min(dx, 1 - max(start[0], start[2])))
    val my = max(-min(start[1], start[3]), min(dy, 1 - max(start[1], start[3])))
    return doubleArrayOf(start[0] + mx, start[1] + my, start[2] + mx, start[3] + my)
}
