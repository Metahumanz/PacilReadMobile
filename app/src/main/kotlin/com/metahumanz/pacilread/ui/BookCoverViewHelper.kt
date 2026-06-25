package com.metahumanz.pacilread.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.metahumanz.pacilread.R
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object BookCoverViewHelper {
    private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    private val COVER_EXECUTOR = Executors.newFixedThreadPool(2)
    private val COVER_CACHE = object : LruCache<String, Bitmap>(maxCacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @JvmStatic
    fun bindCover(coverView: ImageView?, fallbackView: TextView?, coverPath: String?, title: String?) {
        if (coverView == null) {
            fallbackView?.apply {
                text = initialsFor(title)
                visibility = View.VISIBLE
            }
            return
        }
        val fallbackText = initialsFor(title)
        val cacheKey = cacheKeyFor(coverView, coverPath)
        coverView.setTag(R.id.tag_book_cover_request, cacheKey)
        if (cacheKey == null) {
            coverView.setImageDrawable(null)
            fallbackView?.apply {
                text = fallbackText
                visibility = View.VISIBLE
            }
            return
        }

        val cached = COVER_CACHE.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            coverView.setImageBitmap(cached)
            fallbackView?.visibility = View.GONE
            return
        }
        coverView.setImageDrawable(null)
        fallbackView?.apply {
            text = fallbackText
            visibility = View.VISIBLE
        }

        val targetWidth = CoverDecodeSizing.targetSize(coverView.width)
        val targetHeight = CoverDecodeSizing.targetSize(coverView.height)
        try {
            COVER_EXECUTOR.execute {
                decodeCover(coverPath, targetWidth, targetHeight)?.let { COVER_CACHE.put(cacheKey, it) }
                MAIN_HANDLER.post {
                    if (cacheKey != coverView.getTag(R.id.tag_book_cover_request)) return@post
                    val latest = COVER_CACHE.get(cacheKey)
                    if (latest != null && !latest.isRecycled) {
                        coverView.setImageBitmap(latest)
                        fallbackView?.visibility = View.GONE
                    } else {
                        coverView.setImageDrawable(null)
                        fallbackView?.apply {
                            text = fallbackText
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Activity shutdown can race with adapter binding; keep the fallback visible.
        }
    }

    private fun decodeCover(path: String?, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverDecodeSizing.sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun cacheKeyFor(coverView: ImageView, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return "${file.absolutePath}#${file.lastModified()}:${file.length()}:" +
            "${CoverDecodeSizing.targetSize(coverView.width)}x${CoverDecodeSizing.targetSize(coverView.height)}"
    }

    private fun maxCacheSizeBytes(): Int {
        val runtimeCache = Runtime.getRuntime().maxMemory() / 16L
        return Math.max(4L * 1024L * 1024L, Math.min(runtimeCache, 24L * 1024L * 1024L)).toInt()
    }

    private fun initialsFor(title: String?): String {
        if (title.isNullOrBlank()) return "PR"
        val trimmed = title.trim()
        if (trimmed.length == 1) return trimmed.uppercase(Locale.ROOT)
        return trimmed.substring(0, Math.min(2, trimmed.length)).uppercase(Locale.ROOT)
    }
}
