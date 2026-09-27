package run.moritz.quantify

import kotlin.math.hypot
import kotlin.math.max
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

/**
 * When a wave from the edge of [exemplar] reaches each place in [crop], in image pixels; the
 * exemplar itself is reached at once. It runs straight out at the same speed everywhere; [heatmap]
 * only sets the grid of [arrivals].
 */
class Wave(val heatmap: Heatmap, private val exemplar: Box, crop: Box) {
    private val cellWidth = heatmap.bounds.width / heatmap.columns
    private val cellHeight = heatmap.bounds.height / heatmap.rows
    private val farthest =
        listOf(
                Point(crop.left, crop.top),
                Point(crop.right, crop.top),
                Point(crop.left, crop.bottom),
                Point(crop.right, crop.bottom),
            )
            .maxOf(::distance)
            .coerceAtLeast(1e-6f)

    /** When the wave reaches [at], from 0 in the exemplar to 1 at the last corner of the crop. */
    fun arrival(at: Point): Float = distance(at) / farthest

    /** The arrival at each heatmap cell's center, row-major on the heatmap's grid. */
    fun arrivals(): FloatArray =
        FloatArray(heatmap.rows * heatmap.columns) {
            val column = it % heatmap.columns
            val row = it / heatmap.columns
            arrival(
                Point(
                    heatmap.bounds.left + (column + 0.5f) * cellWidth,
                    heatmap.bounds.top + (row + 0.5f) * cellHeight,
                )
            )
        }

    private fun distance(to: Point) =
        hypot(
            max(0f, max(exemplar.left - to.x, to.x - exemplar.right)),
            max(0f, max(exemplar.top - to.y, to.y - exemplar.bottom)),
        )
}
