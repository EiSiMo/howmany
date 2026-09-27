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

        val boxes =
            decodeDetections(output, scale = 0.5f, imageWidth = 2048, imageHeight = 2048).map {
                it.box
            }

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

        val boxes =
            decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024).map {
                it.box
            }

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

    @Test
    fun `orders detections row by row from top left to bottom right`() {
        val output = emptyOutput(gridSize = 16)
        // Strongest first, so that the model's order differs from the reading order.
        output.objectness(row = 12, column = 12, value = 1f)
        output.objectness(row = 12, column = 4, value = 0.9f)
        output.objectness(row = 4, column = 12, value = 0.8f)
        output.objectness(row = 4, column = 4, value = 0.7f)
        for ((row, column) in listOf(4 to 4, 4 to 12, 12 to 4, 12 to 12)) {
            output.offsets(row, column, 0.05f, 0.05f, 0.05f, 0.05f)
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
        val output = emptyOutput(gridSize = 16)
        output.objectness(row = 4, column = 12, value = 1f)
        output.offsets(row = 4, column = 12, 0.1f, 0.1f, 0.1f, 0.1f)
        // A bit lower than its right neighbour, but well within its height.
        output.objectness(row = 5, column = 4, value = 0.9f)
        output.offsets(row = 5, column = 4, 0.1f, 0.1f, 0.1f, 0.1f)

        val boxes =
            decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024).map {
                it.box
            }

        assertEquals(listOf(0.25f, 0.75f), boxes.map { it.center().first })
    }

    @Test
    fun `rates each detection relative to the best one and flags weak ones as uncertain`() {
        val output = emptyOutput(gridSize = 16)
        output.objectness(row = 4, column = 4, value = 2f)
        output.objectness(row = 4, column = 12, value = 1.2f)
        output.objectness(row = 12, column = 4, value = 0.6f)
        for ((row, column) in listOf(4 to 4, 4 to 12, 12 to 4)) {
            output.offsets(row, column, 0.05f, 0.05f, 0.05f, 0.05f)
        }

        val detections = decodeDetections(output, scale = 1f, imageWidth = 1024, imageHeight = 1024)

        assertEquals(listOf(1f, 0.6f, 0.3f), detections.map { it.confidence })
        assertEquals(listOf(false, false, true), detections.map { it.uncertain })
    }

    @Test
    fun `places the heatmap so each cell is centered on its position in image pixels`() {
        val output = emptyOutput(gridSize = 4)
        output.objectness(row = 1, column = 2, value = 4f)
        output.objectness(row = 3, column = 0, value = 1f)

        val heatmap = decodeHeatmap(output, scale = 0.5f)

        // A cell is 2048 / 4 = 512 image pixels; cell (0, 0) is centered on the origin.
        assertBoxes(listOf(Box(-256f, -256f, 1792f, 1792f)), listOf(heatmap.bounds))
        assertEquals(1f, heatmap.values[1 * 4 + 2])
        assertEquals(0.25f, heatmap.values[3 * 4 + 0])
    }

    private fun Box.center() = (left + right) / 2 / 1024 to (top + bottom) / 2 / 1024

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
