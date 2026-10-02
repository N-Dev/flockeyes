package io.github.ndev.flockeyes.core

import io.github.ndev.flockeyes.core.image.Frame
import io.github.ndev.flockeyes.core.net.Net
import io.github.ndev.flockeyes.core.net.Tensor
import io.github.ndev.flockeyes.core.reid.ReidConfig
import io.github.ndev.flockeyes.core.reid.Tuning
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** The repository root: the models and the test photos. */
object Repo {
    val root: File by lazy {
        System.getProperty("flockeyes.repo")?.let { return@lazy File(it) }
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "models/cows-tiny.onnx").exists()) d = d.parentFile
        d ?: error("Can't find the repository (models/cows-tiny.onnx)")
    }

    fun file(path: String) = File(root, path)

    /** The test photos (tests/assets/cows): three of each of a few cows, with what the Python tools made of them. */
    val photos: List<Photo> by lazy {
        val f = file("tests/assets/cows/expected.json")
        if (!f.exists()) return@lazy emptyList()
        @Suppress("UNCHECKED_CAST")
        val list = Json.obj(f.readText())["photos"] as List<Map<String, Any?>>
        list.map { m ->
            @Suppress("UNCHECKED_CAST")
            Photo(
                m["file"] as String, (m["cow"] as Double).toInt(), (m["box"] as List<Double>).toDoubleArray(),
                (m["embedding"] as? List<Double>)?.map { it.toFloat() }?.toFloatArray(),
            )
        }
    }

    /** A picture of one cow cut from a video (tests/assets/singles): "cow_a1" and "cow_a2" are two of cow a. */
    fun single(name: String): BufferedImage = ImageIO.read(file("tests/assets/singles/$name.jpg"))

    /** A video's frames in order (tests/assets/clips). */
    fun clip(name: String): List<File> = file("tests/assets/clips/$name").listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name }

    /** The tuning that goes with the recognition model (models/cow-tuning.bin), if it's there. */
    val tuning: Tuning? by lazy {
        val name = reidConfig.tuning
        if (name.isEmpty() || !file("models/$name").exists()) null else Tuning.read(file("models/$name").readBytes())
    }

    /** The model the expected answers were worked out with. */
    val photosModel: String by lazy {
        val f = file("tests/assets/cows/expected.json")
        if (f.exists()) Json.obj(f.readText())["model"] as? String ?: "" else ""
    }

    /** The real recognition model if it's in the repository (the Models workflow puts it there), else the stand-in. */
    val realReid: Boolean get() = file("models/cow-reid.onnx").exists() && file("models/cow-reid.json").exists()
    val reidPath: String get() = if (realReid) "models/cow-reid.onnx" else "tests/models/pixel-embedder.onnx"
    val reidConfig: ReidConfig
        get() = if (realReid) ReidConfig.parse(file("models/cow-reid.json").readText()) else ReidConfig(key = "pixel", match = 0.93, fresh = 0.85)
}

class Photo(val file: String, val cow: Int, val box: DoubleArray, val embedding: FloatArray?) {
    val image: BufferedImage by lazy { ImageIO.read(Repo.file("tests/assets/cows/$file")) }
}

/** A desktop image as a [Frame], scaled with bilinear filtering like the phone does. */
class AwtFrame(val img: BufferedImage) : Frame {
    override val width = img.width
    override val height = img.height

    override fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray {
        val dst = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val sx = outW / w
        val sy = outH / h
        g.drawImage(img, AffineTransform(sx, 0.0, 0.0, sy, -x * sx, -y * sy), null)
        g.dispose()
        dst.getRGB(0, 0, outW, outH, out, 0, outW)
        return out
    }
}

/** A frame with nothing in it, for tests of the logic that never look at the picture. */
class BlankFrame(override val width: Int = 1280, override val height: Int = 720) : Frame {
    override fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray = out
}

/**
 * Loads a model: with ONNX Runtime (as on GitHub, and in the app), or through tools/bridge.py when
 * -Dflockeyes.bridge=http://127.0.0.1:PORT is set (for machines without ONNX Runtime for Java).
 */
object Nets {
    private val cache = HashMap<String, Net>()

    fun load(path: String): Net = cache.getOrPut(path) {
        val bytes = Repo.file(path).readBytes()
        val bridge = System.getProperty("flockeyes.bridge")
        if (bridge != null) BridgeNet(bridge, bytes)
        else Class.forName("io.github.ndev.flockeyes.core.net.OrtNet").getMethod("fromBytes", ByteArray::class.java).invoke(null, bytes) as Net
    }
}

/** Runs a model in tools/bridge.py, which uses Python ONNX Runtime. */
class BridgeNet(private val base: String, model: ByteArray) : Net {
    private val sid: String
    override val inputNames: List<String>
    override val outputNames: List<String>

    init {
        val info = Json.obj(String(post("/load", model)))
        sid = info["sid"] as String
        @Suppress("UNCHECKED_CAST")
        inputNames = info["inputNames"] as List<String>
        @Suppress("UNCHECKED_CAST")
        outputNames = info["outputNames"] as List<String>
    }

    private fun post(path: String, body: ByteArray): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { it.write(body) }
        if (c.responseCode != 200) error("bridge ${c.responseCode}: ${c.errorStream?.readBytes()?.let { String(it) }}")
        return c.inputStream.use { it.readBytes() }
    }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val feeds = ArrayList<Map<String, Any?>>()
        val bufs = ByteArrayOutputStream()
        for ((name, t) in inputs) {
            val fl = t.floats
            val b = if (fl != null) {
                val bb = ByteBuffer.allocate(fl.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.asFloatBuffer().put(fl)
                bb.array()
            } else t.bytes!!
            feeds.add(mapOf("name" to name, "type" to if (fl != null) "float32" else "uint8", "dims" to t.shape.toList(), "byteLength" to b.size))
            bufs.write(b)
        }
        val header = Json.write(mapOf("feeds" to feeds)).toByteArray()
        val body = ByteArrayOutputStream()
        body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header.size).array())
        body.write(header)
        body.write(bufs.toByteArray())
        val res = post("/run?sid=$sid", body.toByteArray())
        val hl = ByteBuffer.wrap(res, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        @Suppress("UNCHECKED_CAST")
        val outs = Json.obj(String(res, 4, hl))["outputs"] as List<Map<String, Any?>>
        var o = 4 + hl
        val result = LinkedHashMap<String, Tensor>()
        for (m in outs) {
            val n = (m["byteLength"] as Double).toInt()
            @Suppress("UNCHECKED_CAST")
            val dims = (m["dims"] as List<Double>).map { it.toLong() }.toLongArray()
            val data = FloatArray(n / 4).also { ByteBuffer.wrap(res, o, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            result[m["name"] as String] = Tensor.floats(data, *dims)
            o += n
        }
        return result
    }

    override fun close() {}
}
