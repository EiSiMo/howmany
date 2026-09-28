package run.moritz.howmany.counting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import run.moritz.howmany.BuildConfig

private const val TAG = "ObjectCounter"
// The model runs its operators on this many threads.
private const val THREADS = 4
// ImageNet normalization, as GeCo2's backbone was trained with.
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** What the model found in an image: the objects, and where it saw anything like them. */
class CountResult(val detections: List<Detection>, val heatmap: Heatmap)

/**
 * Finds every object in an image that looks like the given exemplars, with GeCo2 running on the
 * CPU. Mirrors the Python reference counter in model/counter.py.
 */
class ObjectCounter private constructor(model: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session =
        environment.createSession(
            model.path,
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                // Planning all activations up front costs ~1 GB more peak memory, and Android
                // throttles apps above 3 GB. On a Pixel 8 Pro it is also no faster.
                setMemoryPatternOptimization(false)
            },
        )
    // Closing frees the session only once no detection runs on it anymore, as freeing it under a
    // running one crashes the app natively.
    private val lock = Any()
    private var running = 0
    private var closed = false

    /**
     * Returns one detection per object like the [exemplars] inside [crop], with its box in image
     * pixels, in reading order: row by row from the top, each row from left to right, and the
     * heatmap they come from. Exemplars and crop are image boxes; the model sees only the crop, at
     * a higher resolution the smaller it is. Confidences are relative to the best detection in the
     * crop.
     */
    fun detect(
        image: Bitmap,
        exemplars: List<Box>,
        crop: Box = Box(0f, 0f, image.width.toFloat(), image.height.toFloat()),
    ): CountResult {
        require(exemplars.isNotEmpty()) { "At least one exemplar is needed" }
        val left = floor(crop.left).toInt().coerceIn(0, image.width - 1)
        val top = floor(crop.top).toInt().coerceIn(0, image.height - 1)
        val right = ceil(crop.right).toInt().coerceIn(left + 1, image.width)
        val bottom = ceil(crop.bottom).toInt().coerceIn(top + 1, image.height)
        val x = left.toFloat()
        val y = top.toFloat()
        synchronized(lock) {
            check(!closed) { "The counter is closed" }
            running++
        }
        val result =
            try {
                Bitmap.createBitmap(image, left, top, right - left, bottom - top).useDerivedFrom(
                    image
                ) {
                    detectInWhole(it, exemplars.map { box -> box.translated(-x, -y) })
                }
            } finally {
                synchronized(lock) { if (--running == 0 && closed) session.close() }
            }
        return CountResult(
            result.detections.map { it.copy(box = it.box.translated(x, y)) },
            result.heatmap.translated(x, y),
        )
    }

    private fun detectInWhole(image: Bitmap, exemplars: List<Box>): CountResult {
        val scale = inputScale(image.width, image.height, exemplars)
        val input = inputSize(image.width, image.height, scale)
        val pixels = pixels(image, scale, input)
        val boxes =
            exemplars.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.map { it * scale }
        val output =
            tensor(pixels, 1, 3, input.height, input.width).use { pixelTensor ->
                tensor(FloatBuffer.wrap(boxes.toFloatArray()), 1, exemplars.size, 4).use {
                    infer(pixelTensor, it)
                }
            }
        return CountResult(
            decodeDetections(output, scale, image.width, image.height),
            decodeHeatmap(output, scale, image.width, image.height),
        )
    }

    /**
     * Frees the model without waiting: now, or once the detections running on it have finished.
     * Don't detect afterwards.
     */
    override fun close() =
        synchronized(lock) {
            if (closed) return
            closed = true
            if (running == 0) session.close()
        }

    private fun tensor(values: FloatBuffer, vararg shape: Int) =
        OnnxTensor.createTensor(environment, values, shape.map { it.toLong() }.toLongArray())

    private fun infer(image: OnnxTensor, exemplars: OnnxTensor): ModelOutput =
        session.run(mapOf("image" to image, "exemplars" to exemplars)).use { result ->
            val objectness = result.tensor("objectness")
            ModelOutput(
                rows = objectness.info.shape[1].toInt(),
                columns = objectness.info.shape[2].toInt(),
                objectness = objectness.floats(),
                offsets = result.tensor("offsets").floats(),
            )
        }

    private fun OrtSession.Result.tensor(name: String) =
        get(name).orElseThrow { IllegalStateException("Model has no output $name") } as OnnxTensor

    private fun OnnxTensor.floats(): FloatArray {
        val buffer = floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        /**
         * Loads the model from the app assets. ONNX Runtime needs it as a file, so this copies it
         * out of the APK once per app version, which takes seconds; call it in the background.
         */
        fun fromAssets(context: Context): ObjectCounter = ObjectCounter(modelFile(context))

        private fun modelFile(context: Context): File {
            val asset = BuildConfig.MODEL_ASSET
            val directory = context.noBackupFilesDir
            val version =
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val file = File(directory, "$version-$asset")
            if (file.exists()) return file
            val partial = File(directory, "$asset.partial")
            // Earlier app versions' copies, and a copy cut off by the app being killed.
            directory
                .listFiles { stale -> stale.name.endsWith("-$asset") || stale == partial }
                .orEmpty()
                .forEach { stale ->
                    if (!stale.delete()) Log.w(TAG, "Cannot delete stale model copy $stale")
                }
            context.assets.open(asset).use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(file)) { "Cannot move model to $file" }
            Log.i(TAG, "Copied model to $file")
            return file
        }

        /** Scales the image, pads it to the input size and normalizes it, channels first. */
        private fun pixels(image: Bitmap, scale: Float, input: InputSize): FloatBuffer {
            val width = (image.width * scale).toInt()
            val height = (image.height * scale).toInt()
            val colors = IntArray(width * height)
            Bitmap.createScaledBitmap(image, width, height, true).useDerivedFrom(image) {
                it.getPixels(colors, 0, width, 0, 0, width, height)
            }
            val plane = input.width * input.height
            val values = FloatArray(3 * plane)
            for (channel in 0..2) {
                values.fill(-MEAN[channel] / STD[channel], channel * plane, (channel + 1) * plane)
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = colors[y * width + x]
                    val index = y * input.width + x
                    for (channel in 0..2) {
                        val value = (color shr (16 - 8 * channel) and 0xff) / 255f
                        values[channel * plane + index] = (value - MEAN[channel]) / STD[channel]
                    }
                }
            }
            return FloatBuffer.wrap(values)
        }

        /**
         * Runs [block] on this bitmap derived from [source], then frees it, unless Android returned
         * [source] itself (as it does when there is nothing to crop or scale).
         */
        private fun <T> Bitmap.useDerivedFrom(source: Bitmap, block: (Bitmap) -> T): T =
            try {
                block(this)
            } finally {
                if (this !== source) recycle()
            }
    }
}
