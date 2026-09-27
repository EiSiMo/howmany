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
 * Picks one box per detected object, in image pixels, in reading order: row by row from the top,
 * each row from left to right.
 */
internal fun decodeDetections(
    output: ModelOutput,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
): List<Box> {
    val peaks = peaks(output)
    val best = peaks.maxOfOrNull { output.objectness[it] } ?: return emptyList()
    val candidates =
        peaks
            .filter { output.objectness[it] > best * SCORE_RATIO }
            .sortedByDescending { output.objectness[it] }
            .map { box(output, it) }
    return suppressDuplicates(candidates)
        .map { it.scaled(INPUT_SIZE / scale) }
        .filter { it.center.x < imageWidth && it.center.y < imageHeight }
        .inReadingOrder()
}

/** A box starts a new row unless its center lies within the height of the row's first box. */
private fun List<Box>.inReadingOrder(): List<Box> {
    val rows = mutableListOf<MutableList<Box>>()
    for (box in sortedBy { it.center.y }) {
        val row = rows.lastOrNull()
        if (row != null && box.center.y <= row.first().bottom) row += box
        else rows += mutableListOf(box)
    }
    return rows.flatMap { row -> row.sortedBy { it.center.x } }
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
