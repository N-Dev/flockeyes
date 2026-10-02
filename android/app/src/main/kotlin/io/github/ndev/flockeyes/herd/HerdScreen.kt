package io.github.ndev.flockeyes.herd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.core.herd.HerdCheck
import io.github.ndev.flockeyes.core.report.CowInfo
import io.github.ndev.flockeyes.core.report.Csv
import io.github.ndev.flockeyes.data.Share
import io.github.ndev.flockeyes.data.ViewRow
import io.github.ndev.flockeyes.scan.fieldColors
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.Card
import io.github.ndev.flockeyes.ui.Confirm
import io.github.ndev.flockeyes.ui.SectionTitle
import io.github.ndev.flockeyes.ui.Segmented
import io.github.ndev.flockeyes.ui.cows
import io.github.ndev.flockeyes.ui.dayLabel
import io.github.ndev.flockeyes.ui.whenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A cow in the list. */
private class Item(val id: Int, val label: String, val name: String, val tag: String, val lastSeen: Long, val seen: Int, val views: Int)

/** Two cows that look alike, by number. */
private class Double2(val a: Int, val b: Int, val labelA: String, val labelB: String, val score: Double)

private fun herdItems(app: App): List<Item> = synchronized(app.herd) {
    app.herd.cows.map { Item(it.id, it.label, it.name, it.tag, it.lastSeen, it.seen, it.views.size) }
}

/** A cow's picture (or a plain square while there isn't one). */
@Composable
fun CowPicture(id: Int, modifier: Modifier = Modifier) {
    val app = App.instance
    val data by app.dataVersion.collectAsState()
    val bmp by produceState<ImageBitmap?>(null, id, data) {
        value = withContext(Dispatchers.IO) { runCatching { app.store.cowPicture(id)?.asImageBitmap() }.getOrNull() }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(C.surface2))
    }
}

/** The Herd tab: every cow the app knows, with its looks; rename, merge doubles, delete. */
@Composable
fun HerdScreen() {
    val app = App.instance
    val context = LocalContext.current
    val data by app.dataVersion.collectAsState()
    val open by app.openCow.collectAsState()
    var page by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("number") }
    var menu by remember { mutableStateOf(false) }
    val list = remember(data) { herdItems(app) }

    LaunchedEffect(open) {
        open?.let {
            page = "cow:$it"
            app.openCow.value = null
        }
    }

    if (page.startsWith("cow:")) {
        val id = page.removePrefix("cow:").toIntOrNull()
        if (id != null && list.any { it.id == id }) {
            CowScreen(id, onClose = { page = "" }, onOpen = { page = "cow:$it" })
            return
        }
    }
    if (page == "doubles") {
        DoublesScreen(onClose = { page = "" }, onOpen = { page = "cow:$it" })
        return
    }

    val match = remember(data) { app.engine.bars().twin }
    // Cows that look alike enough to be one cow learnt twice (worked out off the main thread).
    val doubles by produceState(0, data, match) {
        value = withContext(Dispatchers.Default) { synchronized(app.herd) { HerdCheck.duplicates(app.herd, match).size } }
    }
    val shown = remember(list, query, sort) {
        val q = query.trim().lowercase()
        val f = if (q.isEmpty()) list else list.filter { q in it.name.lowercase() || q in it.tag.lowercase() || q == it.id.toString() }
        when (sort) {
            "seen" -> f.sortedByDescending { it.lastSeen }
            "name" -> f.sortedBy { it.label.lowercase() }
            else -> f.sortedBy { it.id }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Herd", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (list.isEmpty()) "No cows learnt yet" else "${cows(list.size)} known", color = C.muted, fontSize = 13.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = C.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export the herd (CSV)") },
                        enabled = list.isNotEmpty(),
                        onClick = {
                            menu = false
                            val rows = synchronized(app.herd) { app.herd.cows.map { CowInfo(it.id, it.name, it.tag, it.note, it.created, it.lastSeen, it.seen, it.views.size) } }
                            runCatching { Share.send(context, "flock-eyes-herd.csv", Csv.herd(rows).toByteArray(), "text/csv", "Flock Eyes herd") }
                        },
                    )
                }
            }
        }
        if (list.isEmpty()) {
            Card(Modifier.padding(top = 16.dp)) {
                Text("Cows appear here as they’re learnt", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Start a count in the Field or Gate tab. Each cow the app gets a good look at (side-on, standing clear of the others) is learnt: " +
                        "a few small pictures of its markings are kept, and it’s called Cow 1, Cow 2 and so on until you give it a name or its tag number.\n\n" +
                        "A cow’s two sides are marked differently, so one learnt from the left can be learnt again from the right. " +
                        "When that happens, open either one here and merge them.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (list.isNotEmpty() && doubles > 0) {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
                    .clickable { page = "doubles" }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (doubles == 1) "1 pair looks like the same cow" else "$doubles pairs look like the same cow", color = C.amber, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Check them and merge the ones that are.", color = C.muted, fontSize = 12.5.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = C.muted)
            }
        }
        if (list.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                placeholder = { Text("Search by name, tag or number") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = fieldColors(),
            )
            Segmented(listOf("number" to "By number", "seen" to "Last seen", "name" to "By name"), sort, Modifier.padding(top = 10.dp)) { sort = it }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().testTag("cow-${c.id}").clickable { page = "cow:${c.id}" }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CowPicture(c.id, Modifier.size(width = 76.dp, height = 56.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(c.label, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            (if (c.tag.isNotBlank() && c.name != "Cow ${c.id}") "${c.name} · " else "") +
                                "last seen ${if (c.seen == 0) "never" else dayLabel(c.lastSeen).lowercase()} · ${c.seen} count${if (c.seen == 1) "" else "s"} · ${c.views} look${if (c.views == 1) "" else "s"}",
                            color = C.muted, fontSize = 12.5.sp, maxLines = 1,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = C.muted)
                }
            }
        }
    }
}

/** One cow: its picture, name and tag, the looks kept of it, the cows most like it, and where it was counted. */
@Composable
private fun CowScreen(id: Int, onClose: () -> Unit, onOpen: (Int) -> Unit) {
    val app = App.instance
    val data by app.dataVersion.collectAsState()
    val debug = app.prefs.debug
    val cow = remember(data, id) { synchronized(app.herd) { app.herd[id]?.let { Item(it.id, it.label, it.name, it.tag, it.lastSeen, it.seen, it.views.size) to (it.note to it.created) } } }
    if (cow == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val item = cow.first
    val note0 = cow.second.first
    val created = cow.second.second
    var name by rememberSaveable(id) { mutableStateOf(item.name) }
    var tag by rememberSaveable(id) { mutableStateOf(item.tag) }
    var note by rememberSaveable(id) { mutableStateOf(note0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mergeWith by remember { mutableStateOf<Int?>(null) }
    var look by remember { mutableStateOf<ViewRow?>(null) }
    val changed = name.trim() != item.name || tag.trim() != item.tag || note.trim() != note0
    val views by produceState(emptyList<ViewRow>(), id, data) {
        value = withContext(Dispatchers.IO) { runCatching { app.db.viewsOf(id) }.getOrDefault(emptyList()) }
    }
    val alike by produceState(emptyList<Double2>(), id, data) {
        value = withContext(Dispatchers.Default) {
            synchronized(app.herd) {
                val c = app.herd[id]
                if (c == null) emptyList() else HerdCheck.nearest(app.herd, c, 4).map { Double2(c.id, it.b.id, c.label, it.b.label, it.score) }
            }
        }
    }
    val counts by produceState(emptyList<Pair<String, Long>>(), id, data) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val all = app.db.counts().associateBy { it.id }
                app.db.sightingsOf(id, 12).mapNotNull { (cid, t, dir) ->
                    all[cid]?.let { c -> (c.name.ifBlank { if (c.kind == "gate") "Gate" else "Field" } + (if (dir == 1) " · ${c.dir1}" else if (dir == 2) " · ${c.dir2}" else "")) to t }
                }
            }.getOrDefault(emptyList())
        }
    }
    val match = remember(data) { app.engine.bars().twin }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text(item.label, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            if (changed) {
                TextButton(onClick = {
                    synchronized(app.herd) { app.herd[id]?.let { app.herd.rename(it, name, tag, note) } }
                }) { Text("Save", color = C.accent, fontWeight = FontWeight.Bold) }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            CowPicture(id, Modifier.fillMaxWidth().aspectRatio(1.6f))
            Text(
                "Cow number $id · learnt ${dayLabel(created).lowercase()} · last seen ${if (item.seen == 0) "never" else whenLabel(item.lastSeen).lowercase()} · in ${item.seen} count${if (item.seen == 1) "" else "s"}",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp),
            )
            SectionTitle("NAME AND TAG")
            Card {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().testTag("cow-name"), colors = fieldColors())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(tag, { tag = it.take(24) }, singleLine = true, label = { Text("Tag or freeze-brand number") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Text("With a tag number, the cow is shown by it. Tap Save at the top after changing these.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }

            SectionTitle("LOOKS KEPT (${views.size})")
            Card {
                Text(
                    "The pictures it’s recognised from, up to ${app.herd.maxViews}. If one is of another cow, tap it and remove it.",
                    color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(views, key = { it.id }) { v ->
                        ViewPicture(v, Modifier.height(84.dp).width((84 / v.aspect.coerceIn(0.5, 1.4)).dp).clickable { look = v })
                    }
                }
            }

            SectionTitle("MOST LIKE IT IN THE HERD")
            Card {
                if (alike.isEmpty()) Text("No other cows yet.", color = C.muted, fontSize = 13.sp)
                for (a in alike) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        CowPicture(a.b, Modifier.size(width = 64.dp, height = 46.dp).clickable { onOpen(a.b) })
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(a.labelB, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                (if (a.score >= match) "Looks like the same cow" else "Looks different") + (if (debug) " · %.2f".format(a.score) else ""),
                                color = if (a.score >= match) C.amber else C.muted, fontSize = 12.5.sp,
                            )
                        }
                        TextButton(onClick = { mergeWith = a.b }) { Text("Merge", color = C.accent) }
                    }
                }
                Text(
                    "Merge when two entries are one cow (learnt from each side, say). Its looks and counts are combined under this entry.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (counts.isNotEmpty()) {
                SectionTitle("COUNTED IN")
                Card {
                    for ((what, t) in counts) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(what, color = C.text, fontSize = 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(whenLabel(t), color = C.muted, fontSize = 12.5.sp)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 18.dp, bottom = 28.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red),
            ) { Text("Delete this cow") }
        }
    }

    look?.let { v ->
        AlertDialog(
            onDismissRequest = { look = null },
            containerColor = C.surface,
            title = { Text("A look of ${item.label}", color = C.text) },
            text = {
                Column {
                    ViewPicture(v, Modifier.fillMaxWidth().aspectRatio((1 / v.aspect.coerceIn(0.4, 2.0)).toFloat()))
                    Text("Kept ${whenLabel(v.added).lowercase()}" + (if (debug) " · quality %.2f".format(v.quality) else ""), color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        app.store.usePicture(id, v.id, v.aspect)
                        look = null
                    }) { Text("Use as its picture", color = C.accent) }
                    TextButton(onClick = {
                        synchronized(app.herd) { app.herd[id]?.let { app.herd.removeView(it, v.id) } }
                        look = null
                    }) { Text("Remove this look", color = C.red) }
                    TextButton(onClick = { look = null }) { Text("Close", color = C.text) }
                }
            },
        )
    }
    mergeWith?.let { other ->
        val otherLabel = alike.firstOrNull { it.b == other }?.labelB ?: "the other cow"
        Confirm("Merge $otherLabel into ${item.label}?", "They become one cow: ${item.label} keeps both sets of looks and counts. This can’t be undone.", "Merge", onDismiss = { mergeWith = null }) {
            synchronized(app.herd) {
                val a = app.herd[id]
                val b = app.herd[other]
                if (a != null && b != null) app.herd.merge(a, b)
            }
            mergeWith = null
        }
    }
    if (confirmDelete) {
        Confirm("Delete ${item.label}?", "Its looks go. Counts it was in keep their numbers. If it’s seen again it will be learnt as a new cow.", "Delete", onDismiss = { confirmDelete = false }) {
            synchronized(app.herd) { app.herd.remove(id) }
            confirmDelete = false
            onClose()
        }
    }
}

/** One look's picture. */
@Composable
private fun ViewPicture(v: ViewRow, modifier: Modifier = Modifier) {
    val app = App.instance
    val bmp by produceState<ImageBitmap?>(null, v.id) {
        value = withContext(Dispatchers.IO) { runCatching { app.store.viewPicture(v.id, v.aspect)?.asImageBitmap() }.getOrNull() }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(C.surface2))
    }
}

/** Pairs of cows that look alike enough to be one cow learnt twice: merge them, or say they're different. */
@Composable
private fun DoublesScreen(onClose: () -> Unit, onOpen: (Int) -> Unit) {
    val app = App.instance
    val data by app.dataVersion.collectAsState()
    val debug = app.prefs.debug
    val match = remember(data) { app.engine.bars().twin }
    var different by remember { mutableStateOf(listOf<String>()) }
    var merge by remember { mutableStateOf<Double2?>(null) }
    val pairs by produceState<List<Double2>?>(null, data, match) {
        value = withContext(Dispatchers.Default) {
            synchronized(app.herd) { HerdCheck.duplicates(app.herd, match).map { Double2(it.a.id, it.b.id, it.a.label, it.b.label, it.score) } }
        }
    }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text("The same cow twice?", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        val list = pairs?.filter { "${it.a}-${it.b}" !in different }
        Text(
            "These pairs look alike enough to be one cow that was learnt twice. Merging keeps the first one’s name. " +
                "Two entries for one cow from opposite sides won’t show here (its sides look different): merge those from the cow’s own page.",
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (list == null) {
            Text("Checking…", color = C.muted, modifier = Modifier.padding(16.dp))
        } else if (list.isEmpty()) {
            Text("No pairs left to check.", color = C.muted, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                items(list, key = { "${it.a}-${it.b}" }) { d ->
                    Card(Modifier.padding(vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((cid, label) in listOf(d.a to d.labelA, d.b to d.labelB)) {
                                Column(Modifier.weight(1f).clickable { onOpen(cid) }) {
                                    CowPicture(cid, Modifier.fillMaxWidth().aspectRatio(1.5f))
                                    Text(label, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (debug) "%.2f alike".format(d.score) else "", color = C.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { different = different + "${d.a}-${d.b}" }) { Text("Different cows", color = C.text) }
                            Button(onClick = { merge = d }, colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent)) { Text("Same cow: merge") }
                        }
                    }
                }
            }
        }
    }
    merge?.let { d ->
        Confirm("Merge ${d.labelB} into ${d.labelA}?", "They become one cow with both sets of looks and counts. This can’t be undone.", "Merge", onDismiss = { merge = null }) {
            synchronized(app.herd) {
                val a = app.herd[d.a]
                val b = app.herd[d.b]
                if (a != null && b != null) app.herd.merge(a, b)
            }
            merge = null
        }
    }
}
