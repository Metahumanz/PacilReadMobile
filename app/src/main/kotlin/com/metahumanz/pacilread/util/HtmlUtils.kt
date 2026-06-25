package com.metahumanz.pacilread.util

import android.text.Html
import android.text.TextUtils
import java.util.Locale

object HtmlUtils {
    @JvmStatic
    fun toParagraphHtml(text: String?): String {
        val normalized = normalizeText(text)
        val paragraphs = ArrayList<String>()
        val block = StringBuilder()
        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (block.isNotEmpty()) {
                    paragraphs.add(block.toString().trim())
                    block.setLength(0)
                }
                continue
            }
            if (block.isNotEmpty()) block.append('\n')
            block.append(line)
        }
        if (block.isNotEmpty()) paragraphs.add(block.toString().trim())
        if (paragraphs.isEmpty() && normalized.trim().isNotEmpty()) paragraphs.add(normalized.trim())

        val html = StringBuilder()
        for (paragraph in paragraphs) {
            if (paragraph.isEmpty()) continue
            html.append("<p>")
                .append(TextUtils.htmlEncode(paragraph).replace("\n", "<br/>"))
                .append("</p>")
        }
        return html.toString()
    }

    @JvmStatic
    fun normalizeText(text: String?): String = text?.replace("\r\n", "\n")?.replace('\r', '\n') ?: ""

    @JvmStatic
    fun stripHtml(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00A0', ' ').trim()
    }

    @JvmStatic
    fun extractBodyFragment(html: String?): String {
        if (html == null) return ""
        val lower = html.lowercase(Locale.ROOT)
        val bodyStart = lower.indexOf("<body")
        if (bodyStart >= 0) {
            val bodyTagEnd = lower.indexOf('>', bodyStart)
            val bodyEnd = lower.lastIndexOf("</body>")
            if (bodyTagEnd >= 0 && bodyEnd > bodyTagEnd) return html.substring(bodyTagEnd + 1, bodyEnd).trim()
        }
        return html.trim()
    }

    @JvmStatic
    fun firstMeaningfulHeading(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        val lower = html.lowercase(Locale.ROOT)
        for (tag in arrayOf("h1", "h2", "h3", "title")) {
            val open = "<$tag"
            val close = "</$tag>"
            var start = lower.indexOf(open)
            while (start >= 0) {
                val openEnd = lower.indexOf('>', start)
                val end = lower.indexOf(close, openEnd + 1)
                if (openEnd >= 0 && end > openEnd) {
                    val text = stripHtml(html.substring(openEnd + 1, end)).trim()
                    if (text.isNotEmpty()) return text
                }
                start = lower.indexOf(open, start + 1)
            }
        }
        return ""
    }

    @JvmStatic
    fun pruneUnsupportedMarkup(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        var cleaned = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        cleaned = cleaned.replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        cleaned = cleaned.replace(Regex("(?is)<img[^>]*>"), "")
        cleaned = cleaned.replace(Regex("(?is)<svg[^>]*>.*?</svg>"), "")
        cleaned = cleaned.replace(Regex("(?is)<audio[^>]*>.*?</audio>"), "")
        cleaned = cleaned.replace(Regex("(?is)<video[^>]*>.*?</video>"), "")
        return cleaned.trim()
    }
}
