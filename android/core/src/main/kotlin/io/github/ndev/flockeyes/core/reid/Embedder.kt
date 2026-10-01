package io.github.ndev.flockeyes.core.reid

import io.github.ndev.flockeyes.core.Json
import io.github.ndev.flockeyes.core.image.Frame
import io.github.ndev.flockeyes.core.net.Net
import io.github.ndev.flockeyes.core.net.Tensor
import kotlin.math.sqrt

fun dot(a: FloatArray, b: FloatArray): Double {
    var s = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) s += a[i] * b[i]
    return s.toDouble()
}

/** Scales a vector to length 1 in place (so a dot product of two is their cosine similarity). */
fun normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat()
    if (n > 0f) for (i in v.indices) v[i] = v[i] / n
    return v
}

/**
 * How the recognition model is fed and how its answers are judged (models/cow-reid.json, written when the
 * model is prepared and measured).
 *
 * `match`: two looks at least this alike are taken for the same cow. `fresh`: a cow less like every known
 * cow than this is new. Between the two the app waits for more looks.
 */
class ReidConfig(
    val key: String = "none",
    val size: Int = 224,
    val mean: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f),
    val std: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f),
    val dim: Int = 768,
    val match: Double = 0.5,
    val fresh: Double = 0.4,
    val margin: Double = 0.04,
    val name: String = "",
    val licence: String = "",
    val notes: String = "",
) {
    companion object {
        fun parse(json: String): ReidConfig {
            val m = Json.obj(json)
            fun num(k: String, d: Double) = (m[k] as? Number)?.toDouble() ?: d
            fun arr(k: String, d: FloatArray) = (m[k] as? List<*>)?.map { (it as Number).toFloat() }?.toFloatArray() ?: d
            val match = num("match", 0.5)
            return ReidConfig(
                key = m["key"] as? String ?: "reid",
                size = num("size", 224.0).toInt(),
                mean = arr("mean", floatArrayOf(0.5f, 0.5f, 0.5f)),
                std = arr("std", floatArrayOf(0.5f, 0.5f, 0.5f)),
                dim = num("dim", 768.0).toInt(),
                match = match,
                fresh = num("fresh", match - 0.1),
                margin = num("margin", 0.04),
                name = m["name"] as? String ?: "",
                licence = m["licence"] as? String ?: "",
                notes = m["notes"] as? String ?: "",
            )
        }
    }
}

/**
 * Turns a picture of a cow into a vector that is close to other pictures of the same cow and far from
 * pictures of other cows (a re-identification model: MegaDescriptor).
 */
class Embedder(val cfg: ReidConfig) {
    private val n = cfg.size * cfg.size
    private val input = FloatArray(3 * n)

    /** The box (fractions of the frame) cut out and squashed to the model's square, as ARGB. */
    fun crop(frame: Frame, box: DoubleArray): IntArray {
        val x = box[0] * frame.width
        val y = box[1] * frame.height
        val w = maxOf(1.0, (box[2] - box[0]) * frame.width)
        val h = maxOf(1.0, (box[3] - box[1]) * frame.height)
        return frame.pixels(x, y, w, h, cfg.size, cfg.size, IntArray(n))
    }

    /** The model's answer for a picture from [crop], scaled to length 1. One call at a time. */
    @Synchronized
    fun embed(net: Net, px: IntArray): FloatArray {
        val m = cfg.mean
        val s = cfg.std
        for (i in 0 until n) {
            val p = px[i]
            input[i] = (((p shr 16) and 0xff) / 255f - m[0]) / s[0]
            input[i + n] = (((p shr 8) and 0xff) / 255f - m[1]) / s[1]
            input[i + 2 * n] = ((p and 0xff) / 255f - m[2]) / s[2]
        }
        val out = net.run(mapOf(net.inputNames[0] to Tensor.floats(input, 1, 3, cfg.size.toLong(), cfg.size.toLong())))
        val o = out[net.outputNames[0]] ?: out.values.first()
        return normalize(o.floats!!.copyOf())
    }
}
