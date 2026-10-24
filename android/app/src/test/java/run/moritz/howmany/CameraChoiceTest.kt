package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraChoiceTest {
    private val stock = "com.stock.camera/.Capture"
    private val open = "net.sourceforge.opencamera/.MainActivity"
    private val fossify = "org.fossify.camera/.activities.MainActivity"

    @Test
    fun `the preinstalled camera app takes the photo unless the user chose one`() {
        assertEquals(
            CameraChoice.Preinstalled,
            chooseCamera(listOf(stock, open), preinstalled = true, remembered = null),
        )
    }

    @Test
    fun `the camera app the user chose takes the photo`() {
        assertEquals(
            CameraChoice.App(open),
            chooseCamera(listOf(stock, open), preinstalled = true, remembered = open),
        )
    }

    @Test
    fun `a chosen camera app that is gone is forgotten`() {
        assertEquals(
            CameraChoice.Preinstalled,
            chooseCamera(listOf(stock), preinstalled = true, remembered = open),
        )
    }

    @Test
    fun `without a preinstalled camera app the only installed one takes the photo`() {
        assertEquals(
            CameraChoice.App(open),
            chooseCamera(listOf(open), preinstalled = false, remembered = null),
        )
    }

    @Test
    fun `without a preinstalled camera app the user chooses among the installed ones`() {
        assertEquals(
            CameraChoice.Choose(listOf(open, fossify)),
            chooseCamera(listOf(open, fossify), preinstalled = false, remembered = null),
        )
    }

    @Test
    fun `asked to, the user chooses again among all camera apps`() {
        assertEquals(
            CameraChoice.Choose(listOf(stock, open)),
            chooseCamera(
                listOf(stock, open),
                preinstalled = true,
                remembered = open,
                choose = true,
            ),
        )
    }

    @Test
    fun `there is nothing to choose with a single camera app`() {
        assertEquals(
            CameraChoice.App(open),
            chooseCamera(listOf(open), preinstalled = false, remembered = null, choose = true),
        )
    }

    @Test
    fun `without camera apps no photo can be taken`() {
        assertEquals(
            CameraChoice.None,
            chooseCamera(emptyList(), preinstalled = false, remembered = null),
        )
    }
}
