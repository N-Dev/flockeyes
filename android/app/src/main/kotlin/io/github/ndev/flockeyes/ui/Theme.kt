package io.github.ndev.flockeyes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Dark, with the green of the web version.
object C {
    val bg = Color(0xFF0B110A)
    val surface = Color(0xFF151D13)
    val surface2 = Color(0xFF1D281A)
    val line = Color(0x14FFFFFF)
    val line2 = Color(0x24FFFFFF)
    val text = Color(0xFFEAF2E3)
    val muted = Color(0xFF9BAE90)
    val accent = Color(0xFF8FE05A)
    val onAccent = Color(0xFF0C1408)
    val sky = Color(0xFF6CB8F0)
    val amber = Color(0xFFFFB84D)
    val red = Color(0xFFFF7A6B)
}

@Composable
fun FlockEyesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.onAccent,
            secondary = C.sky,
            tertiary = C.amber,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.muted,
            surfaceContainer = C.surface,
            surfaceContainerHigh = C.surface2,
            surfaceContainerLow = C.surface,
            outline = C.line2,
            outlineVariant = C.line,
            error = C.red,
            secondaryContainer = Color(0xFF25381C),
            onSecondaryContainer = C.accent,
        ),
        content = content,
    )
}

/** A few icons drawn here rather than pulling in the whole extended icon set. */
object Ic {
    private fun icon(name: String, stroke: Boolean = false, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (stroke) {
                path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = build)
            } else {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = build)
            }
        }.build()

    /** A cow side-on. */
    val cow: ImageVector = icon("cow") {
        moveTo(3f, 8f); lineTo(15f, 8f); lineTo(17f, 6f); lineTo(20.5f, 6f); lineTo(21.5f, 8f); lineTo(21f, 11f); lineTo(18f, 11.5f)
        lineTo(17f, 13.5f); lineTo(17f, 15.5f); lineTo(16f, 15.5f); lineTo(16f, 20f); lineTo(14f, 20f); lineTo(14f, 16f); lineTo(6f, 16f)
        lineTo(6f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f); lineTo(2.5f, 14f); lineTo(2f, 17f); lineTo(1f, 17f); lineTo(1.5f, 10f); close()
    }

    /** A field: the corners of a viewfinder round a dot. */
    val field: ImageVector = icon("field") {
        moveTo(3f, 4f); lineTo(9f, 4f); lineTo(9f, 6f); lineTo(5f, 6f); lineTo(5f, 10f); lineTo(3f, 10f); close()
        moveTo(15f, 4f); lineTo(21f, 4f); lineTo(21f, 10f); lineTo(19f, 10f); lineTo(19f, 6f); lineTo(15f, 6f); close()
        moveTo(3f, 14f); lineTo(5f, 14f); lineTo(5f, 18f); lineTo(9f, 18f); lineTo(9f, 20f); lineTo(3f, 20f); close()
        moveTo(19f, 14f); lineTo(21f, 14f); lineTo(21f, 20f); lineTo(15f, 20f); lineTo(15f, 18f); lineTo(19f, 18f); close()
        moveTo(8f, 10f); lineTo(14f, 10f); lineTo(15f, 9f); lineTo(16.5f, 9f); lineTo(16.5f, 11.5f); lineTo(15f, 12f); lineTo(15f, 15f)
        lineTo(13.5f, 15f); lineTo(13.5f, 13.5f); lineTo(9.5f, 13.5f); lineTo(9.5f, 15f); lineTo(8f, 15f); close()
    }

    /** A gate: two posts and an arrow going through. */
    val gate: ImageVector = icon("gate") {
        moveTo(3f, 4f); lineTo(5.5f, 4f); lineTo(5.5f, 20f); lineTo(3f, 20f); close()
        moveTo(18.5f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(18.5f, 20f); close()
        moveTo(7.5f, 11f); lineTo(13f, 11f); lineTo(13f, 8f); lineTo(17f, 12f); lineTo(13f, 16f); lineTo(13f, 13f); lineTo(7.5f, 13f); close()
    }

    val history: ImageVector = icon("history", stroke = true) {
        moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
        moveTo(3.5f, 12f); lineTo(3.5f, 7.5f)
        moveTo(3.5f, 12f); lineTo(7f, 10.5f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun local(t: Long): ZonedDateTime = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())

/** "Today", "Yesterday", "Tue 3 Mar" or "3 Mar 2025". */
fun dayLabel(t: Long): String {
    val d = local(t).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> DAY.format(d)
        else -> DAY_YEAR.format(d)
    }
}

fun timeLabel(t: Long): String = TIME.format(local(t))

/** "Today 14:05" */
fun whenLabel(t: Long): String = if (t <= 0) "never" else "${dayLabel(t)} ${timeLabel(t)}"

/** "1 cow", "3 cows" */
fun cows(n: Int): String = "$n cow${if (n == 1) "" else "s"}"

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** A row of choices, one selected. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(C.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.text else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) C.bg else C.muted, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A setting: title, explanation, and a control underneath. */
@Composable
fun SettingRow(title: String, sub: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        control()
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, checkedThumbColor = C.bg))
    }
}

/** A pill-shaped label, like the status over the camera picture. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dot: Color? = null, color: Color = C.text, background: Color = Color(0xB3141A12)) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, C.line, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = color, fontSize = 12.5.sp, maxLines = 1)
    }
}

/** A round button over the camera picture. */
@Composable
fun RoundButton(icon: ImageVector, description: String, on: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 6.dp).size(42.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) C.amber else Color(0x99141A12), contentColor = if (on) C.bg else C.text),
    ) { Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp)) }
}

/** A yes/no question before something that can't be undone. */
@Composable
fun Confirm(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(title, color = C.text) },
        text = { Text(text, color = C.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = C.red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.text) } },
    )
}

/** Faint corner marks round the part of the picture being analysed. */
fun DrawScope.corners(l: Float, t: Float, r: Float, b: Float) {
    val k = 16.dp.toPx()
    val c = Color.White.copy(alpha = 0.35f)
    val w = 1.5.dp.toPx()
    for ((x, y, dx, dy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f))) {
        drawLine(c, Offset(x, y), Offset(x + dx * k, y), w)
        drawLine(c, Offset(x, y), Offset(x, y + dy * k), w)
    }
}
