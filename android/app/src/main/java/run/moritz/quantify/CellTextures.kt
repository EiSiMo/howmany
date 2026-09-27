package run.moritz.quantify

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.core.graphics.createBitmap
import java.nio.ShortBuffer

// Textures of one pixel per cell of a grid, such as the heatmap's, stretched over a rect in view
// coordinates: smoothly interpolated between the cells' centers and clamped beyond.

/** Per-cell ARGB [pixels] of a grid of [columns] x [rows] cells, stretched over [rect]. */
internal fun cellsBrush(pixels: IntArray, columns: Int, rows: Int, rect: Rect): Brush {
    val bitmap = Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888)
    return ShaderBrush(cellsShader(bitmap).apply { stretchCells(columns, rows, rect) })
}

/**
 * Per-cell [values] of a grid of [columns] x [rows] cells in every channel, as half floats; place
 * it with [stretchCells].
 */
// Lint takes the half floats for plain shorts, but they go straight into a half float bitmap.
@SuppressLint("HalfFloat")
internal fun cellsShader(values: FloatArray, columns: Int, rows: Int): BitmapShader {
    val halves = ShortArray(values.size * 4)
    for (cell in values.indices) {
        halves.fill(Half.toHalf(values[cell]), cell * 4, cell * 4 + 4)
    }
    val bitmap = createBitmap(columns, rows, Bitmap.Config.RGBA_F16)
    bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(halves))
    return cellsShader(bitmap)
}

/** Stretches the texture of a grid of [columns] x [rows] cells over [rect]. */
internal fun BitmapShader.stretchCells(columns: Int, rows: Int, rect: Rect) =
    setLocalMatrix(
        Matrix().apply {
            setScale(rect.width / columns, rect.height / rows)
            postTranslate(rect.left, rect.top)
        }
    )

private fun cellsShader(bitmap: Bitmap) =
    BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }
