package ru.parts.ledger.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import ru.parts.ledger.domain.Photos
import java.io.ByteArrayOutputStream

object PhotoImages {
    private const val MAX_DIMENSION = 1800

    fun import(resolver: ContentResolver, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось открыть изображение" }
        val size = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while ((size.toLong() + inSampleSize - 1) / inSampleSize > MAX_DIMENSION) inSampleSize *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Не удалось прочитать изображение")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull()
        val rotated = orient(bitmap, orientation)
        if (rotated !== bitmap) bitmap.recycle()
        try {
            compress(rotated)?.let { return it }
            val smaller = Bitmap.createScaledBitmap(rotated, (rotated.width / 2).coerceAtLeast(1), (rotated.height / 2).coerceAtLeast(1), true)
            try { return compress(smaller) ?: throw IllegalArgumentException("Не удалось уменьшить снимок") }
            finally { if (smaller !== rotated) smaller.recycle() }
        } finally { rotated.recycle() }
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        val output = ByteArrayOutputStream()
        for (quality in listOf(85, 72, 58, 40)) {
            output.reset()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() in 4..Photos.MAX_SIZE) return output.toByteArray()
        }
        return null
    }

    private fun orient(bitmap: Bitmap, orientation: Int?): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun decode(bytes: ByteArray, thumbnail: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            if (thumbnail) while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
