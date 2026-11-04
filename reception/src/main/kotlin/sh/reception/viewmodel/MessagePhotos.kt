package sh.reception.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodes a prepared JPEG for display, subsampled so its longest edge stays near [edge]. Call it off the main thread. */
internal object MessagePhotos {
    private const val PREVIEW_EDGE = 1024

    fun preview(data: ByteArray, edge: Int = PREVIEW_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
