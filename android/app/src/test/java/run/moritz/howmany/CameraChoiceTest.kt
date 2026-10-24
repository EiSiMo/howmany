package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraChoiceTest {
    private val stock = "com.stock.camera/.Capture"
    private val open = "net.sourceforge.opencamera/.MainActivity"
    private val fossify = "org.fossify.camera/.activities.MainActivity"

    @Test
    fun `the only camera app takes the photo`() {
        assertEquals(
            CameraChoice.App(stock),
            chooseCamera(listOf(stock), preinstalled = true, remembered = null),
        )
    }

    @Test
    fun `the user chooses among several camera apps, also with a preinstalled one`() {
        assertEquals(
            CameraChoice.Choose(listOf(stock, open)),
            chooseCamera(listOf(stock, open), preinstalled = true, remembered = null),
        )
    }

    @Test
    fun `the camera app the user chose takes the photo`() {
        assertEquals(
            CameraChoice.App(open),
            chooseCamera(
                listOf(stock, open),
                preinstalled = true,
                remembered = open,
                known = setOf(stock, open),
            ),
        )
    }

    @Test
    fun `the user chooses again when the chosen camera app is gone`() {
        assertEquals(
            CameraChoice.Choose(listOf(stock, fossify)),
            chooseCamera(listOf(stock, fossify), preinstalled = true, remembered = open),
        )
    }

    @Test
    fun `the user chooses again among new camera apps`() {
        assertEquals(
            CameraChoice.Choose(listOf(stock, open, fossify)),
            chooseCamera(
                listOf(stock, open, fossify),
                preinstalled = true,
                remembered = open,
                known = setOf(stock, open),
            ),
        )
    }

    @Test
    fun `gone camera apps are no reason to choose again`() {
        assertEquals(
            CameraChoice.App(open),
            chooseCamera(
                listOf(stock, open),
                preinstalled = true,
                remembered = open,
                known = setOf(stock, open, fossify),
            ),
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
                known = setOf(stock, open),
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
    fun `a preinstalled camera app we cannot see is asked without naming it`() {
        assertEquals(
            CameraChoice.Preinstalled,
            chooseCamera(emptyList(), preinstalled = true, remembered = null),
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
