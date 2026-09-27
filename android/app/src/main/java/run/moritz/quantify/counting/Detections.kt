package run.moritz.quantify.counting

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in image pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width
        get() = right - left

    val height
        get() = bottom - top

    val center
        get() = Point((left + right) / 2, (top + bottom) / 2)

    operator fun contains(point: Point) = point.x in left..right && point.y in top..bottom

    fun translated(x: Float, y: Float) = Box(left + x, top + y, right + x, bottom + y)
}

/** A point in image pixels. */
data class Point(val x: Float, val y: Float)

/** The model takes a square image of this many pixels. */
internal const val INPUT_SIZE = 1024
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

/**
 * Where the model sees objects: a square grid of objectness values (row-major) relative to the
 * maximum, so from 0 to 1, with [bounds] in image pixels such that each cell's value belongs to the
 * center of its share of the bounds.
 */
class Heatmap(val gridSize: Int, val values: FloatArray, val bounds: Box) {
    fun translated(x: Float, y: Float) = Heatmap(gridSize, values, bounds.translated(x, y))
}

/**
 * The model's output on a square grid: an objectness score per cell (row-major), and per cell the
 * distances from the cell to the left, top, right and bottom box edges, relative to the input.
 */
internal class ModelOutput(val gridSize: Int, val objectness: FloatArray, val offsets: FloatArray)

/** The factor from image pixels to model input pixels. */
internal fun inputScale(imageWidth: Int, imageHeight: Int, exemplars: List<Box>): Float {
    val scale = INPUT_SIZE.toFloat() / max(imageWidth, imageHeight)
    val exemplarSize = exemplars.map { (it.width + it.height) / 2 }.average().toFloat() * scale
    return scale * min(1f, EXEMPLAR_SIZE / exemplarSize)
}

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
    val peaks = peaks(output)
    val best = peaks.maxOfOrNull { output.objectness[it] } ?: return emptyList()
    val candidates =
        peaks
            .filter { output.objectness[it] > best * SCORE_RATIO }
            .sortedByDescending { output.objectness[it] }
            .map { Detection(box(output, it), output.objectness[it] / best) }
    return suppressDuplicates(candidates)
        .map { it.copy(box = it.box.scaled(INPUT_SIZE / scale)) }
        .filter { it.box.center.x < imageWidth && it.box.center.y < imageHeight }
        .inReadingOrder()
}

/** The objectness grid, relative to its maximum, in image pixels. */
internal fun decodeHeatmap(output: ModelOutput, scale: Float): Heatmap {
    val best = output.objectness.max().takeIf { it > 0 } ?: 1f
    // The model places a cell's prediction at the cell's top left corner in the input.
    val cell = INPUT_SIZE / scale / output.gridSize
    val size = cell * output.gridSize
    return Heatmap(
        output.gridSize,
        FloatArray(output.objectness.size) { (output.objectness[it] / best).coerceAtLeast(0f) },
        Box(-cell / 2, -cell / 2, size - cell / 2, size - cell / 2),
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
    val size = output.gridSize
    val scores = output.objectness
    val threshold = scores.max() * PEAK_RATIO
    return scores.indices.filter { cell ->
        val row = cell / size
        val column = cell % size
        scores[cell] > threshold &&
            (max(row - 1, 0)..min(row + 1, size - 1)).all { r ->
                (max(column - 1, 0)..min(column + 1, size - 1)).all { c ->
                    scores[r * size + c] <= scores[cell]
                }
            }
    }
}

/** The box predicted at a cell, relative to the input and clipped to it. */
private fun box(output: ModelOutput, cell: Int): Box {
    val x = (cell % output.gridSize).toFloat() / output.gridSize
    val y = (cell / output.gridSize).toFloat() / output.gridSize
    val (left, top, right, bottom) = output.offsets.copyOfRange(cell * 4, cell * 4 + 4).map { it }
    return Box(
        (x - left).coerceIn(0f, 1f),
        (y - top).coerceIn(0f, 1f),
        (x + right).coerceIn(0f, 1f),
        (y + bottom).coerceIn(0f, 1f),
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
