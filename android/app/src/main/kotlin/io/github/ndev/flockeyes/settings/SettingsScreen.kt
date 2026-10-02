package io.github.ndev.flockeyes.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.BuildConfig
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.ai.SpeedResult
import io.github.ndev.flockeyes.ai.SpeedTest
import io.github.ndev.flockeyes.data.Backup
import io.github.ndev.flockeyes.data.Updates
import io.github.ndev.flockeyes.debug.DebugLog
import io.github.ndev.flockeyes.debug.DebugScreen
import io.github.ndev.flockeyes.debug.copy
import io.github.ndev.flockeyes.debug.diagnostics
import io.github.ndev.flockeyes.service.BackgroundService
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.Card
import io.github.ndev.flockeyes.ui.Confirm
import io.github.ndev.flockeyes.ui.SectionTitle
import io.github.ndev.flockeyes.ui.Segmented
import io.github.ndev.flockeyes.ui.SettingRow
import io.github.ndev.flockeyes.ui.SwitchRow
import io.github.ndev.flockeyes.ui.cows
import io.github.ndev.flockeyes.ui.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

private val STRICT_TEXT = mapOf(
    "loose" to "Readier to say it’s a cow it knows: fewer cows learnt twice, more mix-ups between cows that look alike.",
    "normal" to "A cow is named when it stands out from every other cow in the herd by the gap measured for the recognition model.",
    "strict" to "Surer before it says it’s a cow it knows: fewer mix-ups, more cows learnt twice.",
)

@Composable
fun SettingsScreen() {
    val app = App.instance
    val p = app.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<Backup.Checked?>(null) }
    val update by app.updates.available.collectAsState()
    val checking by app.updates.checking.collectAsState()
    val logVersion by DebugLog.version.collectAsState()
    val data by app.dataVersion.collectAsState()
    val fieldUi by app.field.ui.collectAsState()
    val gateUi by app.gate.ui.collectAsState()
    val counting = fieldUi.running || gateUi.running

    val backupTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Backing up…"
        scope.launch {
            val r = withContext(app.ioDispatcher) { runCatching { Backup.openOut(app, uri).use { Backup.write(app, it) } } }
            working = null
            Toast.makeText(context, r.fold({ "Backed up: ${it.text()}" }, { "Couldn’t back up: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Reading the backup…"
        scope.launch {
            val r = withContext(app.ioDispatcher) { runCatching { Backup.openIn(app, uri).use { Backup.check(app, it) } } }
            working = null
            r.onSuccess { restoring = it }.onFailure { Toast.makeText(context, it.message ?: "That file can’t be restored", Toast.LENGTH_LONG).show() }
        }
    }
    val picturesTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Saving the pictures…"
        scope.launch {
            val r = withContext(app.ioDispatcher) { runCatching { Backup.openOut(app, uri).use { app.store.exportPictures(it) } } }
            working = null
            Toast.makeText(context, r.fold({ "Saved $it pictures" }, { "Couldn’t save: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    if (page == "log") {
        DebugScreen(onClose = { page = "" })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        update?.let { u ->
            Card(Modifier.padding(top = 12.dp)) {
                Text("Flock Eyes ${u.version} is out", color = C.sky, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "You have ${BuildConfig.VERSION_NAME}. Download it, then open the file to install it over this one: the herd, counts and settings stay.",
                    color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open(context, u.apk) }, colors = ButtonDefaults.buttonColors(containerColor = C.sky, contentColor = C.bg)) {
                        Text("Download", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { open(context, u.page) }) { Text("What’s new", color = C.sky) }
                }
            }
        }

        SectionTitle("RECOGNISING COWS")
        Card {
            SettingRow("How sure before it says it’s a cow it knows", STRICT_TEXT[p.strictness]) {
                Segmented(listOf("loose" to "Looser", "normal" to "Normal", "strict" to "Stricter"), p.strictness) {
                    p.strictness = it
                    p.gapOverride = 0.0
                }
            }
            SwitchRow(
                "Learn cows it doesn’t know",
                "On: a cow it gets a good look at and doesn’t recognise joins the herd. Off (once your herd is learnt): strangers are counted as not recognised.",
                checked = p.learn,
            ) { p.learn = it }
            Text(
                "It goes by the markings on a cow’s side, so it needs the cow side-on, big enough in the picture and standing clear of the others. " +
                    "A cow’s two sides are marked differently: one learnt from the left looks new from the right until the app has watched it turn round, " +
                    "or the two are merged in the Herd tab. Cows with plain coats can’t be told apart this way.",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        SectionTitle("FINDING COWS")
        Card {
            SettingRow("Cow finder", "Standard is more accurate; Light is quicker on slower phones. Auto uses Standard and moves to Light if the phone can’t keep up.") {
                Segmented(listOf("auto" to "Auto", "tiny" to "Standard", "nano" to "Light"), p.finderModel) { p.finderModel = it }
            }
            SettingRow("Sensitivity", "Higher picks up smaller and further cows, with more false alarms.") {
                Segmented(listOf("low" to "Low", "medium" to "Medium", "high" to "High"), p.sensitivity) { p.sensitivity = it }
            }
            SwitchRow(
                "Far away: look at the picture in two halves",
                "For field counts. Distant cows show bigger to the cow finder, at twice the work each frame.",
                checked = p.far,
            ) { p.far = it }
            SwitchRow(
                "Count what looks like a horse or sheep",
                "From some angles the finder takes a cow for one. Turn off if horses or sheep share the field.",
                checked = p.lookalikes,
            ) { p.lookalikes = it }
            SwitchRow("Vibrate when a cow is named or counted", checked = p.haptics) { p.haptics = it }
        }

        SectionTitle("GATE COUNTS")
        Card {
            SettingRow("Dim the screen while counting", "Saves battery and heat. Tap to wake.") {
                Segmented(listOf("0" to "Never", "60" to "After 1 min", "300" to "After 5 min"), p.dimAfter.toString()) { p.dimAfter = it.toInt() }
            }
            SwitchRow(
                "Keep counting with the screen off",
                "A gate count carries on if the screen goes off or you open another app. A notification shows meanwhile, with a Stop button.",
                checked = p.backgroundCounting,
            ) { p.backgroundCounting = it }
        }

        SectionTitle("SCREEN")
        Card {
            SettingRow("Which way round", "The Gate tab keeps to the way round its line was set up.") {
                Segmented(listOf("auto" to "Turn with phone", "portrait" to "Portrait", "landscape" to "Landscape"), p.orientation) { p.orientation = it }
            }
        }

        SectionTitle("AI ENGINE")
        EngineSettings()

        SectionTitle("PRIVACY & DATA")
        Card {
            val herdSize = remember(data) { app.herd.size }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirm = "counts" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) {
                    Text("Delete all counts")
                }
                OutlinedButton(onClick = { confirm = "herd" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) {
                    Text("Delete the herd")
                }
            }
            Text(
                "The camera picture is analysed on this phone and thrown away. Flock Eyes keeps only the herd (${cows(herdSize)} now, each with a few small pictures of its side) " +
                    "and your counts. No video. Nothing leaves the phone unless you share it or back it up (the only thing Flock Eyes asks the internet is whether there’s an update, if you allow it below).",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        SectionTitle("BACKUP")
        Card {
            Text("One file with the herd (and its pictures), your counts and settings. For a new phone, or to keep a copy.", color = C.muted, fontSize = 12.5.sp)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupTo.launch(Backup.fileName()) }, enabled = working == null, modifier = Modifier.weight(1f)) { Text("Back up") }
                OutlinedButton(
                    onClick = {
                        if (counting || BackgroundService.running.value) {
                            Toast.makeText(context, "Stop counting first", Toast.LENGTH_SHORT).show()
                        } else {
                            restoreFrom.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                        }
                    },
                    enabled = working == null,
                    modifier = Modifier.weight(1f),
                ) { Text("Restore") }
            }
            working?.let { Text(it, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        SectionTitle("DEVELOPER")
        Card {
            SwitchRow(
                "Debug mode",
                "On the Field and Gate screens: what the cow finder sees in each frame, each cow’s track, looks and two best matches with their scores, and how long each step takes. Scores also show in the Herd and Counts tabs.",
                checked = p.debug,
            ) {
                p.debug = it
                DebugLog.add("app", "Debug mode ${if (it) "on" else "off"}")
            }
            val lines = remember(logVersion) { DebugLog.entries().size }
            NavRow("Debug log", "$lines line${if (lines == 1) "" else "s"}: what the app did, for a bug report.") { page = "log" }
            TextButton(onClick = { copy(context, "Flock Eyes diagnostics", diagnostics(context)) }) { Text("Copy diagnostics", color = C.accent) }
            if (p.debug) {
                HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 8.dp))
                val e = app.engine
                val bar = e.bars()
                val now = bar.margin
                Text("“Same cow” gap: %.2f".format(now), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TNUM)
                Text(
                    "A cow is named when it looks at least %.2f like a cow of the herd and this much more like it than like the next. The model’s measured gap is %.2f. A cow less than %.2f like every cow is new."
                        .format(bar.match, e.reid.margin, bar.fresh),
                    color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp),
                )
                Slider(
                    value = now.toFloat(), onValueChange = { p.gapOverride = Math.round(it * 100) / 100.0 }, valueRange = 0.02f..0.4f,
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.surface2),
                )
                Row {
                    TextButton(onClick = { p.gapOverride = 0.0 }, enabled = p.gapOverride > 0) { Text("Use the model’s", color = if (p.gapOverride > 0) C.accent else C.muted) }
                    TextButton(onClick = { picturesTo.launch("Flock Eyes herd pictures.zip") }, enabled = working == null) { Text("Export the herd’s pictures", color = C.accent) }
                }
            }
        }

        SectionTitle("ABOUT")
        Card {
            Text("Flock Eyes ${BuildConfig.VERSION_NAME}", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Build ${BuildConfig.VERSION_CODE}. Counts dairy cows and recognises each one again, on this phone, with ONNX Runtime.",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            val reid = app.engine.reid
            Text(
                "Cow finder: YOLOX (Megvii, Apache 2.0). Recognition: ${reid.name} (WildlifeDatasets, ${reid.licence.ifBlank { "CC BY-NC 4.0" }}), " +
                    "which means this app is for your own use and can’t be sold. Runtime: ONNX Runtime (MIT). ${reid.notes}",
                color = C.muted, fontSize = 12.5.sp,
            )
            SwitchRow(
                "Check for updates",
                "Once a day, asks GitHub whether there’s a newer Flock Eyes. Nothing else is sent.",
                checked = p.updateCheck,
            ) {
                p.updateCheck = it
                if (it) app.updates.checkIfDue()
            }
            Row(Modifier.padding(top = 2.dp)) {
                TextButton(
                    onClick = {
                        val main = ContextCompat.getMainExecutor(context)
                        app.updates.check { r ->
                            main.execute {
                                val msg = r.fold({ rel -> if (rel != null) "Flock Eyes ${rel.version} is out" else "You have the latest version" }, { "Couldn’t check: ${it.message}" })
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !checking,
                ) { Text(if (checking) "Checking…" else "Check now", color = C.accent) }
                TextButton(onClick = { open(context, Updates.PAGE) }) { Text("All releases", color = C.accent) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (confirm) {
        "counts" -> Confirm("Delete every count?", "The herd stays. This can’t be undone.", "Delete all", onDismiss = { confirm = null }) {
            if (counting) {
                Toast.makeText(context, "Stop counting first", Toast.LENGTH_SHORT).show()
            } else {
                app.io.execute {
                    runCatching { app.db.clearCounts() }
                    app.dataChanged()
                }
                Toast.makeText(context, "All counts deleted", Toast.LENGTH_SHORT).show()
            }
            confirm = null
        }
        "herd" -> Confirm("Delete the whole herd?", "Every cow and its pictures go, and each will have to be learnt again. Counts keep their numbers. This can’t be undone.", "Delete the herd", onDismiss = { confirm = null }) {
            if (counting) {
                Toast.makeText(context, "Stop counting first", Toast.LENGTH_SHORT).show()
            } else {
                app.io.execute {
                    runCatching {
                        app.field.scan.reset()
                        app.gate.scan.reset()
                        synchronized(app.herd) { app.db.clearHerd() }
                        app.store.load()
                    }
                }
                Toast.makeText(context, "Herd deleted", Toast.LENGTH_SHORT).show()
            }
            confirm = null
        }
    }
    restoring?.let { b ->
        Confirm(
            "Restore this backup?",
            "From Flock Eyes ${b.summary.version}${if (b.summary.created > 0) ", " + dayLabel(b.summary.created) else ""}: ${b.summary.text()}. " +
                "It replaces the herd, counts and settings on this phone.",
            "Restore",
            onDismiss = {
                b.db.delete()
                restoring = null
            },
        ) {
            restoring = null
            working = "Restoring…"
            scope.launch {
                val r = withContext(app.ioDispatcher) { runCatching { Backup.restore(app, b) } }
                working = null
                Toast.makeText(context, r.fold({ "Restored: ${b.summary.text()}" }, { "Couldn’t restore: ${it.message}" }), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

/** Where the models run (CPU, XNNPACK or NNAPI, and how many threads), and a speed test that finds the fastest. */
@Composable
private fun EngineSettings() {
    val app = App.instance
    val p = app.prefs
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<SpeedTest?>(null) }
    var progress by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpeedResult>?>(null) }
    DisposableEffect(Unit) { onDispose { running?.cancelled = true } }

    Card {
        SettingRow(
            "Accelerator",
            when (p.accel) {
                "auto" -> if (p.tuned.isEmpty()) "Auto uses the CPU until you run the speed test below, then the fastest setup it found for each model." else "Auto uses the fastest setup the speed test found for each model."
                "CPU" -> "ONNX Runtime’s own CPU code."
                "XNNPACK" -> "XNNPACK: CPU code tuned for phone processors. Often the fastest."
                else -> "NNAPI: Android’s route to the phone’s AI chip or graphics. Fast on some phones, slow or unavailable on others."
            },
        ) {
            Segmented(listOf("auto" to "Auto", "CPU" to "CPU", "XNNPACK" to "XNNPACK", "NNAPI" to "NNAPI"), p.accel) { p.accel = it }
        }
        val threadOptions = listOf(0, 2, 4, 6, 8).filter { it == 0 || it <= engine.cores }
        SettingRow("CPU threads", "This phone has ${engine.cores} cores. More threads are faster up to a point, then just warmer.") {
            Segmented(threadOptions.map { it.toString() to if (it == 0) "Auto" else "$it" }, p.threads.toString()) { p.threads = it.toInt() }
        }
        Text("Running now", color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        for (m in Model.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(m.label, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(engine.configFor(m).label, color = C.text, fontSize = 12.5.sp)
            }
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 12.dp))
        Text("Speed test", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Tries every setup on a sample photo, checks the answers are still right, and times it. Takes a minute or two; keep the app open.",
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val test = running
        if (test != null) {
            LinearProgressIndicator(color = C.accent, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
            Text(progress, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { test.cancelled = true }) { Text("Stop", color = C.red) }
        } else {
            Button(
                onClick = {
                    val t = SpeedTest(app)
                    running = t
                    results = null
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            runCatching { t.run(Model.entries) { msg -> progress = msg } }.getOrElse { e ->
                                progress = "The test failed: ${e.message}"
                                emptyList()
                            }
                        }
                        results = r
                        running = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
            ) { Text("Test this phone’s speed", fontWeight = FontWeight.Bold) }
        }
        results?.let { r ->
            if (r.isEmpty()) {
                Text(progress.ifEmpty { "Stopped." }, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                SpeedResults(r)
                Button(
                    onClick = {
                        SpeedTest(app).useFastest(r)
                        Toast.makeText(context, "Using the fastest setup for each model", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                ) { Text("Use the fastest", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SpeedResults(results: List<SpeedResult>) {
    for ((m, rows) in results.groupBy { it.model }) {
        val best = rows.filter { it.ok && it.ms != null }.minByOrNull { it.ms!! }
        Text(m.label, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        for (r in rows) {
            val isBest = r === best
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.cfg.label, color = if (isBest) C.accent else C.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(
                    when {
                        r.ms == null -> r.note ?: "failed"
                        !r.ok -> "%.0f ms · %s".format(r.ms, r.note ?: "wrong results")
                        else -> "%.0f ms".format(r.ms)
                    },
                    color = if (isBest) C.accent else if (r.ok) C.text else C.amber,
                    fontSize = 12.5.sp, style = TNUM, textAlign = TextAlign.End,
                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A row that opens another page. */
@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = C.muted)
    }
}
