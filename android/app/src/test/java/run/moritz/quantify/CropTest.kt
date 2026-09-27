package run.moritz.quantify

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import run.moritz.quantify.counting.Box

class CropTest {
    private val photo = Box(0f, 0f, 1000f, 800f)
    private val crop = Box(100f, 100f, 900f, 700f)

    @Test
    fun `grabs a corner, an edge or nothing`() {
        val view = Rect(100f, 100f, 500f, 400f)

        assertEquals(
            CropHandle(left = true, top = true),
            cropHandleAt(view, Offset(90f, 110f), 24f),
        )
        assertEquals(CropHandle(right = true), cropHandleAt(view, Offset(510f, 250f), 24f))
        assertNull(cropHandleAt(view, Offset(300f, 250f), 24f))
    }

    @Test
    fun `moves only the grabbed edges`() {
        val dragged = crop.dragged(CropHandle(left = true, bottom = true), Offset(50f, -20f), photo)

        assertEquals(Box(150f, 100f, 900f, 680f), dragged)
    }

    @Test
    fun `stays inside the photo`() {
        val dragged = crop.dragged(CropHandle(left = true, top = true), Offset(-500f, -500f), photo)

        assertEquals(Box(0f, 0f, 900f, 700f), dragged)
    }

    @Test
    fun `keeps a minimum size and the box it has to keep`() {
        val right = crop.dragged(CropHandle(right = true), Offset(-1000f, 0f), photo, minSize = 50f)
        val top =
            crop.dragged(
                CropHandle(top = true),
                Offset(0f, 1000f),
                photo,
                keep = Box(200f, 300f, 300f, 400f),
            )

        assertEquals(150f, right.right)
        assertEquals(300f, top.top)
    }
}
