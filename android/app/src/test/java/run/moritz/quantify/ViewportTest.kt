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

    @Test
    fun `keeps a margin around the photo, also when panning`() {
        val withMargin = Viewport.fit(Size(520f, 520f), Size(1000f, 500f), Margin(10f))
        assertOffset(Offset(10f, 135f), withMargin.toView(Offset(0f, 0f)))

        val panned =
            withMargin
                .transformed(Offset(260f, 260f), zoom = 2f, pan = Offset.Zero)
                .transformed(Offset.Zero, zoom = 1f, pan = Offset(10_000f, 10_000f))

        assertOffset(Offset(10f, 10f), panned.toView(Offset(0f, 0f)))
    }

    @Test
    fun `keeps each side's own margin, for controls floating over the photo`() {
        // 60 free pixels at the top, 140 at the bottom: the photo fits between them.
        val margins = Margin(left = 0f, top = 60f, right = 0f, bottom = 140f)
        val fitted = Viewport.fit(Size(500f, 700f), Size(1000f, 1000f), margins)
        assertEquals(0.5f, fitted.scale, 1e-6f)
        assertOffset(Offset(0f, 60f), fitted.toView(Offset(0f, 0f)))

        val panned =
            fitted
                .transformed(Offset(250f, 350f), zoom = 4f, pan = Offset.Zero)
                .transformed(Offset.Zero, zoom = 1f, pan = Offset(0f, -10_000f))

        // The photo's bottom edge stops above the controls.
        assertEquals(560f, panned.toView(Offset(0f, 1000f)).y, 1e-3f)
    }

    @Test
    fun `zooms out step by step to the fitted photo`() {
        val zoomed = fitted.transformed(Offset(0f, 250f), zoom = 2f, pan = Offset.Zero)

        assertOffset(Offset(0f, 0f), zoomed.zoomedOut(0f).toView(Offset(0f, 0f)))
        assertEquals(0.75f, zoomed.zoomedOut(0.5f).scale, 1e-6f)
        assertOffset(Offset(0f, 62.5f), zoomed.zoomedOut(0.5f).toView(Offset(0f, 0f)))
        assertOffset(Offset(0f, 125f), zoomed.zoomedOut(1f).toView(Offset(0f, 0f)))
    }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }
}
