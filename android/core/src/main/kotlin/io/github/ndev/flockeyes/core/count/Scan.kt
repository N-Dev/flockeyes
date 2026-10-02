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
import io.github.ndev.flockeyes.core.track.Place
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

/** How much a box must overlap a place in the field (0 to 1) to be the cow that stands there. */
private const val PLACE_FIT = 0.25

/** How much of a box cut off by the side of the picture must lie in a place to be the cow that stands there. */
private const val PART_FIT = 0.6

/** How near the side of the picture (of its width) a box must come to count as cut off by it. */
private const val SIDE = 0.01

/** How long a cow must have been seen at a place (ms) for the place to count without a name. */
private const val PLACE_SURE = 600.0

/** How long a cow that is being looked at has to be named (ms) before its place counts without a name. */
private const val PLACE_NAMING = 3000.0

/** How long a place must have been in the picture with no cow on it (ms) for a cow turning up close by to be the one that stood there. */
private const val PLACE_EMPTY = 1500.0

/** How long the picture takes to settle after the zoom is changed (ms). */
private const val ZOOM_SETTLE = 700.0

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

    /** Field: cows told apart by where they stand (each place counts once, and a named cow once however many places it has stood). */
    var placed = 0

    /** Gate: crossings so far, including cows still in view: [total, direction 1, direction 2]. */
    val tally = IntArray(3)

    /** Everything counted and finished with, to be saved. */
    val sightings = ArrayList<Sighting>()

    /** Field: cows told apart, by their looks or by where they stand. It can't be fewer than were in view at once. */
    val count: Int get() = if (gate) tally[0] else max(max(seen.size + unknown, peak), placed)
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

/** A place a cow was counted at, as the screen shows it in debug mode: see [Scan.placesInPicture]. */
class PlaceShown(val id: Int, val box: DoubleArray, val label: String, val counts: Boolean)

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

    // ------------------------------------------------------------ where the cows stand (field count)
    /**
     * Each cow's place in the field as the phone pans. A field count goes by these: a cow isn't counted
     * again when the phone comes back to it or its box is lost and found, and cows standing too close
     * together to be told apart by their looks are still counted one by one.
     */
    val places = ArrayList<Place>()
    private val panner = PanEstimator()

    /** How the picture slid since the last frame; tests give their own. */
    var slide: ((Frame) -> Slide)? = null

    /** Where the phone points now, from where it pointed when the count began (picture widths and heights at no zoom). */
    var panX = 0.0
        private set
    var panY = 0.0
        private set
    private var lap = 0
    private var nextPlace = 1

    /** Times the phone was swung round too fast to follow during this count. */
    var swings = 0
        private set

    /** The camera's zoom (1 = none): a cow's place doesn't change when the picture is zoomed. */
    var zoom = 1.0
        set(value) {
            val v = value.coerceAtLeast(0.1)
            if (v != field) {
                field = v
                // The picture jumps: don't take that for the phone turning.
                panner.reset()
                zoomed = true
            }
        }
    private var zoomed = false
    private var steadyAt = 0.0

    /** When the last frame was, and the last in which places were looked over. */
    private var placedT = -1.0
    private var emptyT = -1.0
    private val parts = HashSet<Int>()
    private val several = HashSet<Int>()
    private val overlapped = HashSet<Int>()
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
        places.clear()
        panner.reset()
        panX = 0.0
        panY = 0.0
        lap = 0
        swings = 0
        steadyAt = 0.0
        placedT = -1.0
        emptyT = -1.0
        for (tr in tracker.tracks) {
            // A cow learnt before this count began isn't new in it.
            tr.created = false
            tr.place = null
            tr.notPlace = null
            // A cow already named is at its place from the first frame.
            tr.namePlace = tr.cowId != null
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
        // Places from before can't be found by where they are in a picture that's a different shape.
        panner.reset()
        lap++
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
        // A box round two or more others that between them fill most of it is several cows boxed as one:
        // they are counted by their own boxes, and a picture of it would be of none of them.
        several.clear()
        for (b in live) {
            if (b.id in parts) continue
            var n = 0
            var filled = 0.0
            for (a in live) {
                if (a === b || a.id in parts || area(a.box) >= area(b.box)) continue
                val inside = CowDetector.inside(a.box, b.box)
                if (inside > 0.7) {
                    n++
                    filled += inside * area(a.box)
                }
            }
            if (n >= 2 && filled > 0.55 * area(b.box)) several.add(b.id)
        }
        // Cows overlapping each other: a picture of one would have some of the other in it, and the box
        // following one may slip onto the other. Cows standing right up against each other: no looks either.
        overlapped.clear()
        close.clear()
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]
            val b = live[j]
            if (a.id in parts || b.id in parts || a.id in several || b.id in several) continue
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
            inView.addLast(live.count { it.hits >= 3 && it.topScore >= 0.5 && it.id !in parts && it.id !in several })
            if (inView.size > 5) inView.removeFirst()
            if (inView.size == 5) {
                val mid = inView.sorted()[2]
                if (mid > session.peak) session.peak = mid
            }
            for (tr in live) register(tr, now, saved)
            place(live, frame, t)
        }

        // Which cows get a look this frame: unnamed ones first (those with fewest looks), then named ones
        // not checked for longest.
        val want = live.filter { wantsLook(it, t) }
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

    /** Why a picture of this cow, as it is in this frame, would be no good to recognise it by ("" if it would do). */
    private fun unfit(tr: Track): String {
        val b = tr.box
        val e = 0.008
        return when {
            tr.cut -> "cut"
            tr.score < opt.minScore -> "unsure"
            b[0] <= e || b[1] <= e || b[2] >= 1 - e || b[3] >= 1 - e -> "edge"
            min((b[2] - b[0]) * frameW, (b[3] - b[1]) * frameH) < opt.minSide -> "small"
            tr.id in parts -> "part"
            tr.id in several -> "several"
            tr.id in overlapped -> "overlap"
            tr.id in close -> "close"
            else -> ""
        }
    }

    private fun wantsLook(tr: Track, t: Double): Boolean {
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
        tr.skipped = unfit(tr)
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
        named(tr)
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
        named(tr)
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
            named(tr)
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
            // The box slipped from one cow onto another: the cow it was on is still where it was, and
            // this one's place is somewhere near (the box that was on it before, most likely).
            tr.notPlace = tr.place
            tr.place = null
            assign(tr, p.cow, b.score, p.twin, now, bar)
        }
    }

    // ---------------------------------------------------------------- counting

    /**
     * Field count: gives each cow in view its place in the field, and counts the places.
     *
     * Boxes go to places by where they are, not by which track they belong to: among cows standing in a
     * heap the boxes swap about and merge, and a box that has slipped from one cow to its neighbour must
     * take the neighbour's place, not make a new one. So there are as many places in a spot as there
     * were ever boxes there at once.
     */
    private fun place(live: List<Track>, frame: Frame, t: Double) {
        if (zoomed) {
            zoomed = false
            steadyAt = t + ZOOM_SETTLE
        }
        val s = slide?.invoke(frame) ?: panner.update(frame)
        if (s.sure && !s.lost) {
            // The scene sliding left in the picture is the phone turning right.
            panX -= s.dx / zoom
            panY -= s.dy / zoom
        } else {
            // Nothing in the picture to go by (mist, bare ground), or it has changed past following: go by
            // the cows themselves, which move far less from frame to frame than a turning phone slides
            // them. Two or more must agree: one box on its own may be a cow walking, or a box changing shape.
            val dx = ArrayList<Double>()
            val dy = ArrayList<Double>()
            for (tr in live) {
                val was = tr.prev ?: continue
                if (tr.hits < 3 || was.t != placedT || tr.id in parts || tr.id in several) continue
                val a = was.box
                val b = tr.box
                // Not ones part out of the picture: their boxes grow and shrink as it slides.
                if (minOf(a[0], b[0], a[1], b[1]) <= SIDE || maxOf(a[2], b[2], a[3], b[3]) >= 1 - SIDE) continue
                dx.add((b[0] + b[2] - a[0] - a[2]) / 2)
                dy.add((b[1] + b[3] - a[1] - a[3]) / 2)
            }
            var agree = 0
            if (dx.size >= 2) {
                val mx = middle(dx)
                val my = middle(dy)
                for (i in dx.indices) if (kotlin.math.abs(dx[i] - mx) < 0.015 && kotlin.math.abs(dy[i] - my) < 0.015) agree++
                if (agree >= 2 && 2 * agree >= dx.size) {
                    panX -= mx / zoom
                    panY -= my / zoom
                } else {
                    agree = 0
                }
            }
            if (s.lost && agree == 0) {
                // Swung round too fast to follow, and no cows followed through it to say where to: start
                // again from here. What was counted stays counted, and a cow still being followed is where
                // its box is now.
                lap++
                swings++
                for (tr in live) {
                    val p = tr.place ?: continue
                    if (p.lap == lap - 1 && tr.box[0] > SIDE && tr.box[2] < 1 - SIDE) {
                        p.lap = lap
                        moveTo(p, tr)
                    }
                }
            }
        }
        placedT = t
        // Just zoomed: the frames take a moment to catch up, and until they do boxes are in the wrong places.
        if (t < steadyAt) return
        // Only cows followed for long enough to be sure there is one.
        val cows = ArrayList<Track>()
        for (tr in live) {
            if (tr.hits >= 4 && tr.topScore >= 0.55 && tr.id !in parts && tr.id !in several) cows.add(tr) else tr.place = null
        }
        // A box cut off by the side of the picture is part of a cow coming into view or leaving it: it can
        // hold the cow's place, but it doesn't say where the cow is or how big, and it makes no new place.
        val part = HashSet<Int>()
        for (tr in cows) if (tr.box[0] <= SIDE || tr.box[2] >= 1 - SIDE) part.add(tr.id)
        class Fit(val tr: Track, val p: Place, val by: Double)
        val fits = ArrayList<Fit>()
        for (tr in cows) {
            val b = worldBox(tr)
            for (p in places) {
                if (p.lap != lap || p === tr.notPlace) continue
                val pb = p.box()
                val by = if (tr.id in part) CowDetector.inside(b, pb) * PLACE_FIT / PART_FIT else iou(b, pb)
                if (by >= PLACE_FIT) fits.add(Fit(tr, p, by))
            }
        }
        // The best fits first: a cow that hasn't moved has its own place back before a neighbour's box can take it.
        fits.sortByDescending { it.by }
        val taken = HashSet<Place>()
        val placed = HashSet<Int>()
        for (f in fits) {
            if (f.p in taken || f.tr.id in placed) continue
            taken.add(f.p)
            placed.add(f.tr.id)
            f.tr.place = f.p
        }
        // A whole cow with no place: before it is taken for one not seen before, is there a place close
        // by that has been in the picture for a while with no cow on it? Then the cow that stood there has
        // moved a little (or the picture has: a step to one side, a zoom the app wasn't told of), and this is it.
        for (tr in cows) {
            if (tr.id in placed || tr.id in part) continue
            val b = worldBox(tr)
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            var best: Place? = null
            var near = Double.MAX_VALUE
            for (p in places) {
                if (p.lap != lap || p in taken || p === tr.notPlace || p.emptyMs < PLACE_EMPTY) continue
                val dx = kotlin.math.abs(p.x - (b[0] + b[2]) / 2) / max(w, p.w)
                val dy = kotlin.math.abs(p.y - (b[1] + b[3]) / 2) / max(h, p.h)
                val ratio = (w * h) / max(1e-9, p.w * p.h)
                if (dx > 1.0 || dy > 1.0 || ratio < 0.4 || ratio > 2.5) continue
                val d = dx * dx + dy * dy
                if (d < near) {
                    near = d
                    best = p
                }
            }
            if (best != null) {
                taken.add(best)
                placed.add(tr.id)
                tr.place = best
            }
        }
        for (tr in cows) {
            val whole = tr.id !in part
            if (tr.id !in placed) {
                if (!whole) {
                    tr.place = null
                    continue
                }
                val b = worldBox(tr)
                tr.place = Place(nextPlace++, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2, b[2] - b[0], b[3] - b[1], lap).also {
                    it.firstT = t
                    places.add(it)
                }
            }
            val p = tr.place!!
            if (whole) moveTo(p, tr)
            // Time between frames counts up to a point: a gap is the cow out of sight, not seen.
            if (p.hits > 0) p.seenMs += min(t - p.lastT, 300.0)
            p.lastT = t
            p.hits++
            p.emptyMs = 0.0
            // A cow that may yet be named by its looks: it is being looked at, and good to look at.
            p.naming = tr.cowId == null && !tr.unknown && !tr.taken && tr.looks.size + tr.waiting < opt.maxLooks && unfit(tr).isEmpty()
            if (tr.namePlace && whole) {
                tr.namePlace = false
                name(p, tr)
            }
        }
        // Places in the picture with no cow on them: how long for.
        val dt = if (emptyT < 0) 0.0 else min(t - emptyT, 300.0)
        emptyT = t
        for (p in places) {
            if (p in taken) continue
            p.naming = false
            if (p.lap != lap) continue
            val x0 = (p.x - p.w / 2 - panX) * zoom + 0.5
            val x1 = (p.x + p.w / 2 - panX) * zoom + 0.5
            val y0 = (p.y - p.h / 2 - panY) * zoom + 0.5
            val y1 = (p.y + p.h / 2 - panY) * zoom + 0.5
            // Well inside the picture: a cow there would be seen whole.
            if (x0 > 0.03 && x1 < 0.97 && y0 > 0.0 && y1 < 1.0) p.emptyMs += dt
        }
        // Two places with a cow on each at once are two cows, whatever they are called: the name is kept
        // where the cow goes by it (or else where it was given last), and the other place counts by itself.
        val at = HashMap<Int, Track>()
        for (tr in cows) {
            val p = tr.place ?: continue
            val id = p.cowId ?: continue
            if (tr.id in part) continue
            val other = at[id]
            if (other == null) {
                at[id] = tr
                continue
            }
            val q = other.place!!
            val mine = tr.cowId == id
            val theirs = other.cowId == id
            if (if (mine != theirs) mine else p.since > q.since) {
                unname(q)
                at[id] = tr
            } else {
                unname(p)
            }
        }
        // A named cow counts once however many places it has stood in. A place with no name counts once a
        // cow has been seen there for long enough that it isn't a box flickering; and if that cow is being
        // looked at, once it has had time to be named: it may be one counted already, that has moved.
        val who = HashSet<Int>()
        var unnamed = 0
        for (p in places) {
            val id = p.cowId
            if (id != null && herd[id] != null) who.add(id)
            else if (p.seenMs >= PLACE_SURE && !(p.naming && t - p.firstT < PLACE_NAMING)) unnamed++
        }
        who.addAll(session.seen.keys)
        session.placed = who.size + unnamed
    }

    /** The middle value of a list (the mean of the middle two when there's an even number). */
    private fun middle(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** A track's box in the field: as it would be in the picture had the phone not been turned or zoomed since the count began. */
    private fun worldBox(tr: Track): DoubleArray {
        val b = tr.box
        return doubleArrayOf(panX + (b[0] - 0.5) / zoom, panY + (b[1] - 0.5) / zoom, panX + (b[2] - 0.5) / zoom, panY + (b[3] - 0.5) / zoom)
    }

    private fun moveTo(p: Place, tr: Track) {
        val b = worldBox(tr)
        p.x = (b[0] + b[2]) / 2
        p.y = (b[1] + b[3]) / 2
        p.w = b[2] - b[0]
        p.h = b[3] - b[1]
    }

    /**
     * A look has settled who a track is (it was named, or a later look agreed with its name): the place
     * it stands on is that cow's. A named box that merely slides onto a place doesn't name it: among cows
     * standing close the boxes slip from one cow to the next.
     */
    private fun named(tr: Track) {
        val p = tr.place
        if (p != null) name(p, tr) else tr.namePlace = true
    }

    private fun name(p: Place, tr: Track) {
        val id = tr.cowId ?: return
        // Already found out not to be that cow, however alike they look.
        if (p.notCow == id) return
        if (p.cowId != id) {
            p.cowId = id
            p.since = lastT
        }
        // The cow is found again where it stood before: it was never at the places it was "seen" at in
        // between, so the cows there are others that look like it. They count by their places.
        for (q in places) if (q !== p && q.cowId == id && q.since > p.since) unname(q)
    }

    /** The cow at this place isn't the one it was taken for: the place counts by itself from now on. */
    private fun unname(p: Place) {
        p.notCow = p.cowId
        p.cowId = null
    }

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

    /**
     * The places cows have been counted at that are in or near the picture now, as boxes in the picture
     * (for debug mode): which cow was named there, and whether the place counts yet.
     */
    fun placesInPicture(): List<PlaceShown> = synchronized(herd) {
        val out = ArrayList<PlaceShown>()
        for (p in places) {
            if (p.lap != lap) continue
            val b = p.box()
            val box = doubleArrayOf((b[0] - panX) * zoom + 0.5, (b[1] - panY) * zoom + 0.5, (b[2] - panX) * zoom + 0.5, (b[3] - panY) * zoom + 0.5)
            if (box[2] < 0 || box[0] > 1 || box[3] < 0 || box[1] > 1) continue
            val cow = p.cowId?.let { herd[it] }
            out.add(PlaceShown(p.id, box, cow?.label ?: "", cow != null || (p.seenMs >= PLACE_SURE && !(p.naming && lastT - p.firstT < PLACE_NAMING))))
        }
        out
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
