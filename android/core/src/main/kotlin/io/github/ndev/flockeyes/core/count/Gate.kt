package io.github.ndev.flockeyes.core.count

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A line drawn across a gap, gate or the way out of the parlour (x1, y1, x2, y2 as fractions of the
 * frame). Cows are counted as they cross it, by direction. `aspect` everywhere is the frame's height / width.
 */
object Gate {
    /** An upright line down the middle: for cows walking across the picture. */
    fun default(): DoubleArray = doubleArrayOf(0.5, 0.2, 0.5, 0.95)

    /** A level line: for cows walking towards or away from the camera. */
    fun level(): DoubleArray = doubleArrayOf(0.15, 0.6, 0.85, 0.6)

    /** Whether the line lies more level than upright (in pixels). */
    fun isLevel(line: DoubleArray, aspect: Double): Boolean = abs(line[2] - line[0]) > abs(line[3] - line[1]) * aspect

    /** Names for the two directions: direction 1 is left to right (or towards the camera for a level line). */
    fun directionNames(line: DoubleArray, aspect: Double): Pair<String, String> =
        if (isLevel(line, aspect)) "Towards me" to "Away from me" else "Left to right" to "Right to left"

    private fun cross2(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx

    /**
     * How far point p is from the line, in frame widths: positive on one side, negative on the other.
     * Also (second value) how far along the line its nearest point is: 0 at one end, 1 at the other.
     */
    fun where(line: DoubleArray, p: DoubleArray, aspect: Double): Pair<Double, Double> {
        val dx = line[2] - line[0]
        val dy = (line[3] - line[1]) * aspect
        val px = p[0] - line[0]
        val py = (p[1] - line[1]) * aspect
        val len = hypot(dx, dy)
        if (len == 0.0) return 0.0 to 0.0
        return cross2(dx, dy, px, py) / len to (px * dx + py * dy) / (len * len)
    }

    /** The side ([where] > 0 is +1) that counts as "after" for direction 1: right of an upright line, below a level one. */
    fun sideOfDirection1(line: DoubleArray, aspect: Double): Int {
        val mx = (line[0] + line[2]) / 2
        val my = (line[1] + line[3]) / 2
        val probe = if (isLevel(line, aspect)) doubleArrayOf(mx, my + 0.1) else doubleArrayOf(mx + 0.1, my)
        return if (where(line, probe, aspect).first > 0) 1 else -1
    }

    /**
     * The part of the frame worth analysing at a gate (x, y, w, h): around the line, with room either side
     * for cows to be picked up before they reach it and recognised as they pass.
     */
    fun region(line: DoubleArray, aspect: Double): DoubleArray {
        val x0: Double
        val x1: Double
        val y0: Double
        val y1: Double
        if (isLevel(line, aspect)) {
            x0 = max(0.0, min(line[0], line[2]) - 0.1)
            x1 = min(1.0, max(line[0], line[2]) + 0.1)
            val cy = (line[1] + line[3]) / 2
            // Cows stand taller than the line is: more room above it than below.
            y0 = max(0.0, cy - 0.5)
            y1 = min(1.0, cy + 0.35)
        } else {
            val cx = (line[0] + line[2]) / 2
            x0 = max(0.0, cx - 0.5)
            x1 = min(1.0, cx + 0.5)
            y0 = max(0.0, min(line[1], line[3]) - 0.25)
            y1 = min(1.0, max(line[1], line[3]) + 0.05)
        }
        return doubleArrayOf(x0, y0, max(0.2, x1 - x0), max(0.2, y1 - y0))
    }
}
