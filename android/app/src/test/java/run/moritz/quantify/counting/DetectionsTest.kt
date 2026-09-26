package run.moritz.quantify.counting

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionsTest {
    @Test
    fun `scales the longer side to the input size`() {
        val scale = inputScale(2000, 1000, listOf(Box(0f, 0f, 100f, 100f)))

        assertEquals(0.512f, scale, 1e-6f)
    }

    @Test
    fun `shrinks the image further so exemplars are at most 80 input pixels`() {
        val scale = inputScale(2000, 1000, listOf(Box(0f, 0f, 400f, 200f)))

        assertEquals(0.512f * 80f / 153.6f, scale, 1e-6f)
    }

    @Test
    fun `turns an objectness peak into a box in image pixels`() {
        val output = emptyOutput(gridSize = 4)
        output.objectness(row = 1, column = 2, value = 1f)
        output.offsets(row = 1, column = 2, 0.05f, 0.05f, 0.05f, 0.05f)

        val boxes = decodeDetections(output, scale = 0.5f, imageWidth = 2048, imageHeight = 2048)

        assertBoxes(listOf(Box(921.6f, 409.6f, 1126.4f, 614.4f)), boxes)
    }

    @Test
    fun `keeps one box per object and drops weak peaks`() {
        val output = emptyOutput(gridSize = 16)
        output.objectness(row = 4, column = 4, value = 1f)
        output.offsets(row = 4, column = 4, 0.2f, 0.2f, 0.2f, 0.2f)
        // A duplicate of the same object, overlapping the first box by more than half.
        output.objectness(row = 4, column = 6, value = 0.9f)
        output.offsets(row = 4, column = 6, 0.2f, 0.2f, 0.2f, 0.2f)
        output.objectness(row = 12, column = 12, value = 0.8f)
        output.offsets(row = 12, column = 12, 0.05f, 0.05f, 0.05f, 0.05f)
        output.objectness(row = 12, column = 2, value = 0.1f)

        val boxes = decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024)

        assertEquals(listOf(0.25f, 0.75f), boxes.map { (it.left + it.right) / 2 / 1024 })
    }

    @Test
    fun `drops detections in the padding`() {
        val output = emptyOutput(gridSize = 4)
        output.objectness(row = 1, column = 1, value = 1f)
        output.objectness(row = 1, column = 3, value = 1f)

        val boxes = decodeDetections(output, scale = 1f, imageWidth = 512, imageHeight = 1024)

        assertEquals(1, boxes.size)
    }

    private fun emptyOutput(gridSize: Int) =
        ModelOutput(gridSize, FloatArray(gridSize * gridSize), FloatArray(gridSize * gridSize * 4))

    private fun ModelOutput.objectness(row: Int, column: Int, value: Float) {
        objectness[row * gridSize + column] = value
    }

    private fun ModelOutput.offsets(row: Int, column: Int, vararg values: Float) {
        values.copyInto(offsets, (row * gridSize + column) * 4)
    }

    private fun assertBoxes(expected: List<Box>, actual: List<Box>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.left, a.left, 1e-3f)
            assertEquals(e.top, a.top, 1e-3f)
            assertEquals(e.right, a.right, 1e-3f)
            assertEquals(e.bottom, a.bottom, 1e-3f)
        }
    }
}
