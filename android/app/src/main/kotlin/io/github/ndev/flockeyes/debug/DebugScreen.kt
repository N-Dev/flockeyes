package io.github.ndev.flockeyes.debug

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.BuildConfig
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.data.Share
import io.github.ndev.flockeyes.service.BackgroundService
import io.github.ndev.flockeyes.core.herd.HerdCheck
import io.github.ndev.flockeyes.ui.C
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val ERROR = Regex("(?i)fail|couldn|error|exception|didn't|didn’t")

/** The debug log: what the app did, newest first, with filters, and ways to send it for a bug report. */
@Composable
fun DebugScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val version by DebugLog.version.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    val entries = remember(version) { DebugLog.entries() }
    val cats = remember(entries) { entries.map { it.cat }.distinct().sorted() }
    val shown = remember(entries, filter) {
        entries.asReversed().filter {
            when (filter) {
                "all" -> true
                "problems" -> ERROR.containsMatchIn(it.msg)
                else -> it.cat == filter
            }
        }
    }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text("Debug log", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                copy(context, "Flock Eyes diagnostics", diagnostics(context))
            }) { Text("Copy", color = C.accent) }
            TextButton(onClick = {
                runCatching { Share.send(context, "flockeyes-diagnostics.txt", diagnostics(context).toByteArray(), "text/plain", "Flock Eyes diagnostics") }
            }) { Text("Share", color = C.accent) }
            TextButton(onClick = { DebugLog.clear() }) { Text("Clear", color = C.muted) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("all", "problems") + cats) { c ->
                val on = c == filter
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) C.text else C.surface2).clickable { filter = c }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        when (c) {
                            "all" -> "All"
                            "problems" -> "Problems"
                            else -> c.replaceFirstChar { it.uppercase() }
                        },
                        color = if (on) C.bg else C.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Text(
            "Kept in memory only (the last 1,000 lines). Copy or Share sends it with the phone’s details, for a bug report. No pictures are in it, only cows’ names and match scores.",
            color = C.muted, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (shown.isEmpty()) {
            Text("Nothing yet.", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(shown) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(DebugLog.time(e.t).substring(0, 8), color = C.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        "[${e.cat}] ${e.msg}",
                        color = if (ERROR.containsMatchIn(e.msg)) C.red else C.text,
                        fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

fun copy(context: Context, label: String, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/** Everything useful for a bug report: the app, the phone, how the AI is running, the settings and the log. */
fun diagnostics(context: Context): String {
    val app = App.instance
    val e = app.engine
    val am = context.getSystemService(ActivityManager::class.java)
    val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    return buildString {
        appendLine("Flock Eyes ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""})")
        appendLine("Report made ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val soc = if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        appendLine("Processor: ${e.cores} cores, ${Build.SUPPORTED_ABIS.joinToString()}$soc")
        appendLine("Memory: ${mem.totalMem / 1_048_576} MB, ${mem.availMem / 1_048_576} MB free${if (mem.lowMemory) " (low)" else ""}")
        appendLine("Now: ${runCatching { readHealth(context).text() }.getOrDefault("?")}")
        appendLine()
        val p = app.prefs
        appendLine("AI engine: accelerator ${p.accel}, threads ${if (p.threads == 0) "auto (${e.defaultThreads})" else p.threads}")
        appendLine("Speed test picks: ${p.tuned.ifEmpty { "none yet" }}")
        for (m in Model.entries) appendLine("  ${m.label}: ${e.configFor(m).label}${if (e.isLoaded(m)) " · loaded" else ""}")
        e.notice.value?.let { appendLine("Notice: $it") }
        appendLine()
        val cam = app.camera
        val z = cam.zoom.value
        appendLine("Camera: ${"%.1f".format(cam.cameraFps.value)} fps from the camera; zoom ${z?.let { "%.1f× (%.1f× to %.1f×)".format(it.ratio, it.min, it.max) } ?: "?"}; background: ${if (BackgroundService.running.value) "gate count" else "off"}")
        for ((name, sc) in listOf("Field" to app.field, "Gate" to app.gate)) {
            val u = sc.ui.value
            appendLine(
                "$name: ${if (u.ready) "%.1f fps, finder %.0f ms (%s, %d area%s), picture %.0f ms, look %.0f ms (%d waiting, %d taken), frame %d×%d, %d frames, %s".format(
                    u.fps, u.msFind, u.model, u.tiles, if (u.tiles == 1) "" else "s", u.msPrep, u.msLook, u.waiting, u.looks, u.frameW, u.frameH, u.frames,
                    if (u.running) "counting: ${u.count}" else "not counting",
                ) else "not started"}",
            )
        }
        appendLine()
        val rc = e.reid
        val bar = e.bars()
        appendLine(
            "Recognition: ${rc.name} (${rc.key}), ${rc.dim} numbers a look; " +
                (app.herd.tuning?.let { "tuning ${rc.tuning} (${it.basis.size} directions, from ${it.looks} looks)" } ?: "no tuning") +
                "; same cow at %.2f and %.2f clear of the next (how sure: ${p.strictness}${if (p.gapOverride > 0) ", gap set by hand" else ""}), new below %.2f; learning ${if (p.learn) "on" else "off"}".format(bar.match, bar.margin, bar.fresh),
        )
        val herd = app.herd
        synchronized(herd) {
            val views = herd.cows.sumOf { it.views.size }
            appendLine("Herd: ${herd.size} cows, $views looks${if (app.store.stale > 0) ", ${app.store.stale} looks waiting to be redone" else ""}")
            if (herd.size in 2..400) {
                val nn = HerdCheck.nearestScores(herd)
                if (nn.isNotEmpty()) {
                    appendLine("  nearest other cow, by cow: lowest %.2f, middle %.2f, highest %.2f".format(nn.first(), nn[nn.size / 2], nn.last()))
                    appendLine("  cows with another cow alike enough to be the same one (%.2f): ${nn.count { it >= bar.twin }}".format(bar.twin))
                }
            }
        }
        appendLine()
        appendLine("Settings:")
        for ((k, v) in p.export().toSortedMap()) appendLine("  $k = ${(v as? Map<*, *>)?.get("v")}")
        appendLine()
        appendLine("Log:")
        append(DebugLog.text())
    }
}
