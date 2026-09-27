package run.moritz.quantify.counting

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in image pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

/** GeCo2 was trained on square images of this many pixels, the scaled image top-left. */
internal const val INPUT_SIZE = 1024
// The model takes that image with the padding cut off, down to the next multiple of this.
private const val SIZE_MULTIPLE = 32
// GeCo2 scales images so exemplars are at most this many pixels on average.
private const val EXEMPLAR_SIZE = 80f
// Post-processing as in GeCo2: objectness peaks above 1/8 of the maximum become candidate boxes,
// candidates scoring above 11% of the best are kept, and overlapping duplicates are suppressed.
private const val PEAK_RATIO = 1f / 8
private const val SCORE_RATIO = 0.11f
private const val NMS_IOU = 0.5f

/** The size of the model input in pixels. */
internal data class InputSize(val width: Int, val height: Int)

/**
 * The model's output on a grid over its input: an objectness score per cell (row-major), and per
 * cell the distances from the cell to the left, top, right and bottom box edges in input pixels.
 */
internal class ModelOutput(
    val rows: Int,
    val columns: Int,
    val objectness: FloatArray,
    val offsets: FloatArray,
)

/** The factor from image pixels to model input pixels. */
internal fun inputScale(imageWidth: Int, imageHeight: Int, exemplars: List<Box>): Float {
    val scale = INPUT_SIZE.toFloat() / max(imageWidth, imageHeight)
    val exemplarSize = exemplars.map { (it.width + it.height) / 2 }.average().toFloat() * scale
    return scale * min(1f, EXEMPLAR_SIZE / exemplarSize)
}

/** The model input for an image scaled by [scale]: the scaled image and a little padding. */
internal fun inputSize(imageWidth: Int, imageHeight: Int, scale: Float) =
    InputSize(paddedSide(imageWidth, scale), paddedSide(imageHeight, scale))

private fun paddedSide(side: Int, scale: Float) =
    ((side * scale).toInt() + SIZE_MULTIPLE - 1) / SIZE_MULTIPLE * SIZE_MULTIPLE

/** Picks one box per detected object, in image pixels. */
internal fun decodeDetections(
    output: ModelOutput,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
): List<Box> {
    val input = inputSize(imageWidth, imageHeight, scale)
    val peaks = peaks(output)
    val best = peaks.maxOfOrNull { output.objectness[it] } ?: return emptyList()
    val candidates =
        peaks
            .filter { output.objectness[it] > best * SCORE_RATIO }
            .sortedByDescending { output.objectness[it] }
            .map { box(output, input, it) }
    return suppressDuplicates(candidates)
        .map { it.scaled(1 / scale) }
        .filter { (it.left + it.right) / 2 < imageWidth && (it.top + it.bottom) / 2 < imageHeight }
}

/** Cells that are 3 x 3 local maxima above the peak threshold. */
private fun peaks(output: ModelOutput): List<Int> {
    val columns = output.columns
    val scores = output.objectness
    val threshold = scores.max() * PEAK_RATIO
    return scores.indices.filter { cell ->
        val row = cell / columns
        val column = cell % columns
        scores[cell] > threshold &&
            (max(row - 1, 0)..min(row + 1, output.rows - 1)).all { r ->
                (max(column - 1, 0)..min(column + 1, columns - 1)).all { c ->
                    scores[r * columns + c] <= scores[cell]
                }
            }
    }
}

/** The box predicted at a cell, in input pixels and clipped to the input. */
private fun box(output: ModelOutput, input: InputSize, cell: Int): Box {
    val width = input.width.toFloat()
    val height = input.height.toFloat()
    val x = (cell % output.columns) * width / output.columns
    val y = (cell / output.columns) * height / output.rows
    val (left, top, right, bottom) = output.offsets.copyOfRange(cell * 4, cell * 4 + 4).map { it }
    return Box(
        (x - left).coerceIn(0f, width),
        (y - top).coerceIn(0f, height),
        (x + right).coerceIn(0f, width),
        (y + bottom).coerceIn(0f, height),
    )
}

/** Greedy non-maximum suppression over boxes sorted by descending score. */
private fun suppressDuplicates(boxes: List<Box>): List<Box> {
    val kept = mutableListOf<Box>()
    for (box in boxes) {
        if (kept.none { iou(it, box) > NMS_IOU }) kept += box
    }
    return kept
}

private fun iou(a: Box, b: Box): Float {
    val width = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val height = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val intersection = width * height
    return intersection / (a.width * a.height + b.width * b.height - intersection)
}

private fun Box.scaled(factor: Float) =
    Box(left * factor, top * factor, right * factor, bottom * factor)
