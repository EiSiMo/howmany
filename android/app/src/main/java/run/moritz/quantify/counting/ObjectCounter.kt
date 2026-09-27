package run.moritz.quantify.counting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** The GeCo2 model in the app assets, exported by the benchmark. */
const val MODEL_ASSET = "geco2-int8.onnx"

// ImageNet normalization, as GeCo2's backbone was trained with.
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** What the model found in an image: the objects, and where it saw anything like them. */
class Scan(val detections: List<Detection>, val heatmap: Heatmap)

/**
 * Finds every object in an image that looks like the given examples, with GeCo2 (the benchmark's
 * prototype 4) running on the CPU.
 */
class ObjectCounter internal constructor(model: File, options: OrtSession.SessionOptions) :
    AutoCloseable {
    constructor(
        model: File,
        threads: Int = 4,
    ) : this(
        model,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            // Planning all activations up front costs ~1 GB more peak memory, and Android
            // throttles apps above 3 GB. On a Pixel 8 Pro it is also no faster.
            setMemoryPatternOptimization(false)
        },
    )

    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(model.path, options)

    /**
     * Returns one detection per object like the [exemplars] inside [region], with its box in image
     * pixels, in reading order: row by row from the top, each row from left to right, and the
     * heatmap they come from. Exemplars and region are image boxes; the model sees only the region,
     * at a higher resolution the smaller it is. Confidences are relative to the best detection in
     * the region.
     */
    fun detect(
        image: Bitmap,
        exemplars: List<Box>,
        region: Box = Box(0f, 0f, image.width.toFloat(), image.height.toFloat()),
    ): Scan {
        require(exemplars.isNotEmpty()) { "At least one exemplar is needed" }
        val left = floor(region.left).toInt().coerceIn(0, image.width - 1)
        val top = floor(region.top).toInt().coerceIn(0, image.height - 1)
        val right = ceil(region.right).toInt().coerceIn(left + 1, image.width)
        val bottom = ceil(region.bottom).toInt().coerceIn(top + 1, image.height)
        val cropped = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val x = left.toFloat()
        val y = top.toFloat()
        val scan = detectInWhole(cropped, exemplars.map { it.translated(-x, -y) })
        return Scan(
            scan.detections.map { it.copy(box = it.box.translated(x, y)) },
            scan.heatmap.translated(x, y),
        )
    }

    private fun detectInWhole(image: Bitmap, exemplars: List<Box>): Scan {
        val scale = inputScale(image.width, image.height, exemplars)
        val boxes =
            exemplars.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.map { it * scale }
        OnnxTensor.createTensor(environment, pixels(image, scale), IMAGE_SHAPE).use { imageTensor ->
            OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(boxes.toFloatArray()),
                    longArrayOf(1, exemplars.size.toLong(), 4),
                )
                .use { exemplarTensor ->
                    session.run(mapOf("image" to imageTensor, "exemplars" to exemplarTensor)).use {
                        result ->
                        val objectness = result.floats("objectness")
                        val output =
                            ModelOutput(
                                gridSize = objectness.shape[1].toInt(),
                                objectness = objectness.values,
                                offsets = result.floats("offsets").values,
                            )
                        return Scan(
                            decodeDetections(output, scale, image.width, image.height),
                            decodeHeatmap(output, scale),
                        )
                    }
                }
        }
    }

    override fun close() = session.close()

    private class Floats(val shape: LongArray, val values: FloatArray)

    private fun OrtSession.Result.floats(name: String): Floats {
        val tensor =
            get(name).orElseThrow { IllegalStateException("Model has no output $name") }
                as OnnxTensor
        val buffer = tensor.floatBuffer
        return Floats(tensor.info.shape, FloatArray(buffer.remaining()).also { buffer.get(it) })
    }

    private companion object {
        val IMAGE_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

        /** Scales the image, pads it to the input size and normalizes it, channels first. */
        fun pixels(image: Bitmap, scale: Float): FloatBuffer {
            val width = (image.width * scale).toInt()
            val height = (image.height * scale).toInt()
            val colors = IntArray(width * height)
            Bitmap.createScaledBitmap(image, width, height, true)
                .getPixels(colors, 0, width, 0, 0, width, height)
            val plane = INPUT_SIZE * INPUT_SIZE
            val values = FloatArray(3 * plane)
            for (channel in 0..2) {
                values.fill(-MEAN[channel] / STD[channel], channel * plane, (channel + 1) * plane)
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = colors[y * width + x]
                    val index = y * INPUT_SIZE + x
                    for (channel in 0..2) {
                        val value = (color shr (16 - 8 * channel) and 0xff) / 255f
                        values[channel * plane + index] = (value - MEAN[channel]) / STD[channel]
                    }
                }
            }
            return FloatBuffer.wrap(values)
        }
    }
}
