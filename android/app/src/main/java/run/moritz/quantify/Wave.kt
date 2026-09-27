package run.moritz.quantify

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

// How much slower the wave runs through a certain object than through empty space.
private const val RESISTANCE = 4f
// Samples per heatmap cell along each ray.
private const val SAMPLES_PER_CELL = 2

/**
 * When a wave from [origin] reaches each place in [crop], in image pixels. It runs straight out,
 * but slower wherever the [heatmap] sees objects, so it lags behind them.
 */
class Wave(private val heatmap: Heatmap, private val origin: Point, crop: Box) {
    private val cell = heatmap.bounds.width / heatmap.gridSize
    private val slowest =
        listOf(
                Point(crop.left, crop.top),
                Point(crop.right, crop.top),
                Point(crop.left, crop.bottom),
                Point(crop.right, crop.bottom),
            )
            .maxOf(::travelTime)
            .coerceAtLeast(1e-6f)

    /** When the wave reaches [at], from 0 at the origin to 1 at the last corner of the crop. */
    fun arrival(at: Point): Float = travelTime(at) / slowest

    /** The arrival at each heatmap cell's center, row-major on the heatmap's grid. */
    fun arrivals(): FloatArray =
        FloatArray(heatmap.gridSize * heatmap.gridSize) {
            val column = it % heatmap.gridSize
            val row = it / heatmap.gridSize
            arrival(
                Point(
                    heatmap.bounds.left + (column + 0.5f) * cell,
                    heatmap.bounds.top + (row + 0.5f) * cell,
                )
            )
        }

    /** The distance to [to], lengthened by the resistance of the objects along the way. */
    private fun travelTime(to: Point): Float {
        val distance = hypot(to.x - origin.x, to.y - origin.y)
        val samples = ceil(distance / cell * SAMPLES_PER_CELL).toInt().coerceAtLeast(1)
        var slowness = 0f
        for (i in 0 until samples) {
            val along = (i + 0.5f) / samples
            val x = origin.x + (to.x - origin.x) * along
            val y = origin.y + (to.y - origin.y) * along
            slowness += 1 + RESISTANCE * objectness(x, y)
        }
        return distance * slowness / samples
    }

    /** The heatmap at a point, interpolated between cell centers and 0 outside the grid. */
    private fun objectness(x: Float, y: Float): Float {
        val gridX = (x - heatmap.bounds.left) / cell - 0.5f
        val gridY = (y - heatmap.bounds.top) / cell - 0.5f
        val column = floor(gridX).toInt()
        val row = floor(gridY).toInt()
        val fx = gridX - column
        val fy = gridY - row
        return value(row, column) * (1 - fx) * (1 - fy) +
            value(row, column + 1) * fx * (1 - fy) +
            value(row + 1, column) * (1 - fx) * fy +
            value(row + 1, column + 1) * fx * fy
    }

    private fun value(row: Int, column: Int): Float {
        val size = heatmap.gridSize
        return if (row in 0 until size && column in 0 until size)
            heatmap.values[row * size + column]
        else 0f
    }
}
