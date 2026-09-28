package run.moritz.howmany.counting

import kotlin.math.max
import kotlin.math.min

/** GeCo2 was trained on square images of this many pixels, the scaled image top-left. */
internal const val INPUT_SIZE = 1024
// The model takes that image with the padding cut off, down to the next multiple of this.
private const val SIZE_MULTIPLE = 32
// But never smaller than this per side: with less padding around very small images (few, large
// objects) GeCo2 miscounts, e.g. 48 instead of 32 planks.
private const val MIN_INPUT_SIDE = 512
// GeCo2 scales images so exemplars are at most this many pixels on average.
private const val EXEMPLAR_SIZE = 80f
// Post-processing as in GeCo2: objectness peaks above 1/8 of the maximum become candidate boxes,
// candidates scoring above 11% of the best are kept, and overlapping duplicates are suppressed.
private const val PEAK_RATIO = 1f / 8
private const val SCORE_RATIO = 0.11f
private const val NMS_IOU = 0.5f
// Below this confidence, half of the detections on our photos and FSC-147 are false; above 0.7
// only 2%. Flagging these asks the user to check a fifth of the detections, which hold three
// quarters of the false ones.
private const val UNCERTAIN_BELOW = 0.5f

/**
 * One detected object: its [box] in image pixels, and the model's [confidence] relative to the best
 * detection in the image, from 1 for the best down to about 0.1.
 */
data class Detection(val box: Box, val confidence: Float) {
    /** Whether the detection is often wrong, so the user should check it. */
    val uncertain
        get() = confidence < UNCERTAIN_BELOW
}

/** The size of the model input in pixels. */
internal data class InputSize(val width: Int, val height: Int)

/**
 * Where the model sees objects: a grid of objectness values (row-major) relative to the maximum, so
 * from 0 to 1, with [bounds] in image pixels such that each cell's value belongs to the center of
 * its share of the bounds.
 */
class Heatmap(val rows: Int, val columns: Int, val values: FloatArray, val bounds: Box) {
    fun translated(x: Float, y: Float) = Heatmap(rows, columns, values, bounds.translated(x, y))
}

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
    max(
        ((side * scale).toInt() + SIZE_MULTIPLE - 1) / SIZE_MULTIPLE * SIZE_MULTIPLE,
        MIN_INPUT_SIDE,
    )

/**
 * Picks one detection per object, with its box in image pixels, in reading order: row by row from
 * the top, each row from left to right.
 */
internal fun decodeDetections(
    output: ModelOutput,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
): List<Detection> {
    val input = inputSize(imageWidth, imageHeight, scale)
    val peaks = peaks(output)
    val best = peaks.maxOfOrNull { output.objectness[it] } ?: return emptyList()
    val candidates =
        peaks
            .filter { output.objectness[it] > best * SCORE_RATIO }
            .sortedByDescending { output.objectness[it] }
            .map { Detection(box(output, input, it), output.objectness[it] / best) }
    return suppressDuplicates(candidates)
        .map { it.copy(box = it.box.scaled(1 / scale)) }
        .filter { it.box.center.x < imageWidth && it.box.center.y < imageHeight }
        .inReadingOrder()
}

/** The objectness grid, relative to its maximum, in image pixels. */
internal fun decodeHeatmap(
    output: ModelOutput,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
): Heatmap {
    val best = output.objectness.max().takeIf { it > 0 } ?: 1f
    val input = inputSize(imageWidth, imageHeight, scale)
    // The model places a cell's prediction at the cell's top left corner in the input.
    val cellWidth = input.width / scale / output.columns
    val cellHeight = input.height / scale / output.rows
    return Heatmap(
        output.rows,
        output.columns,
        FloatArray(output.objectness.size) { (output.objectness[it] / best).coerceAtLeast(0f) },
        Box(
            -cellWidth / 2,
            -cellHeight / 2,
            input.width / scale - cellWidth / 2,
            input.height / scale - cellHeight / 2,
        ),
    )
}

/** A box starts a new row unless its center lies within the height of the row's first box. */
private fun List<Detection>.inReadingOrder(): List<Detection> {
    val rows = mutableListOf<MutableList<Detection>>()
    for (detection in sortedBy { it.box.center.y }) {
        val row = rows.lastOrNull()
        if (row != null && detection.box.center.y <= row.first().box.bottom) row += detection
        else rows += mutableListOf(detection)
    }
    return rows.flatMap { row -> row.sortedBy { it.box.center.x } }
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
    val (left, top, right, bottom) = output.offsets.copyOfRange(cell * 4, cell * 4 + 4)
    return Box(
        (x - left).coerceIn(0f, width),
        (y - top).coerceIn(0f, height),
        (x + right).coerceIn(0f, width),
        (y + bottom).coerceIn(0f, height),
    )
}

/** Greedy non-maximum suppression over detections sorted by descending confidence. */
private fun suppressDuplicates(detections: List<Detection>): List<Detection> {
    val kept = mutableListOf<Detection>()
    for (detection in detections) {
        if (kept.none { iou(it.box, detection.box) > NMS_IOU }) kept += detection
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
