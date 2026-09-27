package run.moritz.quantify

import kotlin.math.hypot
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Point

/**
 * Corrects counted points by a tap at [at]: removes the nearest point within [hitRadius], or adds
 * one at [at] if there is none. Only points inside [crop] count, and taps outside it do nothing.
 */
fun List<Point>.toggled(at: Point, hitRadius: Float, crop: Box? = null): List<Point> {
    if (crop != null && at !in crop) return this
    val hit =
        withIndex()
            .filter { (_, point) -> crop == null || point in crop }
            .map { (index, point) -> index to hypot(point.x - at.x, point.y - at.y) }
            .filter { (_, distance) -> distance <= hitRadius }
            .minByOrNull { (_, distance) -> distance }
    return if (hit == null) this + at else filterIndexed { index, _ -> index != hit.first }
}
