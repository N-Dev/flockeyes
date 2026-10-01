package io.github.ndev.flockeyes.core.detect

import io.github.ndev.flockeyes.core.image.Frame
import io.github.ndev.flockeyes.core.image.iou
import io.github.ndev.flockeyes.core.image.jsRound
import io.github.ndev.flockeyes.core.net.Net
import io.github.ndev.flockeyes.core.net.Tensor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * An animal found in one frame: what the finder called it, how sure it is that it's a cow, and its box as
 * fractions of the frame (x1, y1, x2, y2). `cut`: the box ran into the edge of the area that was analysed.
 */
class Det(val cls: String, val score: Double, val box: DoubleArray, val cut: Boolean = false)

/** The cows found in one frame, with how long each step took (ms) and how many areas were analysed. */
class DetFrame(
    val dets: List<Det>,
    val width: Int,
    val height: Int,
    val tiles: Int,
    val msPrep: Double,
    val msInfer: Double,
    val msPost: Double,
) {
    val msTotal: Double get() = msPrep + msInfer + msPost
}

/**
 * Finds cows with YOLOX (trained on COCO, which has a "cow" class). The frame can be analysed whole, or
 * in two overlapping halves ("far" detail): the model looks at 416 x 416 pixels at a time, so halves show
 * it distant cows 1.7 times bigger. A cow lying across the join is put back together.
 */
class CowDetector(private val size: Int = 416) {
    private val input = FloatArray(3 * size * size)
    private var px = IntArray(0)
    private val grid: FloatArray = buildGrid(size)

    companion object {
        const val COW = 19

        /** Animals a black-and-white cow gets mistaken for from odd angles. */
        val LOOKALIKES = intArrayOf(17, 18) // horse, sheep

        val COCO = arrayOf(
            "person", "bicycle", "car", "motorbike", "aeroplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant",
            "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
            "cake", "chair", "sofa", "pot plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "mobile phone", "microwave", "oven", "toaster", "sink", "fridge", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush",
        )

        /** Anchor grid for YOLOX's three output scales (strides 8, 16, 32): x, y, stride per row. */
        private fun buildGrid(size: Int): FloatArray {
            val rows = ArrayList<Float>()
            for (s in intArrayOf(8, 16, 32)) {
                val g = size / s
                for (y in 0 until g) for (x in 0 until g) {
                    rows.add(x.toFloat())
                    rows.add(y.toFloat())
                    rows.add(s.toFloat())
                }
            }
            return rows.toFloatArray()
        }

        /** How much of `d` lies inside `k` (0 to 1). */
        fun inside(d: DoubleArray, k: DoubleArray): Double {
            val w = min(d[2], k[2]) - max(d[0], k[0])
            val h = min(d[3], k[3]) - max(d[1], k[1])
            val a = (d[2] - d[0]) * (d[3] - d[1])
            return if (w > 0 && h > 0 && a > 0) (w * h) / a else 0.0
        }

        /** Keeps the best of overlapping boxes: the model often puts a second, smaller box inside a cow. */
        fun nms(dets: List<Det>, thr: Double = 0.5): List<Det> {
            val keep = ArrayList<Det>()
            // A box cut off by the edge of a half loses to a whole one of the same cow.
            for (d in dets.sortedByDescending { if (it.cut) it.score * 0.6 else it.score }) {
                if (keep.none { iou(it.box, d.box) > thr || inside(d.box, it.box) > 0.7 }) keep.add(d)
            }
            return keep
        }

        /**
         * The areas to analyse (fractions x, y, w, h): the whole frame, or for `far` detail two overlapping
         * halves, side by side for a wide frame and one above the other for a tall one.
         */
        fun tiles(aspect: Double, far: Boolean): List<DoubleArray> {
            if (!far) return listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0))
            return if (aspect <= 1.0) listOf(doubleArrayOf(0.0, 0.0, 0.6, 1.0), doubleArrayOf(0.4, 0.0, 0.6, 1.0))
            else listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.6), doubleArrayOf(0.0, 0.4, 1.0, 0.6))
        }

        /**
         * Two pieces of one cow lying across the join of two areas, put back together: both are cut off,
         * they touch or overlap, and they line up the other way.
         */
        fun stitch(dets: List<Det>, vertical: Boolean): List<Det> {
            val cut = dets.filter { it.cut }
            if (cut.size < 2) return dets
            val used = HashSet<Det>()
            val out = ArrayList<Det>()
            for (i in cut.indices) {
                val a = cut[i]
                if (a in used) continue
                var best: Det? = null
                var bestScore = 0.0
                for (j in i + 1 until cut.size) {
                    val b = cut[j]
                    if (b in used) continue
                    // Along the join: they must meet. Across it: they must cover much the same range.
                    val (a0, a1, b0, b1) = if (vertical) listOf(a.box[1], a.box[3], b.box[1], b.box[3]) else listOf(a.box[0], a.box[2], b.box[0], b.box[2])
                    val (c0, c1, d0, d1) = if (vertical) listOf(a.box[0], a.box[2], b.box[0], b.box[2]) else listOf(a.box[1], a.box[3], b.box[1], b.box[3])
                    val meet = min(a1, b1) - max(a0, b0)
                    if (meet < -0.01) continue
                    // One whole inside the other is the same cow seen twice, not two pieces.
                    if (meet >= 0.9 * min(a1 - a0, b1 - b0)) continue
                    val across = (min(c1, d1) - max(c0, d0)) / max(1e-9, max(c1, d1) - min(c0, d0))
                    if (across < 0.6) continue
                    if (across > bestScore) {
                        bestScore = across
                        best = b
                    }
                }
                val b = best ?: continue
                used.add(a)
                used.add(b)
                out.add(
                    Det(
                        if (a.score >= b.score) a.cls else b.cls, max(a.score, b.score),
                        doubleArrayOf(min(a.box[0], b.box[0]), min(a.box[1], b.box[1]), max(a.box[2], b.box[2]), max(a.box[3], b.box[3])),
                    ),
                )
            }
            if (out.isEmpty()) return dets
            return dets.filter { it !in used } + out
        }
    }

    /**
     * Cows in the frame scoring at least `conf`. `rois` are the areas to analyse (see [tiles]); boxes come
     * back as fractions of the whole frame. With `lookalikes`, something the model calls a horse or a sheep
     * counts as a cow too (it's a field of cows).
     */
    fun detect(frame: Frame, net: Net, rois: List<DoubleArray>, conf: Double = 0.3, lookalikes: Boolean = true): DetFrame {
        var prep = 0.0
        var infer = 0.0
        var post = 0.0
        val found = ArrayList<Det>()
        for (roi in rois) {
            val t0 = System.nanoTime()
            val fw = frame.width
            val fh = frame.height
            val sx = jsRound(roi[0] * fw).toInt()
            val sy = jsRound(roi[1] * fh).toInt()
            val sw = max(1, min(fw - sx, jsRound(roi[2] * fw).toInt()))
            val sh = max(1, min(fh - sy, jsRound(roi[3] * fh).toInt()))
            val scale = min(size.toDouble() / sw, size.toDouble() / sh)
            val nw = min(size, max(1, jsRound(sw * scale).toInt()))
            val nh = min(size, max(1, jsRound(sh * scale).toInt()))
            if (px.size < nw * nh) px = IntArray(nw * nh)
            frame.pixels(sx.toDouble(), sy.toDouble(), sw.toDouble(), sh.toDouble(), nw, nh, px)
            // As YOLOX was trained: the picture in the top-left corner, grey (114) padding, BGR, 0-255.
            val n = size * size
            java.util.Arrays.fill(input, 114f)
            for (y in 0 until nh) {
                val row = y * size
                for (x in 0 until nw) {
                    val p = px[y * nw + x]
                    val i = row + x
                    input[i] = (p and 0xff).toFloat()
                    input[i + n] = ((p shr 8) and 0xff).toFloat()
                    input[i + 2 * n] = ((p shr 16) and 0xff).toFloat()
                }
            }
            val t1 = System.nanoTime()
            val out = net.run(mapOf(net.inputNames[0] to Tensor.floats(input, 1, 3, size.toLong(), size.toLong())))
            val o = out[net.outputNames[0]] ?: out.values.first()
            val t2 = System.nanoTime()
            val d = o.floats!!
            val rows = o.shape[1].toInt()
            val cols = o.shape[2].toInt()
            val rx = nw.toDouble() / sw
            val ry = nh.toDouble() / sh
            // The area's inner edges (not the frame's own): a box reaching one is cut off there.
            val innerL = sx > 0
            val innerT = sy > 0
            val innerR = sx + sw < fw
            val innerB = sy + sh < fh
            val edge = 3.0 // pixels of the model's input
            for (i in 0 until rows) {
                val b = i * cols
                val obj = d[b + 4]
                if (obj < conf) continue
                var cow = d[b + 5 + COW]
                var best = 0f
                var bi = -1
                for (c in 0 until cols - 5) {
                    val v = d[b + 5 + c]
                    if (v > best) {
                        best = v
                        bi = c
                    }
                }
                if (lookalikes) for (c in LOOKALIKES) cow = max(cow, d[b + 5 + c])
                val score = (obj * cow).toDouble()
                if (score < conf) continue
                val stride = grid[i * 3 + 2]
                val cx = (d[b] + grid[i * 3]) * stride
                val cy = (d[b + 1] + grid[i * 3 + 1]) * stride
                val bw = exp(d[b + 2].toDouble()) * stride
                val bh = exp(d[b + 3].toDouble()) * stride
                val l = cx - bw / 2
                val t = cy - bh / 2
                val r = cx + bw / 2
                val bt = cy + bh / 2
                val cut = (innerL && l <= edge) || (innerT && t <= edge) || (innerR && r >= nw - edge) || (innerB && bt >= nh - edge)
                // Model input -> frame pixels -> fractions of the frame, kept inside the analysed area.
                val x1 = max(sx.toDouble(), l / rx + sx) / fw
                val y1 = max(sy.toDouble(), t / ry + sy) / fh
                val x2 = min((sx + sw).toDouble(), r / rx + sx) / fw
                val y2 = min((sy + sh).toDouble(), bt / ry + sy) / fh
                if (x2 - x1 < 0.004 || y2 - y1 < 0.004) continue
                found.add(Det(if (bi >= 0 && bi < COCO.size) COCO[bi] else "?", score, doubleArrayOf(x1, y1, x2, y2), cut))
            }
            val t3 = System.nanoTime()
            prep += (t1 - t0) / 1e6
            infer += (t2 - t1) / 1e6
            post += (t3 - t2) / 1e6
        }
        val t4 = System.nanoTime()
        // Whole boxes first (a piece of a cow that's whole in the other half goes), then the pieces left
        // over are put together, then once more.
        var dets: List<Det> = nms(nmsWithin(found))
        if (rois.size > 1) {
            val vertical = rois[0][3] < 0.99
            dets = nms(stitch(dets, vertical))
        }
        post += (System.nanoTime() - t4) / 1e6
        return DetFrame(dets, frame.width, frame.height, rois.size, prep, infer, post)
    }

    /** First pass: near-identical boxes of one cow (the model gives several) down to the best. */
    private fun nmsWithin(dets: List<Det>): List<Det> {
        val keep = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.none { it.cut == d.cut && iou(it.box, d.box) > 0.6 }) keep.add(d)
        }
        return keep
    }
}
