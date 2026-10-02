package io.github.ndev.flockeyes.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.camera.zoomLabel
import io.github.ndev.flockeyes.core.count.Gate
import io.github.ndev.flockeyes.core.count.PlaceShown
import io.github.ndev.flockeyes.core.count.Shown
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.debug.DebugStrip
import io.github.ndev.flockeyes.debug.enginesText
import io.github.ndev.flockeyes.debug.rememberHealth
import io.github.ndev.flockeyes.ui.C
import io.github.ndev.flockeyes.ui.corners
import kotlin.math.min

/** Where the camera picture sits in a box of `w` x `h` (letterboxed, centred). `aspect` = frame height / width. */
class VideoRect(val x: Float, val y: Float, val w: Float, val h: Float)

fun videoRect(w: Float, h: Float, aspect: Double): VideoRect {
    val fw = min(w, (h / aspect).toFloat())
    val fh = (fw * aspect).toFloat()
    return VideoRect((w - fw) / 2, (h - fh) / 2, fw, fh)
}

/** What a box on the picture says, and its colour. */
fun boxLabel(b: Shown): String = when (b.state) {
    "named" -> b.label + if (b.unsure) "?" else ""
    "new" -> "New: ${b.label}"
    "unknown" -> "Not known"
    "looking" -> "Looking…"
    else -> ""
}

fun boxColor(b: Shown): Color = when (b.state) {
    "named" -> C.accent
    "new" -> C.amber
    "unknown" -> C.red
    "looking" -> Color.White.copy(alpha = 0.7f)
    else -> Color.White.copy(alpha = 0.35f)
}

/**
 * The picture's overlay: the analysed area, each cow's box with its name (green once named, amber if just
 * learnt), and at a gate the line with which way is which. Debug mode adds what the finder saw this frame
 * (dashed), where each cow is heading, for each cow its track number, looks and best two matches, and (in
 * a field count) the places cows have been counted at (dotted).
 */
@Composable
fun CowOverlay(
    boxes: List<Shown>,
    raw: List<Det>,
    aspect: Double,
    roi: DoubleArray,
    debug: Boolean,
    modifier: Modifier,
    line: DoubleArray? = null,
    editing: Boolean = false,
    dir1: String = "",
    dir2: String = "",
    places: List<PlaceShown> = emptyList(),
    /** How much of the top of the picture is under the status pill: the line's labels keep clear of it. */
    topClear: Dp = 0.dp,
) {
    Canvas(modifier) {
        val r = videoRect(size.width, size.height, aspect)
        fun x(v: Double) = r.x + (v * r.w).toFloat()
        fun y(v: Double) = r.y + (v * r.h).toFloat()
        if (roi[2] < 0.995 || roi[3] < 0.995) corners(x(roi[0]), y(roi[1]), x(roi[0] + roi[2]), y(roi[1] + roi[3]))
        val canvas = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val small = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 10.sp.toPx()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        if (!editing && debug) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            small.color = C.sky.toArgb()
            for (d in raw) {
                val l = x(d.box[0])
                val t = y(d.box[1])
                drawRect(C.sky, Offset(l, t), Size(x(d.box[2]) - l, y(d.box[3]) - t), style = Stroke(1.dp.toPx(), pathEffect = dash))
                canvas.drawText("${d.cls} %.2f${if (d.cut) " cut" else ""}".format(d.score), l, t - 3.dp.toPx(), small)
            }
            // Where cows have been counted: a place that counts is white, one not yet sure is red.
            val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
            for (p in places) {
                val l = x(p.box[0].coerceIn(0.0, 1.0))
                val t = y(p.box[1].coerceIn(0.0, 1.0))
                val rr = x(p.box[2].coerceIn(0.0, 1.0))
                val bb = y(p.box[3].coerceIn(0.0, 1.0))
                val c = if (p.counts) Color.White else C.red
                drawRect(c, Offset(l, t), Size(rr - l, bb - t), style = Stroke(1.5.dp.toPx(), pathEffect = dots))
                small.color = c.toArgb()
                canvas.drawText("p${p.id}" + (if (p.label.isEmpty()) "" else " ${p.label}"), l + 3.dp.toPx(), bb - 3.dp.toPx(), small)
            }
            for (b in boxes) {
                // Half a second of travel at its current speed.
                val cx = (b.box[0] + b.box[2]) / 2
                val cy = (b.box[1] + b.box[3]) / 2
                val from = Offset(x(cx), y(cy))
                val to = Offset(x(cx + b.vx * 500), y(cy + b.vy * 500))
                if ((to - from).getDistance() > 4.dp.toPx()) {
                    drawLine(C.amber, from, to, 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(C.amber, 3.dp.toPx(), to)
                }
            }
        }
        if (!editing) {
            for (b in boxes) {
                val l = x(b.box[0])
                val t = y(b.box[1])
                val w = x(b.box[2]) - l
                val h = y(b.box[3]) - t
                val color = boxColor(b)
                val thick = if (b.crossed != 0) 4.dp.toPx() else if (b.state == "named" || b.state == "new") 2.dp.toPx() else 1.5.dp.toPx()
                drawRect(color, Offset(l, t), Size(w, h), style = Stroke(thick))
                val text = (if (debug) "#${b.id} " else "") + boxLabel(b) + (if (debug && b.state == "named") " %.2f".format(b.score) else "")
                if (text.isNotBlank()) {
                    val tw = paint.measureText(text) + 10.dp.toPx()
                    val th = 17.dp.toPx()
                    val ty = if (t - th - 1 < r.y) t + 1 else t - th - 1
                    drawRect(if (b.state == "named" || b.state == "new") Color(0xD90E1A0B) else Color(0x8C000000), Offset(l, ty), Size(tw, th))
                    paint.color = color.copy(alpha = 1f).toArgb()
                    paint.textAlign = android.graphics.Paint.Align.LEFT
                    canvas.drawText(text, l + 5.dp.toPx(), ty + th - 5.dp.toPx(), paint)
                }
                if (debug) {
                    // Under the box: sightings and looks, then the two most alike cows in the herd.
                    small.color = android.graphics.Color.WHITE
                    val line1 = "${b.hits} seen · ${b.looks} looks" + (if (b.waiting > 0) " (+${b.waiting})" else "") + (if (b.skipped.isNotEmpty()) " · ${b.skipped}" else "") +
                        (if (b.state == "named" || b.state == "new") " · now %.2f".format(b.agree) else "")
                    canvas.drawText(line1, l, t + h + 11.sp.toPx(), small)
                    if (b.best.isNotEmpty()) {
                        val line2 = "${b.best} %.2f".format(b.bestScore) + (if (b.second.isNotEmpty()) " · ${b.second} %.2f".format(b.secondScore) else "")
                        canvas.drawText(line2, l, t + h + 23.sp.toPx(), small)
                    }
                }
            }
        }
        if (line != null) {
            val p0 = Offset(x(line[0]), y(line[1]))
            val p1 = Offset(x(line[2]), y(line[3]))
            drawLine(Color(0x8C000000), p0, p1, 6.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, p0, p1, 2.5.dp.toPx(), StrokeCap.Round)
            if (editing) {
                for (hnd in listOf(p0, p1)) {
                    drawCircle(C.accent.copy(alpha = 0.25f), 18.dp.toPx(), hnd)
                    drawCircle(C.accent, 7.dp.toPx(), hnd)
                }
            }
            // Which way is which: each direction's name on the side cows going that way end up on.
            val mid = Offset((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
            val level = Gate.isLevel(line, aspect)
            paint.textSize = 12.sp.toPx()
            for ((name, sign) in listOf(dir1 to 1f, dir2 to -1f)) {
                if (name.isBlank()) continue
                val label = if (level) (if (sign > 0) "↓ $name" else "↑ $name") else (if (sign > 0) "$name →" else "← $name")
                val tw = paint.measureText(label) + 12.dp.toPx()
                val th = 19.dp.toPx()
                val gap = 12.dp.toPx()
                val bx: Float
                val by: Float
                if (level) {
                    bx = mid.x - tw / 2
                    by = if (sign > 0) mid.y + gap else mid.y - gap - th
                } else {
                    val top = min(p0.y, p1.y)
                    bx = if (sign > 0) mid.x + gap else mid.x - gap - tw
                    by = top + 6.dp.toPx() + (if (sign > 0) 0f else th + 4.dp.toPx())
                }
                val cx = bx.coerceIn(r.x + 2f, maxOf(r.x + 2f, r.x + r.w - tw - 2f))
                // Under the status pill at the top, a label can't be read: it goes below it (the second below the first).
                val clear = topClear.toPx() + (if (!level && sign < 0) th + 4.dp.toPx() else 0f)
                val cy = maxOf(by, clear).coerceIn(r.y + 2f, maxOf(r.y + 2f, r.y + r.h - th - 2f))
                drawRect(Color(0xB3000000), Offset(cx, cy), Size(tw, th))
                paint.color = android.graphics.Color.WHITE
                paint.textAlign = android.graphics.Paint.Align.LEFT
                canvas.drawText(label, cx + 6.dp.toPx(), cy + th - 5.5.dp.toPx(), paint)
            }
        }
    }
}

/** Zoom buttons over the picture. */
@Composable
fun ZoomChips(steps: List<Float>, zoom: Float, modifier: Modifier = Modifier, onZoom: (Float) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (z in steps) {
            val on = kotlin.math.abs(z - zoom) < 0.05f
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) C.text else Color(0x99000000))
                    .clickable { onZoom(z) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(zoomLabel(z), color = if (on) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Debug mode's numbers over the picture. */
@Composable
fun ScanDebug(ui: ScanUi, modifier: Modifier = Modifier) {
    val app = App.instance
    val camFps by app.camera.cameraFps.collectAsState()
    val health = rememberHealth(true)
    val lines = buildList {
        add("camera %.1f fps · analysed %.1f fps · %d frames".format(camFps, ui.fps, ui.frames))
        add("picture %.0f ms · finder %.0f ms (%s, %d area%s) · after %.0f ms".format(ui.msPrep, ui.msFind, ui.model, ui.tiles, if (ui.tiles == 1) "" else "s", ui.msPost))
        add("look %.0f ms · %d waiting · %d taken · same cow ≥ %.2f and %.2f clear".format(ui.msLook, ui.waiting, ui.looks, ui.match, ui.margin))
        add("frame ${ui.frameW}×${ui.frameH} · ${ui.raw.size} found · ${ui.boxes.size} followed · herd ${app.herd.size}")
        // A field count: cows counted by where they stand, and how far the phone has turned since it began.
        if (ui.running && (ui.placed > 0 || ui.panX != 0.0 || ui.panY != 0.0)) {
            add("by place ${ui.placed} · most at once ${ui.peak} · named ${ui.named} · turned %+.2f across, %+.2f down%s".format(ui.panX, ui.panY, if (ui.swings > 0) " · lost ${ui.swings}×" else ""))
        }
        add(enginesText(listOf(Model.DET_TINY, Model.DET_NANO, Model.REID).filter { app.engine.isLoaded(it) }))
        health?.let { add(it.text()) }
    }
    DebugStrip(lines, ui.times, modifier)
}

fun fpsText(v: Double) = if (v < 10) "%.1f".format(v) else "%.0f".format(v)

fun clock(ms: Long): String {
    val secs = maxOf(0L, ms / 1000)
    return "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
}
