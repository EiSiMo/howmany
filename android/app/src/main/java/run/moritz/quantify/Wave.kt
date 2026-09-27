package run.moritz.quantify

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
    private val farthest = crop.corners.maxOf(exemplar::distanceTo).coerceAtLeast(1e-6f)

    /** When the wave reaches [at], from 0 in the exemplar to 1 at the last corner of the crop. */
    fun arrival(at: Point): Float = exemplar.distanceTo(at) / farthest

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
}
