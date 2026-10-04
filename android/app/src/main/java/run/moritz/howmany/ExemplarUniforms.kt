package run.moritz.howmany

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect

// The shaders take the exemplars as this many fixed slots plus a count, so they need no array
// uniform, which not every device supports.
internal const val SHADER_EXEMPLARS = 3

/** Sets the [shader]'s exemplar slots to [exemplars], padding unused slots with the last one. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.setExemplars(exemplars: List<Rect>) {
    require(exemplars.isNotEmpty()) { "At least one exemplar is needed" }
    for (slot in 0 until SHADER_EXEMPLARS) {
        val rect = exemplars.getOrElse(slot) { exemplars.last() }
        setFloatUniform("exemplar$slot", rect.left, rect.top, rect.right, rect.bottom)
    }
    setFloatUniform("exemplarCount", exemplars.size.toFloat())
}
