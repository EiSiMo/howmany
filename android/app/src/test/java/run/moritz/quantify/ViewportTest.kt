package run.moritz.quantify

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    // A 1000 x 500 photo in a 500 x 500 view: fitted at half size, centered vertically.
    private val fitted = Viewport.fit(Size(500f, 500f), Size(1000f, 500f))

    @Test
    fun `fits the photo into the view and centers it`() {
        assertOffset(Offset(0f, 125f), fitted.toView(Offset(0f, 0f)))
        assertOffset(Offset(500f, 375f), fitted.toView(Offset(1000f, 500f)))
        assertOffset(Offset(1000f, 500f), fitted.toImage(Offset(500f, 375f)))
    }

    @Test
    fun `keeps the image point under the fingers while zooming`() {
        val zoomed = fitted.transformed(centroid = Offset(100f, 250f), zoom = 2f, pan = Offset.Zero)

        assertOffset(Offset(200f, 250f), zoomed.toImage(Offset(100f, 250f)))
        assertEquals(1f, zoomed.scale, 1e-6f)
    }

    @Test
    fun `zooms between the fitted size and eight times it`() {
        assertEquals(0.5f, fitted.transformed(Offset.Zero, 0.5f, Offset.Zero).scale, 1e-6f)
        assertEquals(4f, fitted.transformed(Offset.Zero, 100f, Offset.Zero).scale, 1e-6f)
    }

    @Test
    fun `pans only as far as the photo covers the view`() {
        val zoomed = fitted.transformed(Offset(250f, 250f), zoom = 2f, pan = Offset.Zero)

        val panned = zoomed.transformed(Offset.Zero, zoom = 1f, pan = Offset(10_000f, 10_000f))

        // The left edge stops at the view's left edge; vertically the photo exactly fills the view.
        assertOffset(Offset(0f, 0f), panned.toView(Offset(0f, 0f)))
    }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }
}
