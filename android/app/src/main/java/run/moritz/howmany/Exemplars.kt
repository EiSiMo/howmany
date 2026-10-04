package run.moritz.howmany

import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

/**
 * The exemplars without the one the user tapped at [at]: the smallest one containing [at], or the
 * last drawn one if several are the same size. Tapping empty space removes nothing.
 */
fun List<Box>.removedAt(at: Point): List<Box> {
    val hit =
        indices
            .filter { at in this[it] }
            .minWithOrNull(compareBy({ this[it].width * this[it].height }, { -it })) ?: return this
    return filterIndexed { index, _ -> index != hit }
}

/** The exemplars with [box] added, unless there are already [MAX_EXEMPLARS] of them. */
fun List<Box>.marked(box: Box): List<Box> = if (size < MAX_EXEMPLARS) this + box else this
