package com.metahumanz.pacilread.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID

object CoverImageStore {
    private const val MAX_COVER_DIMENSION_PX = 900
    private const val JPEG_QUALITY = 85

    @JvmStatic
    @Throws(IOException::class)
    fun saveCompressedCover(context: Context, uri: Uri, prefix: String?): File {
        context.applicationContext.contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) throw IOException("无法读取封面图片")
            return saveCompressedCover(context, readAllBytes(inputStream), prefix)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveCompressedCover(context: Context, sourceFile: File?, prefix: String?): File {
        if (sourceFile == null || !sourceFile.exists()) throw IOException("封面文件不存在")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptionsFor(bounds))
        return saveBitmap(context, bitmap, prefix)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveCompressedCover(context: Context, imageBytes: ByteArray?, prefix: String?): File {
        if (imageBytes == null || imageBytes.isEmpty()) throw IOException("封面图片为空")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptionsFor(bounds))
        return saveBitmap(context, bitmap, prefix)
    }

    @Throws(IOException::class)
    private fun decodeOptionsFor(bounds: BitmapFactory.Options): BitmapFactory.Options {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("封面图片格式不受支持")
        return BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        val longest = Math.max(width, height)
        while (longest / sampleSize > MAX_COVER_DIMENSION_PX * 2) sampleSize *= 2
        return sampleSize
    }

    @Throws(IOException::class)
    private fun saveBitmap(context: Context, bitmap: Bitmap?, prefix: String?): File {
        if (bitmap == null) throw IOException("封面图片解码失败")
        val scaled = scaleDown(bitmap)
        val flattened = flattenOnWhite(scaled)
        val destination = destinationFile(context, prefix)
        try {
            FileOutputStream(destination).use { outputStream ->
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                    throw IOException("封面图片压缩失败")
                }
            }
        } finally {
            if (flattened !== scaled) flattened.recycle()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        return destination
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = Math.max(width, height)
        if (longest <= MAX_COVER_DIMENSION_PX) return bitmap
        val scale = MAX_COVER_DIMENSION_PX / longest.toFloat()
        val targetWidth = Math.max(1, Math.round(width * scale))
        val targetHeight = Math.max(1, Math.round(height * scale))
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return flattened
    }

    @Throws(IOException::class)
    private fun destinationFile(context: Context, prefix: String?): File {
        val folder = File(context.applicationContext.filesDir, "covers")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建封面目录: ${folder.absolutePath}")
        return File(folder, "${safePrefix(prefix)}_${UUID.randomUUID()}.jpg")
    }

    private fun safePrefix(prefix: String?): String {
        var value = prefix?.trim()?.lowercase(Locale.ROOT) ?: "cover"
        value = value.replace(Regex("[^a-z0-9_-]+"), "_")
        return value.ifEmpty { "cover" }
    }

    @Throws(IOException::class)
    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) outputStream.write(buffer, 0, read)
        return outputStream.toByteArray()
    }
}
