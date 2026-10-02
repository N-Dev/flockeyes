package io.github.ndev.flockeyes.core.count

import io.github.ndev.flockeyes.core.detect.CowDetector
import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.herd.Cow
import io.github.ndev.flockeyes.core.herd.Herd
import io.github.ndev.flockeyes.core.herd.HerdCheck
import io.github.ndev.flockeyes.core.image.Frame
import io.github.ndev.flockeyes.core.image.area
import io.github.ndev.flockeyes.core.image.iou
import io.github.ndev.flockeyes.core.reid.Embedder
import io.github.ndev.flockeyes.core.reid.dot
import io.github.ndev.flockeyes.core.track.Candidate
import io.github.ndev.flockeyes.core.track.Crossed
import io.github.ndev.flockeyes.core.track.Looked
import io.github.ndev.flockeyes.core.track.Track
import io.github.ndev.flockeyes.core.track.Tracker
import io.github.ndev.flockeyes.core.track.foot
import kotlin.math.max
import kotlin.math.min

/**
 * How cows are recognised and learnt.
 *
 * A cow is named when it looks at least `match` like a cow in the herd AND that cow is clear of the next
 * most alike one by `margin`: what counts is that one cow stands out, more than how alike it looks. A cow
 * that looks less than `fresh` like every cow in the herd is new. In between, the app waits for more looks.
 * (How alike: as the herd's tuning sees the looks, see Tuning.)
 */
class ScanOptions(
    var match: Double = 0.5,
    var fresh: Double = 0.4,
    var margin: Double = 0.04,
    /** Learn cows it doesn't know (off: they're counted as unrecognised). */
    var learn: Boolean = true,
    /** Looks that must agree before a cow is named, and before it's called new; and when to stop waiting. */
    var minLooks: Int = 2,
    var newLooks: Int = 3,
    var maxLooks: Int = 8,
    /** The shorter side of a box, in pixels, below which a cow is too small to recognise. */
    var minSide: Int = 48,
    /** How sure the cow finder must be of a box for a look to be taken (a head or a rump on its own scores low). */
    var minScore: Double = 0.6,
    /** Time between looks at a cow that isn't named yet, and at one that is (ms). */
    var lookGapMs: Double = 250.0,
    var checkGapMs: Double = 2500.0,
    /** Looks of one cow that may be waiting for the recognition model at once. */
    var maxWaiting: Int = 2,
    /** Looks kept when a cow is first learnt. */
    var firstViews: Int = 4,
)

/** The bars a look must clear. */
class Bars(val match: Double, val fresh: Double, val margin: Double) {
    /** Two cows in the herd at least this alike are probably one cow learnt twice. */
    val twin: Double get() = match + margin
}

/** A picture of a tracked cow for the recognition model: see [Scan.look]. */
class Look(val trackId: Int, val px: IntArray, val aspect: Double, val quality: Double, val t: Double)

/**
 * A cow counted: in a field count once per cow (dir 0); at a gate each time one crosses (dir 1 or 2).
 * `cowId` is null for a cow that wasn't recognised. `t` is the real date and time (ms).
 */
class Sighting(val cowId: Int?, val t: Long, val score: Double, val fresh: Boolean, val dir: Int = 0, val trackId: Int = 0)

/** What one count has found so far. */
class Session(val gate: Boolean) {
    /** Field: the cows counted, in the order they were recognised. */
    val seen = LinkedHashMap<Int, Sighting>()

    /** Cows learnt during this count. */
    val fresh = LinkedHashSet<Int>()

    /** Cows that weren't recognised (learning off, or gone before the app had a good look). */
    var unknown = 0

    /** Field: the most cows in view at once. */
    var peak = 0

    /** Gate: crossings so far, including cows still in view: [total, direction 1, direction 2]. */
    val tally = IntArray(3)

    /** Everything counted and finished with, to be saved. */
    val sightings = ArrayList<Sighting>()

    /** Field: cows told apart. It can't be fewer than were in view at once. */
    val count: Int get() = if (gate) tally[0] else max(seen.size + unknown, peak)
}

/** A tracked cow as the screen shows it. */
class Shown(
    val id: Int,
    val box: DoubleArray,
    /** "named", "new" (learnt just now), "looking" (being worked out), "unknown", "far" (too small or hidden to tell). */
    val state: String,
    val cowId: Int?,
    val label: String,
    val score: Double,
    val unsure: Boolean,
    val counted: Boolean,
    /** Crossed the gate line within the last moment (the box flashes), and which way. */
    val crossed: Int,
    // Debug mode:
    val hits: Int,
    val looks: Int,
    val waiting: Int,
    val skipped: String,
    val best: String,
    val bestScore: Double,
    val second: String,
    val secondScore: Double,
    val agree: Double,
    val vx: Double,
    val vy: Double,
)

/** What one frame led to. */
class FrameOut(
    /** Tracks seen in this frame. */
    val live: List<Track>,
    /** Pictures for the recognition model: hand each answer to [Scan.look]. */
    val looks: List<Look>,
    /** Cows that crossed the gate line in this frame. */
    val crossed: List<Track>,
    /** Sightings finished with in this frame, to save. */
    val saved: List<Sighting>,
    /** Cows named or learnt because their track ended in this frame. */
    val named: List<Track>,
)

/** What a look led to. */
class LookOut(
    /** The track was named (or learnt as a new cow) by this look. */
    val named: Track?,
    val saved: List<Sighting>,
)

/**
 * Follows the cows in the camera's frames and works out who each is: the heart of both the field count
 * and the gate count.
 *
 * Each frame: [frame] with what the cow finder found. It returns pictures of cows worth a look; each goes
 * through the recognition model (on another thread, so the picture keeps moving) and its answer comes back
 * through [look]. A cow is named once enough looks agree, or learnt as a new cow if it's like none in the
 * herd. Everything here holds the herd's lock.
 */
class Scan(val herd: Herd, val opt: ScanOptions, private val embedder: Embedder) {
    val tracker = Tracker()

    /** Whether a count is running: cows are only learnt and counted then. */
    var counting = false
        private set
    var session = Session(false)
        private set

    /** The gate line (x1, y1, x2, y2), or null for a field count. */
    var line: DoubleArray? = null

    /** Only cows in this part of the frame are looked at (the gate's area), or null for all. */
    var region: DoubleArray? = null

    /** Frame time to real time (ms). */
    private var offset = 0L
    private var frameW = 0
    private var frameH = 0
    private var lastT = 0.0
    private val flash = HashMap<Int, Pair<Double, Int>>()
    private val inView = ArrayDeque<Int>()
    private val parts = HashSet<Int>()
    private val close = HashSet<Int>()

    /** Whether two boxes, each grown by `by` of its own size on every side, meet. */
    private fun touching(a: DoubleArray, b: DoubleArray, by: Double): Boolean {
        val ax = (a[2] - a[0]) * by
        val ay = (a[3] - a[1]) * by
        val bx = (b[2] - b[0]) * by
        val by2 = (b[3] - b[1]) * by
        return a[0] - ax < b[2] + bx && b[0] - bx < a[2] + ax && a[1] - ay < b[3] + by2 && b[1] - by2 < a[3] + ay
    }

    fun start(gate: Boolean) = synchronized(herd) {
        session = Session(gate)
        counting = true
        inView.clear()
        for (tr in tracker.tracks) {
            tr.counted = false
            tr.crossings.clear()
            tr.side = 0
            // Cows in view that aren't named yet are looked at afresh, now that they can be learnt.
            if (tr.cowId == null) {
                tr.looks.clear()
                tr.unknown = false
            }
        }
    }

    /** Ends the count: every cow still in view is finished with. Returns the last sightings to save. */
    fun stop(now: Long): List<Sighting> = synchronized(herd) {
        if (!counting) return emptyList()
        val saved = ArrayList<Sighting>()
        val named = ArrayList<Track>()
        for (tr in tracker.tracks) finish(tr, now, saved, named, ending = false)
        counting = false
        saved
    }

    /** The bars a look must clear. */
    fun bars(): Bars = Bars(opt.match, opt.fresh, opt.margin)

    /** Forgets every track (the camera moved to another screen, or the settings changed). */
    fun reset() = synchronized(herd) {
        tracker.flush()
        flash.clear()
    }

    // ---------------------------------------------------------------- frames

    fun frame(dets: List<Det>, frame: Frame, t: Double, now: Long, budget: Int = 2): FrameOut = synchronized(herd) {
        frameW = frame.width
        frameH = frame.height
        val aspect = frame.height.toDouble() / frame.width
        tracker.aspect = aspect
        offset = now - t.toLong()
        lastT = t
        val u = tracker.update(dets, t)
        val live = u.seen

        // A small box touching a much bigger one is a piece of that animal (a head, a rump), not a cow to
        // count or look at.
        parts.clear()
        for (a in live) for (b in live) {
            if (a !== b && area(a.box) < 0.3 * area(b.box) && touching(a.box, b.box, 0.06)) parts.add(a.id)
        }
        // Cows overlapping each other: a picture of one would have some of the other in it, and the box
        // following one may slip onto the other. Cows standing right up against each other: no looks either.
        val overlapped = HashSet<Int>()
        close.clear()
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]
            val b = live[j]
            if (a.id in parts || b.id in parts) continue
            if (iou(a.box, b.box) > 0.12 || CowDetector.inside(a.box, b.box) > 0.25 || CowDetector.inside(b.box, a.box) > 0.25) {
                overlapped.add(a.id)
                overlapped.add(b.id)
                a.clean = false
                b.clean = false
                a.mixed = true
                b.mixed = true
            } else if (touching(a.box, b.box, 0.04)) {
                close.add(a.id)
                close.add(b.id)
            }
        }

        val saved = ArrayList<Sighting>()
        val named = ArrayList<Track>()
        val crossed = ArrayList<Track>()
        val l = line
        if (l != null) for (tr in live) {
            if (!crossing(tr, l, aspect, t)) continue
            crossed.add(tr)
            // A cow not named yet has now gone through: time to decide who it is (or learn it).
            if (tr.cowId == null && tr.looks.isNotEmpty() && resolve(tr, now, last = false)) named.add(tr)
        }
        if (counting && !session.gate) {
            // The most in view at once: the middle of the last five frames, so a box that flickers in for
            // a frame or two (a cow boxed twice, a bush) doesn't count.
            inView.addLast(live.count { it.hits >= 3 && it.topScore >= 0.5 && it.id !in parts })
            if (inView.size > 5) inView.removeFirst()
            if (inView.size == 5) {
                val mid = inView.sorted()[2]
                if (mid > session.peak) session.peak = mid
            }
            for (tr in live) register(tr, now, saved)
        }

        // Which cows get a look this frame: unnamed ones first (those with fewest looks), then named ones
        // not checked for longest.
        val want = live.filter { wantsLook(it, t, overlapped) }
            .sortedWith(compareBy<Track>({ if (it.cowId == null) 0 else 1 }, { if (it.cowId == null) it.looks.size + it.waiting else 0 }, { it.lastSampleT }))
        val looks = ArrayList<Look>()
        for (tr in want.take(budget)) {
            val px = embedder.crop(frame, tr.box)
            val wPx = (tr.box[2] - tr.box[0]) * frame.width
            val hPx = (tr.box[3] - tr.box[1]) * frame.height
            val quality = min(1.0, min(wPx, hPx) / 160.0) * tr.score
            looks.add(Look(tr.id, px, hPx / max(1.0, wPx), quality, t))
            tr.lookShape = hPx / max(1.0, wPx)
            tr.lastSampleT = t
            tr.waiting++
        }

        for (tr in u.lost) {
            finish(tr, now, saved, named, ending = true)
            flash.remove(tr.id)
        }
        FrameOut(live, looks, crossed, saved, named)
    }

    private fun wantsLook(tr: Track, t: Double, overlapped: Set<Int>): Boolean {
        tr.skipped = ""
        if (tr.unknown || tr.hits < 2) return false
        if (tr.waiting >= opt.maxWaiting) return false
        val named = tr.cowId != null
        // An unnamed cow has had its looks; one that looked like a cow elsewhere in view is looked at
        // again now and then (as a named one is), in case that has changed.
        if (!named && !tr.taken && tr.looks.size + tr.waiting >= opt.maxLooks) return false
        val b = tr.box
        val shape = (b[3] - b[1]) * frameH / max(1.0, (b[2] - b[0]) * frameW)
        // A named cow is looked at again now and then, and sooner if its box has changed shape (it has turned).
        val turned = named && tr.lookShape > 0 && kotlin.math.abs(shape - tr.lookShape) / tr.lookShape > 0.18
        val gap = if (!named) (if (tr.taken && tr.looks.size >= opt.minLooks) opt.checkGapMs else opt.lookGapMs) else if (turned) minOf(opt.checkGapMs, 700.0) else opt.checkGapMs
        if (t - tr.lastSampleT < gap) return false
        val e = 0.008
        tr.skipped = when {
            tr.cut -> "cut"
            tr.score < opt.minScore -> "unsure"
            b[0] <= e || b[1] <= e || b[2] >= 1 - e || b[3] >= 1 - e -> "edge"
            min((b[2] - b[0]) * frameW, (b[3] - b[1]) * frameH) < opt.minSide -> "small"
            tr.id in parts -> "part"
            tr.id in overlapped -> "overlap"
            tr.id in close -> "close"
            else -> ""
        }
        if (tr.skipped.isNotEmpty()) return false
        val r = region
        if (r != null) {
            val cx = (b[0] + b[2]) / 2
            val cy = (b[1] + b[3]) / 2
            if (cx < r[0] || cx > r[0] + r[2] || cy < r[1] || cy > r[1] + r[3]) {
                tr.skipped = "outside"
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- looks

    /** The recognition model's answer for a picture from [frame]. */
    fun look(look: Look, emb: FloatArray, now: Long): LookOut = synchronized(herd) {
        val tr = tracker.tracks.firstOrNull { it.id == look.trackId } ?: return LookOut(null, emptyList())
        tr.waiting = max(0, tr.waiting - 1)
        tr.sampled++
        if (look.quality >= tr.thumbQuality || tr.thumb == null) {
            tr.thumb = look.px
            tr.thumbAspect = look.aspect
            tr.thumbQuality = look.quality
        }
        val saved = ArrayList<Sighting>()
        val cow = tr.cowId?.let { herd[it] }
        if (cow != null) {
            check(tr, cow, look, emb, now)
            return LookOut(null, saved)
        }
        tr.cowId = null
        tr.looks.add(Looked(emb, look.px, look.aspect, look.quality, look.t))
        while (tr.looks.size > opt.maxLooks) tr.looks.removeAt(0)
        val did = resolve(tr, now, last = false)
        if (did && counting && !session.gate) register(tr, now, saved)
        LookOut(if (did) tr else null, saved)
    }

    /** A look as the herd's tuning sees it (kept, and worked out again when the tuning changes). */
    private fun seen(lk: Looked): FloatArray {
        val have = lk.tuned
        if (have != null && lk.tuneVersion == herd.tuneVersion) return have
        return herd.see(lk.emb).also {
            lk.tuned = it
            lk.tuneVersion = herd.tuneVersion
        }
    }

    /** Every cow in the herd by how like the track's looks it is, most alike first. */
    fun rank(looks: List<Looked>): List<Candidate> {
        val out = ArrayList<Candidate>(herd.size)
        if (looks.isEmpty()) return out
        val seen = looks.map { seen(it) }
        for (cow in herd.cows) {
            if (cow.views.isEmpty()) continue
            var sum = 0.0
            for (e in seen) sum += cow.similarity(e)
            out.add(Candidate(cow.id, sum / seen.size))
        }
        out.sortByDescending { it.score }
        return out
    }

    /**
     * The cow a track's looks are most like, and how far it stands out from the next most alike.
     * `taken`: what it looks most like is a cow that is in view on another track.
     */
    private class Pick(val best: Candidate?, val cow: Cow?, val gap: Double, val twin: Boolean, val taken: Boolean)

    private fun pick(tr: Track, bar: Bars): Pick {
        val all = rank(tr.looks)
        tr.best = all.getOrNull(0)
        tr.second = all.getOrNull(1)
        // A cow can't be in two places: cows named on other tracks in view now are out, and so is any
        // entry that looks like the same animal as one of those (a cow learnt twice).
        val held = ArrayList<Cow>()
        for (o in tracker.tracks) if (o !== tr && lastT - o.lastT < 800) o.cowId?.let { id -> herd[id]?.let { held.add(it) } }
        var b: Candidate? = null
        var top: Cow? = null
        var rival: Candidate? = null
        var twin = false
        var taken = false
        for (c in all) {
            val cow = herd[c.cowId] ?: continue
            if (held.any { it === cow }) {
                if (b == null && c.score >= bar.match) taken = true
                continue
            }
            if (b == null) {
                if (c.score >= bar.match && held.any { HerdCheck.alike(it, cow) >= bar.twin }) {
                    taken = true
                    continue
                }
                b = c
                top = cow
                if (c.score < bar.match) break
            } else {
                // The runner-up that counts is the next cow that isn't the same animal learnt twice.
                if (c.score >= bar.match && HerdCheck.alike(top!!, cow) >= bar.twin) {
                    twin = true
                    continue
                }
                rival = c
                break
            }
        }
        val gap = if (b == null) 0.0 else b.score - (rival?.score ?: (bar.match - bar.margin))
        return Pick(b, top, gap, twin, taken)
    }

    /**
     * Decides who a track is, if its looks are enough to go on: a cow in the herd, a new cow, or (with
     * learning off) not one of the herd. `last`: the cow has gone, so it's now or never. Returns whether
     * it was named.
     */
    private fun resolve(tr: Track, now: Long, last: Boolean): Boolean {
        val n = tr.looks.size
        if (n == 0) return false
        val bar = bars()
        val p = pick(tr, bar)
        val b = p.best
        if (b != null && p.cow != null && b.score >= bar.match) {
            val clear = p.gap >= bar.margin
            val sure = p.gap >= 2 * bar.margin
            if (clear && (n >= opt.minLooks || sure || last)) {
                assign(tr, p.cow, b.score, p.twin, now, bar)
                return true
            }
            // Not clear of the runner-up: more looks may settle it. If they don't, it's learnt as a new
            // cow below (a cow learnt twice can be merged; two cows under one name is worse).
        }
        // What it looks most like is a cow standing somewhere else in view: so it isn't that cow, but
        // nor can the app tell it from that cow. It isn't learnt (two entries that can't be told apart
        // are no use); it still counts as a cow in view.
        tr.taken = p.taken
        if (p.taken) return false
        val best = b?.score ?: -1.0
        val enough = (best < bar.fresh && n >= opt.newLooks) || n >= opt.maxLooks || (last && n >= 2)
        if (!enough) return false
        if (!counting) return false
        // At a gate only cows that go through are learnt, not ones standing about behind it.
        if (session.gate && tr.crossings.isEmpty()) return false
        if (!opt.learn) {
            if (!tr.unknown) {
                tr.unknown = true
                tr.looks.clear()
            }
            return false
        }
        create(tr, now)
        return true
    }

    private fun assign(tr: Track, cow: Cow, score: Double, unsure: Boolean, now: Long, bar: Bars) {
        tr.cowId = cow.id
        tr.created = false
        tr.unknown = false
        tr.unsure = unsure
        tr.matchScore = score
        tr.agree = score
        tr.agreeN = 1
        // Looks that match on their own add to what's known of the cow (a slightly different angle or light).
        if (counting) for (lk in tr.looks) if (cow.similarity(seen(lk)) >= bar.match) herd.addView(cow, lk.emb, lk.quality, now, lk.px, lk.aspect)
        tr.looks.clear()
        tr.mixed = false
    }

    private fun create(tr: Track, now: Long) {
        val cow = herd.create(now)
        // The best picture first, then the looks least like the ones already chosen.
        val pool = tr.looks.sortedByDescending { it.quality }.toMutableList()
        val chosen = ArrayList<Looked>()
        chosen.add(pool.removeAt(0))
        while (chosen.size < opt.firstViews && pool.isNotEmpty()) {
            var bi = 0
            var bs = 2.0
            for ((i, c) in pool.withIndex()) {
                var m = -1.0
                for (p in chosen) m = max(m, dot(c.emb, p.emb))
                if (m < bs) {
                    bs = m
                    bi = i
                }
            }
            chosen.add(pool.removeAt(bi))
        }
        for (lk in chosen) herd.addView(cow, lk.emb, lk.quality, now, lk.px, lk.aspect, dup = 0.985)
        tr.cowId = cow.id
        tr.created = true
        tr.unknown = false
        tr.unsure = false
        tr.matchScore = 1.0
        tr.agree = 1.0
        tr.agreeN = 1
        tr.looks.clear()
        tr.mixed = false
        session.fresh.add(cow.id)
    }

    /**
     * A later look at a named cow. A cow followed without a break is the same animal however it looks
     * now, so its new look is added to what's known of it: that is how the app comes to know a cow from
     * both sides and with its head up or down. Only a track that has been in among other animals (or was
     * lost for a moment) can have slipped onto another cow; such a track is renamed if its looks come to
     * be clearly another cow's. Otherwise it keeps its name.
     */
    private fun check(tr: Track, cow: Cow, look: Look, emb: FloatArray, now: Long) {
        val bar = bars()
        val s = cow.similarity(herd.see(emb))
        tr.agreeN++
        tr.agree = tr.agree * 0.6 + s * 0.4
        if (s >= bar.match) {
            tr.mixed = false
            tr.looks.clear()
        }
        if (!tr.mixed) {
            if (counting) herd.addView(cow, emb, look.quality, now, look.px, look.aspect)
            return
        }
        if (s >= bar.fresh) {
            tr.looks.clear()
            return
        }
        // Mixed up with other animals and no longer looking like its name: is it clearly another cow?
        tr.looks.add(Looked(emb, look.px, look.aspect, look.quality, look.t))
        while (tr.looks.size > opt.maxLooks) tr.looks.removeAt(0)
        if (tr.looks.size < opt.minLooks) return
        val p = pick(tr, bar)
        val b = p.best ?: return
        if (p.cow != null && p.cow !== cow && b.score >= bar.match && p.gap >= bar.margin) {
            tr.counted = false
            assign(tr, p.cow, b.score, p.twin, now, bar)
        }
    }

    // ---------------------------------------------------------------- counting

    /** Field count: a named cow in view is counted, once. */
    private fun register(tr: Track, now: Long, saved: MutableList<Sighting>) {
        if (tr.counted || tr.hits < 2) return
        val id = tr.cowId
        if (id == null) {
            if (tr.unknown) {
                tr.counted = true
                session.unknown++
                val s = Sighting(null, now, 0.0, false, 0, tr.id)
                session.sightings.add(s)
                saved.add(s)
            }
            return
        }
        tr.counted = true
        if (session.seen.containsKey(id)) return
        val s = Sighting(id, now, tr.matchScore, tr.created, 0, tr.id)
        session.seen[id] = s
        session.sightings.add(s)
        saved.add(s)
        herd[id]?.let { herd.sawNow(it, now) }
    }

    /**
     * Gate count: has this cow gone from clearly one side of the line to clearly the other? "Clearly":
     * a cow standing on the line with its box wobbling isn't counted back and forth.
     */
    private fun crossing(tr: Track, line: DoubleArray, aspect: Double, t: Double): Boolean {
        val (d, along) = Gate.where(line, foot(tr.box), aspect)
        val band = max(0.012, 0.15 * (tr.box[2] - tr.box[0]))
        val side = if (d > band) 1 else if (d < -band) -1 else 0
        if (side == 0) return false
        val was = tr.side
        tr.side = side
        if (was == side) return false
        if (was == 0) {
            // First time clearly on a side: it has crossed only if it was first seen on the other one
            // (a cow first spotted right by the line).
            val d0 = Gate.where(line, tr.firstFoot, aspect).first
            val origin = if (d0 > 0.002) 1 else if (d0 < -0.002) -1 else 0
            if (origin == 0 || origin == side) return false
        }
        // Past the end of the line isn't through the gate.
        if (along < -0.1 || along > 1.1) return false
        if (!counting || !session.gate) return false
        val dir = if (side == Gate.sideOfDirection1(line, aspect)) 1 else 2
        tr.crossings.add(Crossed(t, dir, tr.box))
        session.tally[0]++
        session.tally[dir]++
        flash[tr.id] = t to dir
        return true
    }

    /** A track that's over: last chance to name it, and its gate crossings are saved. */
    private fun finish(tr: Track, now: Long, saved: MutableList<Sighting>, named: MutableList<Track>, ending: Boolean) {
        if (tr.cowId == null && !tr.unknown && tr.looks.isNotEmpty() && counting) {
            if (resolve(tr, now, last = true)) named.add(tr)
        }
        if (!counting) return
        if (session.gate) {
            if (tr.crossings.isEmpty()) return
            val cow = tr.cowId?.let { herd[it] }
            for (c in tr.crossings) {
                val s = Sighting(cow?.id, offset + c.t.toLong(), tr.matchScore, tr.created, c.dir, tr.id)
                session.sightings.add(s)
                saved.add(s)
            }
            if (cow != null) herd.sawNow(cow, now) else session.unknown += tr.crossings.size
            tr.crossings.clear()
        } else {
            register(tr, now, saved)
        }
    }

    // ---------------------------------------------------------------- for the screen

    /** The cows in view (seen within `fresh` ms), as the screen shows them. */
    fun shown(t: Double = lastT, freshMs: Double = 500.0): List<Shown> = synchronized(herd) {
        fun name(c: Candidate?) = c?.let { herd[it.cowId]?.label } ?: ""
        tracker.tracks.filter { t - it.lastT < freshMs && it.hits >= 2 }.map { tr ->
            val cow = tr.cowId?.let { herd[it] }
            val state = when {
                cow != null && tr.created -> "new"
                cow != null -> "named"
                tr.unknown -> "unknown"
                tr.looks.isNotEmpty() || tr.waiting > 0 -> "looking"
                tr.skipped.isNotEmpty() -> "far"
                else -> "looking"
            }
            val f = flash[tr.id]
            Shown(
                tr.id, tr.box, state, cow?.id, cow?.label ?: "", if (cow != null) tr.matchScore else (tr.best?.score ?: 0.0), tr.unsure,
                tr.counted || tr.crossings.isNotEmpty(), if (f != null && t - f.first < 900) f.second else 0,
                tr.hits, tr.looks.size + (if (cow != null) tr.sampled else 0), tr.waiting, tr.skipped,
                name(tr.best), tr.best?.score ?: 0.0, name(tr.second), tr.second?.score ?: 0.0, tr.agree, tr.vx, tr.vy,
            )
        }
    }

    /** Gate: where each recognised cow went last (1 or 2), from crossings so far (cows still in view included). */
    fun lastDirections(): Map<Int, Int> = synchronized(herd) {
        val out = LinkedHashMap<Int, Int>()
        for (s in session.sightings.sortedBy { it.t }) if (s.cowId != null && s.dir != 0) out[s.cowId] = s.dir
        for (tr in tracker.tracks) {
            val id = tr.cowId ?: continue
            tr.crossings.lastOrNull()?.let { out[id] = it.dir }
        }
        out
    }
}
