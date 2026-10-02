package io.github.ndev.flockeyes.herd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.ai.Model
import io.github.ndev.flockeyes.core.herd.Cow
import io.github.ndev.flockeyes.core.herd.HerdListener
import io.github.ndev.flockeyes.core.herd.View
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.debug.DebugLog
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps the herd saved: every change the camera or a screen makes to it (a cow learnt, a look added, a
 * rename, a merge) is written to the database as it happens. Each look is saved with the small picture it
 * was made from, so the herd can be shown, and worked out again if the recognition model is ever changed.
 */
class HerdStore(private val app: App) : HerdListener {
    private val herd get() = app.herd
    private val db get() = app.db

    /** Cows that have a picture saved. */
    private val pictured = HashSet<Int>()

    /** Looks saved with another recognition model, waiting to be worked out again. */
    @Volatile
    var stale = 0
        private set

    /** Decoded pictures for the screens: "cow:12" (the cow's picture) and "view:345" (one look). */
    val pictures = LruCache<String, Bitmap>(240)

    /** Reads the herd from the database. Looks made by another recognition model are left out until [refreshOldLooks] has redone them. */
    fun load() {
        synchronized(herd) {
            herd.listener = null
            herd.clear()
            pictured.clear()
            pictures.evictAll()
            val key = app.engine.reid.key
            val dim = app.engine.reid.dim
            val cows = LinkedHashMap<Int, Cow>()
            for (r in db.cows()) {
                val c = Cow(r.id, r.name, r.tag, r.note, r.created)
                c.lastSeen = r.lastSeen
                c.seen = r.seen
                cows[r.id] = c
                if (db.hasThumb(r.id)) pictured.add(r.id)
            }
            var old = 0
            for (v in db.views()) {
                val c = cows[v.cow] ?: continue
                val e = v.emb
                if (v.model == key && e != null && e.size == dim) c.views.add(View(e, v.added, v.quality, v.id)) else old++
            }
            stale = old
            // Looks are compared through the recognition model's tuning.
            herd.retune(app.engine.tuning)
            for (c in cows.values) herd.restore(c)
            herd.nextId = maxOf(herd.nextId, db.meta("next_cow")?.toIntOrNull() ?: 1)
            herd.listener = this
            DebugLog.add("herd", "Herd loaded: ${cows.size} cows, ${cows.values.sumOf { it.views.size }} looks" + if (old > 0) ", $old looks from another model to redo" else "")
        }
        app.dataChanged()
    }

    /**
     * Looks saved with another recognition model (after an update that changed it) are worked out again
     * from their pictures, so the herd doesn't have to be learnt again. Run off the main thread.
     */
    fun refreshOldLooks() {
        if (stale == 0) return
        val cfg = app.engine.reid
        val embedder = Embedder(cfg)
        val net = app.engine.net(Model.REID)
        var done = 0
        val px = IntArray(cfg.size * cfg.size)
        for (v in db.views()) {
            if (v.model == cfg.key && v.emb?.size == cfg.dim) continue
            val jpeg = db.crop(v.id) ?: continue
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
            val sq = if (bmp.width == cfg.size && bmp.height == cfg.size) bmp else Bitmap.createScaledBitmap(bmp, cfg.size, cfg.size, true)
            sq.getPixels(px, 0, cfg.size, 0, 0, cfg.size, cfg.size)
            val emb = embedder.embed(net, px)
            db.updateView(v.id, emb, cfg.key)
            synchronized(herd) {
                herd[v.cow]?.let { herd.restoreView(it, View(emb, v.added, v.quality, v.id)) }
            }
            done++
        }
        stale = 0
        if (done > 0) {
            DebugLog.add("herd", "$done looks worked out again with ${cfg.key}")
            app.dataChanged()
        }
    }

    // ---------------------------------------------------------------- what the herd tells us

    override fun cowAdded(cow: Cow) {
        db.saveCow(cow.id, cow.name, cow.tag, cow.note, cow.created, cow.lastSeen, cow.seen)
        db.setMeta("next_cow", herd.nextId.toString())
        DebugLog.add("herd", "Learnt a new cow: ${cow.name}")
        app.dataChanged()
    }

    override fun cowChanged(cow: Cow) {
        db.saveCow(cow.id, cow.name, cow.tag, cow.note, cow.created, cow.lastSeen, cow.seen)
        app.dataChanged()
    }

    override fun cowRemoved(id: Int) {
        db.deleteCow(id)
        pictured.remove(id)
        pictures.remove("cow:$id")
        app.dataChanged()
    }

    override fun viewAdded(cow: Cow, view: View, px: IntArray?, aspect: Double) {
        val size = app.engine.reid.size
        val crop = px?.let { jpeg(Bitmap.createBitmap(it, size, size, Bitmap.Config.ARGB_8888), 88) }
        view.ref = db.addView(cow.id, view.emb, crop, aspect, view.quality, view.added, app.engine.reid.key)
        // The cow's own picture: its first look, the right shape again.
        if (px != null && cow.id !in pictured) {
            pictured.add(cow.id)
            db.setThumb(cow.id, jpeg(unsquash(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888), aspect, 256), 85))
            pictures.remove("cow:${cow.id}")
        }
        app.dataChanged()
    }

    override fun viewRemoved(cow: Cow, view: View) {
        if (view.ref != 0L) {
            db.deleteView(view.ref)
            pictures.remove("view:${view.ref}")
        }
        app.dataChanged()
    }

    override fun merged(into: Cow, from: Int) {
        // If the cow being kept has no picture, it takes the other's.
        if (into.id !in pictured && from in pictured) {
            db.thumb(from)?.let {
                db.setThumb(into.id, it)
                pictured.add(into.id)
                pictures.remove("cow:${into.id}")
            }
        }
        db.mergeCows(into.id, from)
        pictured.remove(from)
        pictures.remove("cow:$from")
        DebugLog.add("herd", "Merged cow $from into ${into.name} (${into.id})")
        app.dataChanged()
    }

    // ---------------------------------------------------------------- pictures

    /** The cow's picture (the right shape), or null if it has none. Reads the database: call off the main thread. */
    fun cowPicture(id: Int): Bitmap? {
        pictures.get("cow:$id")?.let { return it }
        val b = db.thumb(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        pictures.put("cow:$id", b)
        return b
    }

    /** One look's picture, the right shape again. */
    fun viewPicture(id: Long, aspect: Double): Bitmap? {
        pictures.get("view:$id")?.let { return it }
        val raw = db.crop(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        val b = unsquash(raw, aspect, 224)
        pictures.put("view:$id", b)
        return b
    }

    /** Makes this look the cow's picture. */
    fun usePicture(cow: Int, view: Long, aspect: Double) {
        val raw = db.crop(view)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return
        db.setThumb(cow, jpeg(unsquash(raw, aspect, 256), 85))
        pictured.add(cow)
        pictures.remove("cow:$cow")
        app.dataChanged()
    }

    /** Every cow's pictures in a zip, a folder a cow: for looking through, or for training a model later. */
    fun exportPictures(out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            val cows = synchronized(herd) { herd.cows.map { it.id to it.label } }
            for ((id, label) in cows) {
                val folder = "cow_%04d_%s".format(id, label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-'))
                for (v in db.viewsOf(id)) {
                    val crop = v.crop ?: continue
                    val b = BitmapFactory.decodeByteArray(crop, 0, crop.size) ?: continue
                    zip.putNextEntry(ZipEntry("$folder/look_${v.id}.jpg"))
                    zip.write(jpeg(unsquash(b, v.aspect, 448), 90))
                    zip.closeEntry()
                    n++
                }
            }
        }
        return n
    }

    companion object {
        fun jpeg(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        /** A square picture squashed from a box (`aspect` = height / width) pulled back to its real shape, `long` pixels on its longer side. */
        fun unsquash(b: Bitmap, aspect: Double, long: Int): Bitmap {
            val a = aspect.coerceIn(0.2, 5.0)
            val w = if (a <= 1) long else maxOf(1, Math.round(long / a).toInt())
            val h = if (a <= 1) maxOf(1, Math.round(long * a).toInt()) else long
            return Bitmap.createScaledBitmap(b, w, h, true)
        }
    }
}
