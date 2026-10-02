package io.github.ndev.flockeyes.core.count

import io.github.ndev.flockeyes.core.image.Frame
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far the scene slid in the picture since the last frame: `dx` as a fraction of its width, `dy` of its
 * height. `sure`: one fit stood out. `lost`: the phone swung further than is looked for, so where it now
 * points is anyone's guess.
 */
class Slide(val dx: Double, val dy: Double, val sure: Boolean, val lost: Boolean = false)

/**
 * Works out how the phone is being turned from the pictures themselves: a small grey copy of each frame is
 * slid over an earlier one to find where they fit best. With that, a cow keeps one place in the field
 * however the phone pans (see [Scan]): the field count goes by places, so a cow isn't counted again when
 * the phone comes back to it, and cows too close together to tell apart are still counted one by one.
 *
 * - A phone held still: the frame fits the one before it best exactly where it is. Then nothing has moved,
 *   whatever the cows are doing, and nothing is added up.
 * - A phone being turned: each frame is fitted to the same earlier frame (the "key") until the picture has
 *   slid a good way from it, and only then does a new frame become the key. So the small error of each fit
 *   isn't added up frame after frame, and a frame too blurred to fit costs nothing, because the next one is
 *   fitted to the same key. The fit is looked for coarsely first (in a half-size copy), up to `reach` of the
 *   picture's width from where the last frame fitted (a quarter: far faster than anyone pans while
 *   counting), and then finely around the best coarse fit.
 * - A frame that doesn't fit the key (the cows in it have moved about too much since) is fitted to the frame
 *   before it instead, and becomes the key.
 * - A frame that fits nothing, and after it one that fits it but not the key: the phone has been swung
 *   somewhere else and the thread is lost.
 */
class PanEstimator(private val long: Int = 96, private val short: Int = 54, private val reach: Double = 0.25) {
    /** A small grey copy of a frame (less its average, so the light changing isn't taken for movement), and one half the size. */
    private class Small(n: Int) {
        val full = IntArray(n)
        val half = IntArray(n / 4)
    }

    private val copies = Array(3) { Small(long * short) }
    private var key = copies[0]
    private var prev = copies[0]
    private val argb = IntArray(long * short * FINE * FINE)
    private var w = long
    private var h = short
    private var haveKey = false
    private var upright = false

    /** Where the last frame fitted on the key frame (pixels of the small copy), and whether it fitted nothing (1) or something (0). */
    private var atX = 0.0
    private var atY = 0.0
    private var misses = 0

    fun reset() {
        haveKey = false
    }

    private fun makeKey(s: Small) {
        key = s
        haveKey = true
        atX = 0.0
        atY = 0.0
        misses = 0
    }

    /** How unlike two copies are when a point at (x, y) in `a` is at (x + sx, y + sy) in `b`. */
    private fun cost(a: IntArray, b: IntArray, w: Int, h: Int, sx: Int, sy: Int, step: Int): Double {
        val x0 = maxOf(0, -sx)
        val x1 = minOf(w, w - sx)
        val y0 = maxOf(0, -sy)
        val y1 = minOf(h, h - sy)
        if (x1 - x0 < w / 3 || y1 - y0 < h / 3) return Double.MAX_VALUE
        var s = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val ra = y * w
            val rb = (y + sy) * w + sx
            var x = x0
            while (x < x1) {
                s += abs(a[ra + x] - b[rb + x])
                x += step
                n++
            }
            y += step
        }
        return s.toDouble() / n
    }

    /**
     * Where between pixels a fit is best, from the costs either side of the best one: the costs rise in a V
     * from the best fit (they are sums of differences, not of squares), so it lies where two lines of equal
     * and opposite slope through the three costs meet.
     */
    private fun between(lo: Double, mid: Double, hi: Double): Double {
        if (lo == Double.MAX_VALUE || hi == Double.MAX_VALUE) return 0.0
        val d = maxOf(lo, hi) - mid
        return if (d <= 1e-9) 0.0 else (0.5 * (lo - hi) / d).coerceIn(-0.5, 0.5)
    }

    /**
     * The best fit of `b` on `a`, looked for within `reach` of (cx, cy): where (pixels of the full copy),
     * whether it stands out from the rest (not a blank sky, not everything moving), whether it is as far
     * as was looked, and how unlike the two are on average over all that was tried.
     */
    private class Fit(val x: Double, val y: Double, val stands: Boolean, val edge: Boolean, val average: Double)

    private fun fit(a: Small, b: Small, cx: Int, cy: Int): Fit {
        val sw = w / 2
        val sh = h / 2
        val r = (reach * sw).roundToInt().coerceAtLeast(2)
        var best = Double.MAX_VALUE
        var bx = cx
        var by = cy
        var total = 0.0
        var n = 0
        for (sy in cy - r..cy + r) for (sx in cx - r..cx + r) {
            val c = cost(a.half, b.half, sw, sh, sx, sy, 1)
            if (c == Double.MAX_VALUE) continue
            total += c
            n++
            if (c < best) {
                best = c
                bx = sx
                by = sy
            }
        }
        val average = if (n > 0) total / n else 0.0
        val stands = average > BLANK && best < 0.6 * average
        val edge = abs(bx - cx) >= r || abs(by - cy) >= r
        if (!stands || edge) return Fit(0.0, 0.0, stands, edge, average)
        // Finely, around the best coarse fit.
        var fine = Double.MAX_VALUE
        var fx = 2 * bx
        var fy = 2 * by
        for (sy in 2 * by - 2..2 * by + 2) for (sx in 2 * bx - 2..2 * bx + 2) {
            val c = cost(a.full, b.full, w, h, sx, sy, 2)
            if (c < fine) {
                fine = c
                fx = sx
                fy = sy
            }
        }
        val x = fx + between(cost(a.full, b.full, w, h, fx - 1, fy, 2), fine, cost(a.full, b.full, w, h, fx + 1, fy, 2))
        val y = fy + between(cost(a.full, b.full, w, h, fx, fy - 1, 2), fine, cost(a.full, b.full, w, h, fx, fy + 1, 2))
        return Fit(x, y, stands = true, edge = false, average = average)
    }

    /** Whether `b` fits `a` best exactly where it is, with the fit clearly worse a pixel either way: nothing has moved. */
    private fun still(a: Small, b: Small): Boolean {
        val c = cost(a.full, b.full, w, h, 0, 0, 1)
        val l = cost(a.full, b.full, w, h, -1, 0, 1)
        val r = cost(a.full, b.full, w, h, 1, 0, 1)
        val u = cost(a.full, b.full, w, h, 0, -1, 1)
        val d = cost(a.full, b.full, w, h, 0, 1, 1)
        // Something to see: a blank picture fits as well anywhere.
        if (minOf(minOf(l, r), minOf(u, d)) < 1.25 * c + 0.3) return false
        return abs(between(l, c, r)) < STILL && abs(between(u, c, d)) < STILL
    }

    /** The slide since the last frame given (zero, and not sure, for the first). */
    fun update(frame: Frame): Slide {
        // The small copy lies the way the picture does.
        val tall = frame.height > frame.width
        if (tall != upright) {
            upright = tall
            haveKey = false
        }
        w = if (tall) short else long
        h = if (tall) long else short
        val cur = copies.first { it !== key && it !== prev }
        // The copy is made in two steps: the frame scaled to a few times the copy's size, then each
        // square of those pixels averaged into one. Scaled straight down, a pixel of the copy would be
        // one or two of the frame's picked out from hundreds, and fine grass would come out as noise
        // that changes with the slightest shake of the hand.
        val fw = w * FINE
        frame.pixels(0.0, 0.0, frame.width.toDouble(), frame.height.toDouble(), fw, h * FINE, argb)
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            var g = 0
            for (j in 0 until FINE) {
                val row = (y * FINE + j) * fw + x * FINE
                for (i in 0 until FINE) {
                    val p = argb[row + i]
                    // Grey, weighted as the eye sees the three colours.
                    g += ((p shr 16) and 0xff) * 77 + ((p shr 8) and 0xff) * 150 + (p and 0xff) * 29
                }
            }
            g = g / (FINE * FINE) shr 8
            cur.full[y * w + x] = g
            sum += g
        }
        val mean = (sum / cur.full.size).toInt()
        for (i in cur.full.indices) cur.full[i] -= mean
        val sw = w / 2
        val sh = h / 2
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = 2 * y * w + 2 * x
            cur.half[y * sw + x] = (cur.full[i] + cur.full[i + 1] + cur.full[i + w] + cur.full[i + w + 1]) / 4
        }
        val last = prev
        prev = cur
        if (!haveKey) {
            makeKey(cur)
            return Slide(0.0, 0.0, sure = false)
        }

        // The frame before this one fitted nothing (though there was plenty to see in it).
        val disturbed = misses > 0
        misses = 0

        // Held still: the frame fits the one before it exactly where it is. Nothing is added up, and the
        // key is this frame, so that cows wandering about don't come to look like the phone turning.
        if (!disturbed && still(last, cur)) {
            makeKey(cur)
            return Slide(0.0, 0.0, sure = true)
        }

        // Turning: fitted to the key frame, around where the last frame fitted.
        val k = fit(key, cur, (atX / 2).roundToInt(), (atY / 2).roundToInt())
        if (k.stands && !k.edge) {
            val dx = (k.x - atX) / w
            val dy = (k.y - atY) / h
            atX = k.x
            atY = k.y
            // Slid a good way from the key frame: less and less of the two overlap, so this frame is the key now.
            if (abs(k.x) > KEY * w || abs(k.y) > KEY * h) makeKey(cur)
            return Slide(dx, dy, sure = true)
        }
        // It doesn't fit the key (too much in it has changed since): then the frame before it.
        val p = if (last === key) k else fit(last, cur, 0, 0)
        if (p.stands && !p.edge) {
            makeKey(cur)
            // After a frame that fitted nothing, a frame that fits it but not the key: the picture is of
            // somewhere else now, and how the phone got there wasn't seen.
            return if (disturbed) Slide(0.0, 0.0, sure = false, lost = true) else Slide(p.x / w, p.y / h, sure = true)
        }
        if (k.stands && k.edge || p.stands && p.edge) {
            // The best fit is as far as was looked: the phone swung faster than that.
            makeKey(cur)
            return Slide(0.0, 0.0, sure = false, lost = true)
        }
        // Too blank to fit (mist, bare ground): nothing to go on, and nothing to say the phone has moved.
        // Plenty to see but no fit: a hand across the lens, or the phone on its way somewhere else. The
        // next frame tells which: it fits the key again, or it doesn't.
        if (disturbed || maxOf(k.average, p.average) > BLANK) misses = 1
        return Slide(0.0, 0.0, sure = false)
    }

    private companion object {
        /** How far the picture may slide from the key frame (of its width or height) before a new key is taken. */
        const val KEY = 0.085

        /** How unlike two copies must be on average, over all the ways they were laid on each other, for there to be anything to see. */
        const val BLANK = 2.0

        /** A slide of less than this (pixels of the small copy) between one frame and the next is the phone held still. */
        const val STILL = 0.12

        /** The frame is scaled to this many times the small copy's size, and squares of that many pixels each way averaged. */
        const val FINE = 4
    }
}
