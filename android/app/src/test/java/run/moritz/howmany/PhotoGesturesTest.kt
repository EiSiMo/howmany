package run.moritz.howmany

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import run.moritz.howmany.counting.Box

class PhotoGesturesTest {
    // The photo fills the view at its own size, so view and image pixels coincide.
    private val viewport = Viewport.fit(Size(1000f, 1000f), Size(1000f, 1000f))
    private val crop = Box(100f, 100f, 900f, 900f)

    private fun dragFrom(start: Offset, canAdjustCrop: Boolean, canMarkExemplar: Boolean) =
        photoDrag(start, crop, viewport, HANDLE_REACH, canAdjustCrop, canMarkExemplar)

    @Test
    fun `a drag from the crop's edge adjusts the crop`() {
        assertEquals(
            PhotoDrag.Crop(CropHandle(left = true), crop),
            dragFrom(Offset(110f, 500f), canAdjustCrop = true, canMarkExemplar = true),
        )
    }

    @Test
    fun `a drag elsewhere marks an exemplar, or pans when neither is allowed`() {
        assertEquals(
            PhotoDrag.Exemplar(Offset(500f, 500f), Offset(500f, 500f)),
            dragFrom(Offset(500f, 500f), canAdjustCrop = true, canMarkExemplar = true),
        )
        assertEquals(
            PhotoDrag.Exemplar(Offset(110f, 500f), Offset(110f, 500f)),
            dragFrom(Offset(110f, 500f), canAdjustCrop = false, canMarkExemplar = true),
        )
        assertEquals(
            PhotoDrag.Pan,
            dragFrom(Offset(110f, 500f), canAdjustCrop = false, canMarkExemplar = false),
        )
    }

    @Test
    fun `an exemplar spans its drag in any direction`() {
        val drag = PhotoDrag.Exemplar(Offset(400f, 300f), Offset(200f, 500f))

        assertEquals(Rect(200f, 300f, 400f, 500f), drag.rect)
        assertEquals(Box(200f, 300f, 400f, 500f), drag.box(viewport, crop))
    }

    @Test
    fun `an exemplar stays inside the crop and needs some size`() {
        assertEquals(
            Box(100f, 100f, 300f, 300f),
            PhotoDrag.Exemplar(Offset(50f, 50f), Offset(300f, 300f)).box(viewport, crop),
        )
        assertNull(PhotoDrag.Exemplar(Offset(300f, 300f), Offset(301f, 400f)).box(viewport, crop))
        assertNull(PhotoDrag.Exemplar(Offset(10f, 10f), Offset(50f, 50f)).box(viewport, crop))
    }

    @Test
    fun `a second tap soon and close by makes a double tap`() {
        assertTrue(isDoubleTap(Offset(500f, 500f), Offset(520f, 480f), elapsedMillis = 100))
    }

    @Test
    fun `a second tap too soon, too late or too far away does not`() {
        assertFalse(isDoubleTap(Offset(500f, 500f), Offset(500f, 500f), elapsedMillis = 10))
        assertFalse(isDoubleTap(Offset(500f, 500f), Offset(500f, 500f), elapsedMillis = 400))
        assertFalse(isDoubleTap(Offset(500f, 500f), Offset(700f, 500f), elapsedMillis = 100))
    }

    private fun isDoubleTap(first: Offset, second: Offset, elapsedMillis: Long) =
        isDoubleTap(first, second, elapsedMillis, window = 40L..300L, slop = 100f)

    private companion object {
        const val HANDLE_REACH = 24f
    }
}
