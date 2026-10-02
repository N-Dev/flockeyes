package io.github.ndev.flockeyes.core.herd

import io.github.ndev.flockeyes.core.reid.Tuning
import io.github.ndev.flockeyes.core.reid.dot

/**
 * One look of a cow: what the recognition model made of a picture of it (a unit vector). `ref` is where
 * the app keeps the picture itself (0 until it's saved).
 */
class View(val emb: FloatArray, val added: Long, val quality: Double, var ref: Long = 0) {
    /** The look as the herd's tuning sees it (see [Herd.tuning]); the plain description until there is one. */
    var tuned: FloatArray = emb
        internal set
}

/** A cow the app knows: its name or tag, and a few looks of it from different angles. */
class Cow(val id: Int, var name: String, var tag: String = "", var note: String = "", val created: Long = 0) {
    val views = ArrayList<View>()
    var lastSeen: Long = created

    /** How many counts it has been seen in. */
    var seen: Int = 0

    /** What to call it on screen: its tag if it has one, else its name. */
    val label: String get() = if (tag.isNotBlank()) tag else name

    /** How like this cow a look is: the best match among its views. `tuned`: the look as [Herd.see] gives it. */
    fun similarity(tuned: FloatArray): Double {
        var best = -1.0
        for (v in views) {
            val s = dot(tuned, v.tuned)
            if (s > best) best = s
        }
        return best
    }

    /** The same on the plain descriptions (for telling whether a look is one the cow already has). */
    fun plainSimilarity(emb: FloatArray): Double {
        var best = -1.0
        for (v in views) {
            val s = dot(emb, v.emb)
            if (s > best) best = s
        }
        return best
    }

    /** The view that adds least: the one most like another. */
    internal fun mostRedundant(): Int {
        var worst = -1
        var worstS = -2.0
        for (i in views.indices) {
            var m = -1.0
            for (j in views.indices) if (i != j) m = maxOf(m, dot(views[i].emb, views[j].emb))
            if (m > worstS) {
                worstS = m
                worst = i
            }
        }
        return worst
    }
}

/** What happened to the herd, for the app to save. */
interface HerdListener {
    fun cowAdded(cow: Cow) {}
    fun cowChanged(cow: Cow) {}
    fun cowRemoved(id: Int) {}

    /** A look was added to a cow; `px` is its picture (ARGB, [View] size square), `aspect` the box's height / width. */
    fun viewAdded(cow: Cow, view: View, px: IntArray?, aspect: Double) {}
    fun viewRemoved(cow: Cow, view: View) {}

    /** Cow `from` turned out to be cow `into`: its looks and sightings move over. */
    fun merged(into: Cow, from: Int) {}
}

/**
 * The cows the app knows. Everything that reads or changes it holds its lock (`synchronized(herd)`): the
 * camera thread names cows while the screens rename, merge and delete them.
 */
class Herd(var maxViews: Int = 12) {
    private val list = ArrayList<Cow>()
    private val byId = HashMap<Int, Cow>()
    var nextId = 1
    var listener: HerdListener? = null

    /** Bumped on every change, so screens know to redraw. */
    @Volatile
    var version = 0
        private set

    val cows: List<Cow> get() = list
    val size: Int get() = list.size
    operator fun get(id: Int): Cow? = byId[id]

    // ---------------------------------------------------------------- the yardstick

    /** How looks are compared (see [Tuning]); null: the model's plain descriptions. Looks are compared as [see] gives them. */
    var tuning: Tuning? = null
        private set

    /** Bumped whenever the tuning changes: looks seen with an older one are out of date. */
    var tuneVersion = 0
        private set

    /** A look as the tuning sees it (the plain description if there's no tuning). */
    fun see(emb: FloatArray): FloatArray = tuning?.apply(emb) ?: emb

    /** Takes up a tuning (null: none), and sees every look in the herd afresh. */
    fun retune(t: Tuning?) {
        tuning = t
        for (c in list) for (v in c.views) v.tuned = t?.apply(v.emb) ?: v.emb
        tuneVersion++
        version++
    }

    /** Puts back one look of a cow read from storage (no listener calls). */
    fun restoreView(cow: Cow, view: View) {
        view.tuned = see(view.emb)
        cow.views.add(view)
        version++
    }

    /** Puts back a cow read from storage (no listener calls). */
    fun restore(cow: Cow) {
        for (v in cow.views) v.tuned = see(v.emb)
        byId[cow.id]?.let { list.remove(it) }
        list.add(cow)
        byId[cow.id] = cow
        if (cow.id >= nextId) nextId = cow.id + 1
        version++
    }

    fun clear() {
        list.clear()
        byId.clear()
        version++
    }

    /** A new cow, called "Cow 12" until it's given a name or tag. */
    fun create(now: Long): Cow {
        val id = nextId++
        val cow = Cow(id, "Cow $id", created = now)
        list.add(cow)
        byId[id] = cow
        version++
        listener?.cowAdded(cow)
        return cow
    }

    /**
     * Adds a look to a cow unless it has one much like it already (`dup`). A full set makes room by
     * dropping the look that added least. Returns whether it was added.
     */
    fun addView(cow: Cow, emb: FloatArray, quality: Double, now: Long, px: IntArray?, aspect: Double, dup: Double = 0.92): Boolean {
        if (cow.views.isNotEmpty() && cow.plainSimilarity(emb) >= dup) return false
        val v = View(emb, now, quality)
        v.tuned = see(emb)
        cow.views.add(v)
        if (cow.views.size > maxViews) {
            val i = cow.mostRedundant()
            val gone = cow.views.removeAt(i)
            if (gone === v) return false
            listener?.viewRemoved(cow, gone)
        }
        version++
        listener?.viewAdded(cow, v, px, aspect)
        return true
    }

    fun rename(cow: Cow, name: String, tag: String, note: String = cow.note) {
        cow.name = name.trim().ifEmpty { "Cow ${cow.id}" }
        cow.tag = tag.trim()
        cow.note = note.trim()
        version++
        listener?.cowChanged(cow)
    }

    fun sawNow(cow: Cow, now: Long) {
        cow.lastSeen = now
        cow.seen++
        version++
        listener?.cowChanged(cow)
    }

    fun remove(id: Int) {
        val cow = byId.remove(id) ?: return
        list.remove(cow)
        version++
        listener?.cowRemoved(id)
    }

    /**
     * Two entries that are one cow (learnt twice, say from each side): `from`'s looks go to `into`, which
     * keeps its own name unless it never had one.
     */
    fun merge(into: Cow, from: Cow) {
        if (into === from) return
        if (into.tag.isBlank() && from.tag.isNotBlank()) into.tag = from.tag
        if (into.name == "Cow ${into.id}" && from.name != "Cow ${from.id}") into.name = from.name
        if (into.note.isBlank()) into.note = from.note
        into.lastSeen = maxOf(into.lastSeen, from.lastSeen)
        into.seen = maxOf(into.seen, from.seen)
        // The app moves the saved pictures and sightings; then the looks are thinned to the limit.
        into.views.addAll(from.views)
        from.views.clear()
        list.remove(from)
        byId.remove(from.id)
        listener?.merged(into, from.id)
        while (into.views.size > maxViews) {
            val gone = into.views.removeAt(into.mostRedundant())
            listener?.viewRemoved(into, gone)
        }
        version++
        listener?.cowChanged(into)
    }

    /** A view moved out of a cow (a wrong look), by its saved id. */
    fun removeView(cow: Cow, ref: Long) {
        val v = cow.views.firstOrNull { it.ref == ref } ?: return
        cow.views.remove(v)
        version++
        listener?.viewRemoved(cow, v)
    }
}
