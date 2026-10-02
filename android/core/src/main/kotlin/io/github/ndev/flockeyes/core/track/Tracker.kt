package io.github.ndev.flockeyes.core.track

import io.github.ndev.flockeyes.core.detect.Det
import io.github.ndev.flockeyes.core.image.area
import io.github.ndev.flockeyes.core.image.iou
import kotlin.math.hypot
import kotlin.math.max

class Obs(val t: Double, val box: DoubleArray)

/**
 * A look at a tracked cow: its picture (ARGB, the model's square, squashed from a box `aspect` = height /
 * width), what the recognition model made of it, and how good a picture it was (0 to 1).
 */
class Looked(val emb: FloatArray, val px: IntArray?, val aspect: Double, val quality: Double, val t: Double) {
    /** The description as the herd's tuning saw it, and which tuning that was (see Herd.see). */
    var tuned: FloatArray? = null
    var tuneVersion = -1
}

/** A possible name for a tracked cow: a cow in the herd and how alike they look (0 to 1). */
class Candidate(val cowId: Int, val score: Double)

/** A cow crossing the gate line: when, which way (1 or 2), and where it was. */
class Crossed(val t: Double, val dir: Int, val box: DoubleArray)

/** One cow followed from frame to frame, with what's known about who it is. */
class Track internal constructor(val id: Int, t: Double, box: DoubleArray, score: Double) {
    var hits = 0
    val firstT = t
    var lastT = t
    var box: DoubleArray = box
    var score: Double = score

    /** Its box ran into the edge of the analysed area at the last sighting (only part of the cow). */
    var cut = false
    val obs = ArrayList<Obs>()
    var prev: Obs? = null
    var vx = 0.0
    var vy = 0.0
    val first: DoubleArray = centre(box)

    /** Where it stood when it was first seen (for the gate: which side of the line it came from). */
    val firstFoot: DoubleArray = foot(box)

    // ------------------------------------------------------------ who it is
    /** Looks of this cow gathered while it's unnamed. */
    val looks = ArrayList<Looked>()

    /** The cow in the herd this is, once decided. */
    var cowId: Int? = null

    /** This track is how that cow was learnt (it was new). */
    var created = false

    /** Decided: not a cow in the herd (with learning off). */
    var unknown = false

    /** Named although two cows looked about as likely. */
    var unsure = false

    /** How alike it looked when it was named. */
    var matchScore = 0.0

    /** The best and second-best names at the last look (for debug mode). */
    var best: Candidate? = null
    var second: Candidate? = null

    /** When its looks were last sampled, and how many samples are still being worked out. */
    var lastSampleT = -1e12
    var waiting = 0

    /** Total samples taken. */
    var sampled = 0

    /** Never overlapped another animal: its samples are certainly all of the one cow. */
    var clean = true

    /**
     * It has been in among other animals (or lost for a moment) since its looks last agreed with its
     * name: the track may have slipped onto another cow. While this is false the track has been followed
     * without a break, so it is the same animal however it looks now.
     */
    var mixed = false

    /** Not named because what it looks most like is a cow in view on another track (see Scan.resolve). */
    var taken = false

    /** The best score the cow finder has given it, and its box's shape (height / width) at the last look. */
    var topScore = score
    var lookShape = 0.0

    /** Why no sample was taken at the last look (for debug mode): "edge", "small", "overlap", "cut", or "". */
    var skipped = ""

    /** Recent samples of a named cow against that cow: a running average, and how many. */
    var agree = 0.0
    var agreeN = 0

    /** A small picture of it (ARGB, `thumbSize` square, squashed from a box `thumbAspect` = height / width) and how good it is. */
    var thumb: IntArray? = null
    var thumbAspect = 1.0
    var thumbQuality = 0.0

    // ------------------------------------------------------------ counting
    /** Counted in the field count. */
    var counted = false

    /** Each time it crossed the gate line. */
    val crossings = ArrayList<Crossed>()

    /** Which side of the gate line it was last surely on (+1, -1; 0 before it's known). */
    var side = 0
}

internal fun centre(b: DoubleArray) = doubleArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)

/** Where a cow touches the ground: the bottom middle of its box. A gate line is drawn on the ground. */
fun foot(b: DoubleArray) = doubleArrayOf((b[0] + b[2]) / 2, b[3])

/**
 * Follows cows from frame to frame: each new box continues the track it overlaps most (allowing for how
 * the track was moving), or starts a new one. `aspect` is the frame's height / width.
 */
class Tracker(var aspect: Double = 9.0 / 16, private val maxAge: Double = 1500.0, private val iouMin: Double = 0.1) {
    val tracks = ArrayList<Track>()
    private var nextId = 1

    private fun predict(tr: Track, t: Double): DoubleArray {
        // Only a short way ahead: a cow that stops shouldn't have its box sail on.
        val dt = minOf(t - tr.lastT, 400.0)
        return doubleArrayOf(tr.box[0] + tr.vx * dt, tr.box[1] + tr.vy * dt, tr.box[2] + tr.vx * dt, tr.box[3] + tr.vy * dt)
    }

    class Update(val seen: List<Track>, val lost: List<Track>)

    fun update(dets: List<Det>, t: Double): Update {
        val pairs = ArrayList<Triple<Double, Int, Int>>()
        val preds = tracks.map { predict(it, t) }
        for ((i, tr) in tracks.withIndex()) {
            val p = preds[i]
            val pc = centre(p)
            val size = max(p[2] - p[0], (p[3] - p[1]) * aspect)
            // With one sighting there's no speed to predict from yet, so look further.
            val reach = (if (tr.hits < 2) 1.2 else 0.6) * size
            for ((j, d) in dets.withIndex()) {
                var s = iou(p, d.box)
                if (s < iouMin) {
                    // Moved a lot (the phone swung round) or briefly hidden: accept a box near where the
                    // track should be, of similar size.
                    val dc = centre(d.box)
                    val dist = hypot(dc[0] - pc[0], (dc[1] - pc[1]) * aspect)
                    val ratio = area(d.box) / max(1e-9, area(p))
                    if (dist > reach || ratio < 0.4 || ratio > 2.5) continue
                    s = 0.001 * (1 - dist / reach)
                }
                pairs.add(Triple(s, i, j))
            }
        }
        pairs.sortByDescending { it.first }
        val usedT = HashSet<Int>()
        val usedD = HashSet<Int>()
        val seen = ArrayList<Track>()
        for ((_, i, j) in pairs) {
            if (i in usedT || j in usedD) continue
            usedT.add(i)
            usedD.add(j)
            observe(tracks[i], dets[j], t)
            seen.add(tracks[i])
        }
        val existing = tracks.size
        for ((j, d) in dets.withIndex()) {
            if (j in usedD) continue
            val tr = Track(nextId++, t, d.box, d.score)
            observe(tr, d, t)
            tracks.add(tr)
            seen.add(tr)
        }
        val lost = ArrayList<Track>()
        val keep = ArrayList<Track>()
        for ((i, tr) in tracks.withIndex()) {
            if (i >= existing || i in usedT || tr.lastT == t) {
                keep.add(tr)
                continue
            }
            if (t - tr.lastT > maxAge) lost.add(tr) else keep.add(tr)
        }
        tracks.clear()
        tracks.addAll(keep)
        return Update(seen, lost)
    }

    /** Ends every track (at the end of a count) and returns them. */
    fun flush(): List<Track> {
        val all = tracks.toList()
        tracks.clear()
        return all
    }

    private fun observe(tr: Track, d: Det, t: Double) {
        tr.prev = tr.obs.lastOrNull()
        tr.obs.add(Obs(t, d.box))
        if (tr.obs.size > 60) tr.obs.removeAt(0)
        // Missed for a moment and picked up again: it may not be the same animal.
        if (tr.hits > 0 && t - tr.lastT > 700) tr.mixed = true
        tr.box = d.box
        tr.score = d.score
        if (d.score > tr.topScore) tr.topScore = d.score
        tr.cut = d.cut
        tr.lastT = t
        tr.hits++
        // Speed of the box over roughly the last half second (steadier than frame to frame).
        var k = tr.obs.size - 1
        while (k > 0 && t - tr.obs[k - 1].t <= 600) k--
        val o = tr.obs[k]
        if (o.t < t) {
            val c0 = centre(o.box)
            val c1 = centre(d.box)
            tr.vx = (c1[0] - c0[0]) / (t - o.t)
            tr.vy = (c1[1] - c0[1]) / (t - o.t)
        }
    }
}
