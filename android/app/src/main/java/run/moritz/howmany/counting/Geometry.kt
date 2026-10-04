package run.moritz.howmany.counting

import kotlin.math.hypot
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

    val corners
        get() =
            listOf(Point(left, top), Point(right, top), Point(left, bottom), Point(right, bottom))

    operator fun contains(point: Point) = point.x in left..right && point.y in top..bottom

    /** How far [point] lies outside the box, from its nearest edge; 0 inside. */
    fun distanceTo(point: Point) =
        hypot(
            max(0f, max(left - point.x, point.x - right)),
            max(0f, max(top - point.y, point.y - bottom)),
        )

    fun translated(x: Float, y: Float) = Box(left + x, top + y, right + x, bottom + y)
}

/** A point in image pixels. */
data class Point(val x: Float, val y: Float)

/** The smallest box containing all of these, or null if there are none. */
fun Iterable<Box>.region(): Box? = reduceOrNull { a, b ->
    Box(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
