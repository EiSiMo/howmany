package run.moritz.quantify.counting

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
