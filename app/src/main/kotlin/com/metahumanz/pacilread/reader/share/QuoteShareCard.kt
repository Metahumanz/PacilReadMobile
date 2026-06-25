package com.metahumanz.pacilread.reader.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.ContextThemeWrapper
import androidx.core.content.FileProvider
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.theme.ThemeModeHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object QuoteShareCard {
    private const val WIDTH = 1080
    private const val HORIZONTAL_PADDING = 96
    private const val QUOTE_TOP = 222
    private const val TARGET_QUOTE_HEIGHT = 1720
    private const val CONTEXT_LIMIT = 5
    private const val CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private val INLINE_SPACE_PATTERN = Regex("[\\t\\x0B\\f\\r ]+")
    private val EXCESS_NEWLINE_PATTERN = Regex("\\n{3,}")

    @JvmStatic
    @Throws(Exception::class)
    fun generate(
        context: Context, quote: String?, contextBefore: String?, contextAfter: String?, title: String?,
        author: String?, chapter: String?,
    ): GeneratedCard {
        val directory = File(context.cacheDir, "share_cards")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("无法创建分享缓存")
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS
        directory.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val bitmap = render(context, quote, contextBefore, contextAfter, title, author, chapter)
        val target = File(directory, "quote_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(target).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IllegalStateException("生成分享图片失败")
            }
        } catch (error: Exception) {
            bitmap.recycle()
            target.delete()
            throw error
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return GeneratedCard(bitmap, target, uri)
    }

    @JvmStatic
    fun createShareIntent(card: GeneratedCard?): Intent {
        if (card == null) throw IllegalArgumentException("分享图片不存在")
        val uri = requireNotNull(card.uri)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply {
                clipData = ClipData.newRawUri("引用分享卡", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(intent, "分享引用")
    }

    @JvmStatic
    fun contextExcerpt(chapterText: String?, selectionStart: Int, selectionEnd: Int): ContextExcerpt {
        val source = chapterText ?: ""
        val safeStart = Math.max(0, Math.min(selectionStart, source.length))
        val safeEnd = Math.max(safeStart, Math.min(selectionEnd, source.length))
        return ContextExcerpt(
            normalizeContext(source.substring(Math.max(0, safeStart - CONTEXT_LIMIT), safeStart)),
            normalizeContext(source.substring(safeEnd, Math.min(source.length, safeEnd + CONTEXT_LIMIT))),
        )
    }

    @JvmStatic
    fun render(
        context: Context, quote: String?, contextBefore: String?, contextAfter: String?, title: String?,
        author: String?, chapter: String?,
    ): Bitmap {
        val appTheme = ContextThemeWrapper(ThemeModeHelper.wrapForApp(context), ThemeModeHelper.resolveAppThemeResId(context))
        val backgroundColor = themedColor(appTheme, R.color.app_surface, Color.rgb(249, 249, 247))
        val quoteColor = themedColor(appTheme, R.color.app_text_primary, Color.rgb(35, 37, 40))
        val contextColor = blendColor(backgroundColor, themedColor(appTheme, R.color.app_text_muted, Color.rgb(151, 153, 156)), 0.34f)
        val sourceColor = themedColor(appTheme, R.color.app_text_secondary, Color.rgb(68, 70, 73))
        val metaColor = themedColor(appTheme, R.color.app_text_muted, Color.rgb(120, 122, 124))
        val dividerColor = themedColor(appTheme, R.color.app_border, Color.rgb(218, 219, 216))
        val accentColor = themedColor(appTheme, R.color.app_primary, Color.rgb(27, 97, 201))
        val safeQuote = quote?.trim().orEmpty()
        val safeBefore = normalizeContext(contextBefore)
        val safeAfter = normalizeContext(contextAfter)
        val contentWidth = WIDTH - HORIZONTAL_PADDING * 2

        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = quoteColor
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        var quoteLayout: StaticLayout? = null
        var textSize = 56f
        while (textSize >= 34f) {
            quotePaint.textSize = textSize
            quoteLayout = layout(safeQuote, quotePaint, contentWidth, 1.35f)
            if (quoteLayout.height <= TARGET_QUOTE_HEIGHT) break
            textSize -= 2f
        }
        val finalQuoteLayout = quoteLayout ?: layout(safeQuote, quotePaint, contentWidth, 1.35f)
        val contextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = contextColor
            textSize = 36f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val beforeLayout = if (safeBefore.isEmpty()) null else layout(safeBefore, contextPaint, contentWidth, 1.32f)
        val afterLayout = if (safeAfter.isEmpty()) null else layout(safeAfter, contextPaint, contentWidth, 1.32f)
        val sourcePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = sourceColor
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        val sourceLayout = layout("《${safe(title, "未命名书籍")}》", sourcePaint, contentWidth, 1.1f)
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = metaColor
            textSize = 26f
        }
        val metaLayout = layout(joinMeta(chapter, author), metaPaint, contentWidth, 1.15f)

        var contentY = QUOTE_TOP
        val beforeY = contentY
        if (beforeLayout != null) contentY += beforeLayout.height + 34
        val quoteY = contentY
        contentY += finalQuoteLayout.height
        val afterY = contentY + if (afterLayout == null) 0 else 34
        if (afterLayout != null) contentY = afterY + afterLayout.height
        val footerHeight = 42 + sourceLayout.height + 24 + metaLayout.height + 72
        val height = contentY + 88 + footerHeight
        val dividerY = height - footerHeight
        val sourceY = dividerY + 42
        val metaY = sourceY + sourceLayout.height + 24

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRect(HORIZONTAL_PADDING.toFloat(), 92f, (HORIZONTAL_PADDING + 10).toFloat(), 196f, accentPaint)
        val quoteMarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentPaint.color
            textSize = 112f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("“", (HORIZONTAL_PADDING + 28).toFloat(), 180f, quoteMarkPaint)
        beforeLayout?.let { drawLayout(canvas, it, HORIZONTAL_PADDING, beforeY) }
        drawLayout(canvas, finalQuoteLayout, HORIZONTAL_PADDING, quoteY)
        afterLayout?.let { drawLayout(canvas, it, HORIZONTAL_PADDING, afterY) }
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dividerColor
            strokeWidth = 2f
        }
        canvas.drawLine(HORIZONTAL_PADDING.toFloat(), dividerY.toFloat(), (WIDTH - HORIZONTAL_PADDING).toFloat(), dividerY.toFloat(), divider)
        drawLayout(canvas, sourceLayout, HORIZONTAL_PADDING, sourceY)
        drawLayout(canvas, metaLayout, HORIZONTAL_PADDING, metaY)
        return bitmap
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Int, y: Int) {
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, paint: TextPaint, width: Int, spacingMultiplier: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setLineSpacing(0f, spacingMultiplier).build()

    private fun joinMeta(chapter: String?, author: String?): String {
        val safeChapter = safe(chapter, "")
        val safeAuthor = safe(author, "")
        return when {
            safeChapter.isEmpty() -> safeAuthor
            safeAuthor.isEmpty() -> safeChapter
            else -> "$safeChapter  ·  $safeAuthor"
        }
    }

    private fun safe(value: String?, fallback: String): String = if (value.isNullOrBlank()) fallback else value.trim()
    private fun normalizeContext(value: String?): String = value?.trim()?.replace(INLINE_SPACE_PATTERN, " ")
        ?.replace(EXCESS_NEWLINE_PATTERN, "\n\n").orEmpty()
    private fun themedColor(context: Context, colorRes: Int, fallback: Int): Int =
        ThemeModeHelper.resolveColor(context, colorRes).let { if (it == 0) fallback else it }
    private fun blendColor(background: Int, foreground: Int, foregroundRatio: Float): Int {
        val ratio = Math.max(0f, Math.min(1f, foregroundRatio))
        val backgroundRatio = 1f - ratio
        return Color.rgb(
            Math.round(Color.red(background) * backgroundRatio + Color.red(foreground) * ratio),
            Math.round(Color.green(background) * backgroundRatio + Color.green(foreground) * ratio),
            Math.round(Color.blue(background) * backgroundRatio + Color.blue(foreground) * ratio),
        )
    }

    class ContextExcerpt(before: String?, after: String?) {
        @JvmField val before: String = before ?: ""
        @JvmField val after: String = after ?: ""
    }

    class GeneratedCard(
        @JvmField val bitmap: Bitmap?,
        @JvmField val file: File?,
        @JvmField val uri: Uri?,
    ) {
        fun fileName(): String = file?.name ?: "PacilRead-引用分享.png"

        @Throws(Exception::class)
        fun writeTo(context: Context?, destination: Uri?) {
            val sourceFile = file
            if (context == null || destination == null || sourceFile == null || !sourceFile.isFile) {
                throw IllegalStateException("分享图片不存在")
            }
            FileInputStream(sourceFile).use { input ->
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("无法打开保存位置")
                output.use {
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) it.write(buffer, 0, read)
                }
            }
        }

        fun recyclePreview() {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }
}
