package io.github.ndev.flockeyes.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.core.report.CountInfo
import io.github.ndev.flockeyes.core.report.Csv
import io.github.ndev.flockeyes.core.report.SightingInfo
import io.github.ndev.flockeyes.data.Share
import io.github.ndev.flockeyes.herd.CowPicture
import io.github.ndev.flockeyes.scan.Chip
import io.github.ndev.flockeyes.scan.fieldColors
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.Card
import io.github.ndev.flockeyes.ui.Confirm
import io.github.ndev.flockeyes.ui.SectionTitle
import io.github.ndev.flockeyes.ui.cows
import io.github.ndev.flockeyes.ui.dayLabel
import io.github.ndev.flockeyes.ui.timeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** What a count is called in lists. */
fun countTitle(c: CountInfo): String = c.name.ifBlank { if (c.kind == "gate") "Gate" else "Field" }

/** "23 cows" for a field count, "30 crossings" for a gate count. */
fun countFigure(c: CountInfo): String = if (c.kind == "gate") "${c.count} crossing${if (c.count == 1) "" else "s"}" else cows(c.count)

/** The sightings of a count with each cow's name as it is now (a cow since deleted keeps its number). */
fun sightingInfos(app: App, id: Long): List<SightingInfo> {
    val rows = app.db.sightings(id)
    return synchronized(app.herd) {
        rows.map { s ->
            val cow = s.cow?.let { app.herd[it] }
            SightingInfo(s.t, s.cow, cow?.name ?: s.cow?.let { "Cow $it (deleted)" } ?: "", cow?.tag ?: "", s.dir, s.score, s.fresh)
        }
    }
}

/** The Counts tab: every saved count, newest first; each opens to who was counted. */
@Composable
fun HistoryScreen() {
    val app = App.instance
    val context = LocalContext.current
    val data by app.dataVersion.collectAsState()
    val open by app.openCount.collectAsState()
    var page by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    // A count still running isn't listed until it's saved.
    val running = remember(data) { app.field.ui.value.countId ?: app.gate.ui.value.countId }
    val counts by produceState(emptyList<CountInfo>(), data) {
        value = withContext(Dispatchers.IO) { runCatching { app.db.counts() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(open) {
        open?.let {
            page = it
            app.openCount.value = null
        }
    }
    val current = page?.let { id -> counts.firstOrNull { it.id == id } }
    if (current != null) {
        CountScreen(current, onClose = { page = null })
        return
    }
    val list = counts.filter { it.id != running }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Counts", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (list.isEmpty()) "Nothing counted yet" else "${list.size} saved", color = C.muted, fontSize = 13.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = C.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export every count (CSV)") },
                        enabled = list.isNotEmpty(),
                        onClick = {
                            menu = false
                            runCatching { Share.send(context, "flock-eyes-counts.csv", Csv.counts(list).toByteArray(), "text/csv", "Flock Eyes counts") }
                        },
                    )
                }
            }
        }
        if (list.isEmpty()) {
            Card(Modifier.padding(top = 16.dp)) {
                Text("Counts you save appear here", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Count a field in the Field tab, or cows passing a gap in the Gate tab. Each saved count lists who was counted, and can be shared as a spreadsheet file.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().testTag("count-${c.id}").clickable { page = c.id }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${c.count}", color = C.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = TNUM,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(countTitle(c), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "${dayLabel(c.started)} ${timeLabel(c.started)} · " + (if (c.kind == "gate") "gate: ${c.dir1} ${c.n1}, ${c.dir2} ${c.n2}" else "field" + (if (c.fresh > 0) ", ${c.fresh} new" else "")) +
                                (if (c.source == "video") " · from a video" else ""),
                            color = C.muted, fontSize = 12.5.sp, maxLines = 1,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = C.muted)
                }
            }
        }
    }
}

/** One count: its figures, who was counted, and (for a field count) which cows of the herd weren't. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountScreen(c: CountInfo, onClose: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val data by app.dataVersion.collectAsState()
    val debug = app.prefs.debug
    var confirm by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val sightings by produceState(emptyList<SightingInfo>(), c.id, data) {
        value = withContext(Dispatchers.IO) { runCatching { sightingInfos(app, c.id) }.getOrDefault(emptyList()) }
    }
    val missing = remember(sightings, data) {
        if (c.kind != "field") emptyList() else synchronized(app.herd) {
            val seen = sightings.mapNotNull { it.cowId }.toSet()
            // Only cows the app already knew when the count was made.
            app.herd.cows.filter { it.id !in seen && it.created <= c.ended }.map { it.id to it.label }
        }
    }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text(countTitle(c), color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { renaming = true }) { Text("Rename", color = C.accent) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Card {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${c.count}", color = C.accent, fontSize = 48.sp, fontWeight = FontWeight.Bold, style = TNUM, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(if (c.kind == "gate") (if (c.count == 1) "crossing" else "crossings") else (if (c.count == 1) "cow counted" else "cows counted"), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${dayLabel(c.started)} ${timeLabel(c.started)} to ${timeLabel(c.ended)}" + (if (c.source == "video") " · counted from a video" else ""),
                            color = C.muted, fontSize = 12.5.sp,
                        )
                    }
                }
                if (c.kind == "gate") {
                    Text("${c.dir1}: ${c.n1} · ${c.dir2}: ${c.n2}", color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                } else {
                    val named = sightings.count { it.cowId != null }
                    Text(
                        "$named known by their markings" + (if (c.unknown > 0) ", ${c.unknown} not recognised" else "") + " · most in view at once ${c.peak}" +
                            (if (c.fresh > 0) " · ${c.fresh} learnt as new" else ""),
                        color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                    if (c.count > named + c.unknown) {
                        Text(
                            "The others were counted by where they stood: too far off, hidden or too close together to tell apart by their markings.",
                            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (c.note.isNotBlank()) Text(c.note, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }

            SectionTitle(if (c.kind == "gate") "WENT THROUGH" else "COUNTED")
            Card {
                if (sightings.isEmpty()) Text("No cows were recognised in this count.", color = C.muted, fontSize = 13.sp)
                for (s in sightings) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.cowId != null) {
                            CowPicture(s.cowId!!, Modifier.size(width = 54.dp, height = 40.dp).clickable { app.openCow.value = s.cowId })
                        } else {
                            Box(Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(C.surface2))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                if (s.cowId == null) "Not recognised" else (s.tag.ifBlank { s.cow }) + (if (s.fresh) " · new" else ""),
                                color = if (s.cowId == null) C.muted else C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                            Text(
                                timeLabel(s.t) + (if (s.dir == 1) " · ${c.dir1}" else if (s.dir == 2) " · ${c.dir2}" else "") +
                                    (if (debug && s.cowId != null && !s.fresh) " · %.2f".format(s.score) else ""),
                                color = C.muted, fontSize = 12.5.sp, maxLines = 1,
                            )
                        }
                    }
                }
            }

            if (missing.isNotEmpty()) {
                SectionTitle("NOT SEEN (${missing.size})")
                Card {
                    Text("Cows in the herd that weren’t recognised in this count.", color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in missing.take(120)) Chip(label) { app.openCow.value = id }
                    }
                }
            }

            Row(Modifier.padding(top = 18.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    val name = "flock-eyes-${countTitle(c).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${Csv.time(c.started).take(10)}.csv"
                    runCatching { Share.send(context, name, Csv.sightings(c, sightings).toByteArray(), "text/csv", "${countTitle(c)}: ${countFigure(c)}") }
                        .onFailure { Toast.makeText(context, "Couldn’t share: ${it.message}", Toast.LENGTH_LONG).show() }
                }) { Text("Share (CSV)") }
                OutlinedButton(onClick = { confirm = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) { Text("Delete") }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(c.name) }
        var note by remember { mutableStateOf(c.note) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = C.surface,
            title = { Text("Name and note", color = C.text) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.db.renameCount(c.id, name.trim(), note.trim())
                    app.dataChanged()
                    renaming = false
                }) { Text("Save", color = C.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = C.text) } },
        )
    }
    if (confirm) {
        Confirm("Delete this count?", "The cows learnt during it stay in the herd. This can’t be undone.", "Delete", onDismiss = { confirm = false }) {
            app.db.deleteCount(c.id)
            app.dataChanged()
            confirm = false
            onClose()
        }
    }
}
