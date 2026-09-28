package run.moritz.howmany

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedImageTest {
    @Test
    fun `takes the image shared with the app`() {
        assertEquals("stream", sharedImage(Intent.ACTION_SEND, "image/jpeg", "stream", "data"))
    }

    @Test
    fun `takes the image opened with the app`() {
        assertEquals("data", sharedImage(Intent.ACTION_VIEW, "image/png", "stream", "data"))
    }

    @Test
    fun `ignores intents without an image`() {
        assertNull(sharedImage(Intent.ACTION_MAIN, null, null, null))
        assertNull(sharedImage(Intent.ACTION_SEND, "text/plain", "stream", null))
        assertNull(sharedImage(Intent.ACTION_VIEW, null, null, "data"))
        assertNull(sharedImage(Intent.ACTION_SEND, "image/jpeg", null, "data"))
        assertNull(sharedImage(Intent.ACTION_SEND_MULTIPLE, "image/jpeg", "stream", null))
    }
}
