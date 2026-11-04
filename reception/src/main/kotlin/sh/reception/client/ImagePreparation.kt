package sh.reception.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A photo as the service accepts it: JPEG, at most 2,048 px on the longest edge and 1 MiB. */
internal class PreparedImage(val data: ByteArray, val width: Int, val height: Int)

/**
 * Turns a picked photo into a [PreparedImage]. Decoding is subsampled so a 50-megapixel photo never needs its full
 * size in memory; EXIF orientation is applied before scaling; transparency becomes white. Call it off the main thread.
 */
internal object ImagePreparation {
    const val MAXIMUM_EDGE: Int = 2048
    const val MAXIMUM_BYTES: Int = 1_048_576

    fun prepare(source: ByteArray): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ReceptionAPIError("invalid_image", 0)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight)) }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw ReceptionAPIError("invalid_image", 0)
        try {
            val orientation = orientation(source)
            val (width, height) = oriented(orientation, decoded.width, decoded.height)
            var edge = MAXIMUM_EDGE.toFloat()
            repeat(5) {
                val scale = min(1f, edge / max(width, height))
                val size = max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
                val rendered = render(decoded, orientation, size.first, size.second)
                try {
                    for (quality in 85 downTo 25 step 10) {
                        val data = ByteArrayOutputStream().also { rendered.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                        if (data.size <= MAXIMUM_BYTES) return PreparedImage(data, size.first, size.second)
                    }
                } finally {
                    rendered.recycle()
                }
                edge *= 0.75f
            }
        } finally {
            decoded.recycle()
        }
        throw ReceptionAPIError("image_too_large", 0)
    }

    /** The largest power of two that keeps the decoded image at least [MAXIMUM_EDGE] on its longest edge. */
    private fun sampleSize(longestEdge: Int): Int {
        var sample = 1
        while (longestEdge / (sample * 2) >= MAXIMUM_EDGE) sample *= 2
        return sample
    }

    private fun orientation(source: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun oriented(orientation: Int, width: Int, height: Int): Pair<Int, Int> = when (orientation) {
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270 -> height to width
        else -> width to height
    }

    /** Draws [source] upright at [width] × [height] over white. */
    private fun render(source: Bitmap, orientation: Int, width: Int, height: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        matrix.postScale(width / bounds.width(), height / bounds.height())
        val result = createBitmap(width, height)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }
}
