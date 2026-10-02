package io.github.ndev.flockeyes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Screenshots and results the CI job pulls off the emulator and publishes (android-ci branch). */
object Shots {
    val dir: File
        // The app's own files: the CI job copies them off with `run-as` (the debug app allows it).
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ci").apply { mkdirs() }

    fun take(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("screenshot $name")
    }

    fun log(line: String) {
        Log.i("FlockEyesTest", line)
        File(dir, "results.txt").appendText(line + "\n")
    }

    fun save(name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    /** The repository's test pictures (tests/assets), which are built into the test app. */
    private val pictures get() = InstrumentationRegistry.getInstrumentation().context.assets

    private val singles = HashMap<String, Bitmap>()

    /** A picture of one cow cut from a video: "cow_a1" and "cow_a2" are two of cow a (see tests/assets). */
    fun single(name: String): Bitmap = synchronized(singles) {
        singles.getOrPut(name) { pictures.open("singles/$name.jpg").use { BitmapFactory.decodeStream(it) } }
    }

    val SINGLES = listOf("cow_a1", "cow_a2", "cow_c1", "cow_c2", "cow_d1", "cow_d2")

    /** The names of a video's frames, in order ("heap": cows at a heap of silage). */
    fun clip(name: String): List<String> = pictures.list("clips/$name")!!.filter { it.endsWith(".jpg") }.sorted().map { "clips/$name/$it" }

    fun frame(path: String): Bitmap = pictures.open(path).use { BitmapFactory.decodeStream(it) }

    /**
     * A pretend frame: pictures of cows placed on a plain background. Each entry is a picture with the left
     * and top of where it goes and how wide it's drawn, in pixels of a `w` x `h` frame.
     */
    fun field(w: Int, h: Int, placed: List<Placed>): Bitmap {
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        c.drawColor(Color.rgb(0x6b, 0x8e, 0x4e))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.rgb(0x9f, 0xb8, 0xc8)
        c.drawRect(0f, 0f, w.toFloat(), h / 5f, paint)
        for (p in placed) {
            val ph = p.w * p.photo.height / p.photo.width
            c.drawBitmap(p.photo, null, Rect(p.x, p.y, p.x + p.w, p.y + ph), paint)
        }
        return frame
    }

    fun png(b: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

class Placed(val photo: Bitmap, val x: Int, val y: Int, val w: Int)
