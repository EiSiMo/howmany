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
    fun `sizes the input to the scaled image, rounded up to a multiple of 32`() {
        assertEquals(InputSize(1024, 512), inputSize(2000, 1000, scale = 0.512f))
        assertEquals(InputSize(608, 512), inputSize(2000, 1000, scale = 0.3f))
    }

    @Test
    fun `pads small images to at least 512 pixels per side`() {
        assertEquals(InputSize(512, 512), inputSize(400, 200, scale = 0.5f))
    }

    @Test
    fun `turns an objectness peak into a box in image pixels`() {
        // A 1024 x 512 input, whose output grid has a cell every 32 input pixels.
        val output = emptyOutput(rows = 16, columns = 32)
        output.objectness(row = 4, column = 10, value = 1f)
        output.offsets(row = 4, column = 10, 2f, 3f, 4f, 6f)

        val boxes =
            decodeDetections(output, scale = 0.5f, imageWidth = 2048, imageHeight = 1024).map {
                it.box
            }

        assertBoxes(listOf(Box(636f, 250f, 648f, 268f)), boxes)
    }

    @Test
    fun `keeps one box per object and drops weak peaks`() {
        val output = emptyOutput(rows = 16, columns = 16)
        output.objectness(row = 4, column = 4, value = 1f)
        output.offsets(row = 4, column = 4, 128f, 128f, 128f, 128f)
        // A duplicate of the same object, overlapping the first box by more than half.
        output.objectness(row = 4, column = 6, value = 0.9f)
        output.offsets(row = 4, column = 6, 128f, 128f, 128f, 128f)
        output.objectness(row = 12, column = 12, value = 0.8f)
        output.offsets(row = 12, column = 12, 32f, 32f, 32f, 32f)
        output.objectness(row = 12, column = 2, value = 0.1f)

        val boxes =
            decodeDetections(output, scale = 1f, imageWidth = 512, imageHeight = 512).map {
                it.box
            }

        assertEquals(listOf(128f, 384f), boxes.map { (it.left + it.right) / 2 })
    }

    @Test
    fun `drops detections in the padding`() {
        // A 300 x 512 image padded to a 512 x 512 input.
        val output = emptyOutput(rows = 16, columns = 32)
        output.objectness(row = 8, column = 8, value = 1f)
        output.objectness(row = 8, column = 24, value = 1f)

        val boxes = decodeDetections(output, scale = 1f, imageWidth = 300, imageHeight = 512)

        assertEquals(1, boxes.size)
    }

    @Test
    fun `orders detections row by row from top left to bottom right`() {
        val output = emptyOutput(rows = 16, columns = 16)
        // Strongest first, so that the model's order differs from the reading order.
        output.objectness(row = 12, column = 12, value = 1f)
        output.objectness(row = 12, column = 4, value = 0.9f)
        output.objectness(row = 4, column = 12, value = 0.8f)
        output.objectness(row = 4, column = 4, value = 0.7f)
        for ((row, column) in listOf(4 to 4, 4 to 12, 12 to 4, 12 to 12)) {
            output.offsets(row, column, 51.2f, 51.2f, 51.2f, 51.2f)
        }

        val boxes =
            decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024).map {
                it.box
            }

        assertEquals(
            listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f),
            boxes.map { it.center() },
        )
    }

    @Test
    fun `keeps slightly offset objects in the same row`() {
        val output = emptyOutput(rows = 16, columns = 16)
        output.objectness(row = 4, column = 12, value = 1f)
        output.offsets(row = 4, column = 12, 102.4f, 102.4f, 102.4f, 102.4f)
        // A bit lower than its right neighbour, but well within its height.
        output.objectness(row = 5, column = 4, value = 0.9f)
        output.offsets(row = 5, column = 4, 102.4f, 102.4f, 102.4f, 102.4f)

        val boxes =
            decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024).map {
                it.box
            }

        assertEquals(listOf(0.25f, 0.75f), boxes.map { it.center().first })
    }

    @Test
    fun `rates each detection relative to the best one and flags weak ones as uncertain`() {
        val output = emptyOutput(rows = 16, columns = 16)
        output.objectness(row = 4, column = 4, value = 2f)
        output.objectness(row = 4, column = 12, value = 1.2f)
        output.objectness(row = 12, column = 4, value = 0.6f)
        for ((row, column) in listOf(4 to 4, 4 to 12, 12 to 4)) {
            output.offsets(row, column, 51.2f, 51.2f, 51.2f, 51.2f)
        }

        val detections = decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024)

        assertEquals(listOf(1f, 0.6f, 0.3f), detections.map { it.confidence })
        assertEquals(listOf(false, false, true), detections.map { it.uncertain })
    }

    @Test
    fun `places the heatmap so each cell is centered on its position in image pixels`() {
        // A 2048 x 1024 image scaled to a 1024 x 512 input, with an output cell every 256 input
        // pixels.
        val output = emptyOutput(rows = 2, columns = 4)
        output.objectness(row = 1, column = 2, value = 4f)
        output.objectness(row = 0, column = 3, value = 1f)

        val heatmap = decodeHeatmap(output, scale = 0.5f, imageWidth = 2048, imageHeight = 1024)

        // A cell is 512 image pixels; cell (0, 0) is centered on the origin.
        assertEquals(2 to 4, heatmap.rows to heatmap.columns)
        assertBoxes(listOf(Box(-256f, -256f, 1792f, 768f)), listOf(heatmap.bounds))
        assertEquals(1f, heatmap.values[1 * 4 + 2])
        assertEquals(0.25f, heatmap.values[0 * 4 + 3])
    }

    private fun Box.center() = (left + right) / 2 / 1024 to (top + bottom) / 2 / 1024

    private fun emptyOutput(rows: Int, columns: Int) =
        ModelOutput(rows, columns, FloatArray(rows * columns), FloatArray(rows * columns * 4))

    private fun ModelOutput.objectness(row: Int, column: Int, value: Float) {
        objectness[row * columns + column] = value
    }

    private fun ModelOutput.offsets(row: Int, column: Int, vararg values: Float) {
        values.copyInto(offsets, (row * columns + column) * 4)
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
