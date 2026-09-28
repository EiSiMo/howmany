package run.moritz.howmany

import kotlin.math.abs
import kotlin.math.hypot
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

/**
 * Corrects counted points by a tap at [at]: removes the nearest point within [hitRadius], or adds
 * one at [at] if there is none. Only points inside [crop] count, and taps outside it do nothing.
 * Points stay in reading order, so their numbers stay easy to follow: a new point goes into the row
 * of points within [objectHeight] / 2 of it vertically, before the first one to its right.
 */
fun List<Point>.toggled(
    at: Point,
    hitRadius: Float,
    objectHeight: Float,
    crop: Box? = null,
): List<Point> {
    if (crop != null && at !in crop) return this
    val hit =
        withIndex()
            .filter { (_, point) -> crop == null || point in crop }
            .map { (index, point) -> index to hypot(point.x - at.x, point.y - at.y) }
            .filter { (_, distance) -> distance <= hitRadius }
            .minByOrNull { (_, distance) -> distance }
    if (hit != null) return filterIndexed { index, _ -> index != hit.first }
    val next = indexOfFirst { point ->
        if (abs(point.y - at.y) <= objectHeight / 2) point.x > at.x else point.y > at.y
    }
    return if (next == -1) this + at else take(next) + at + drop(next)
}
