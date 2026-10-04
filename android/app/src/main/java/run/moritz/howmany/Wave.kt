package run.moritz.howmany

import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Heatmap
import run.moritz.howmany.counting.Point

/**
 * When a wave from the nearest of [exemplars] reaches each place in [crop], in image pixels; the
 * exemplars themselves are reached at once. It runs straight out at the same speed everywhere;
 * [heatmap] only sets the grid of [arrivals].
 */
class Wave(val heatmap: Heatmap, private val exemplars: List<Box>, crop: Box) {
    private val cellWidth = heatmap.bounds.width / heatmap.columns
    private val cellHeight = heatmap.bounds.height / heatmap.rows
    private val farthest = crop.corners.maxOf(::toExemplars).coerceAtLeast(1e-6f)

    /** When the wave reaches [at], from 0 in an exemplar to 1 at the last corner of the crop. */
    fun arrival(at: Point): Float = toExemplars(at) / farthest

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

    /** How far [at] lies outside the nearest exemplar. */
    private fun toExemplars(at: Point) = exemplars.minOf { it.distanceTo(at) }
}
