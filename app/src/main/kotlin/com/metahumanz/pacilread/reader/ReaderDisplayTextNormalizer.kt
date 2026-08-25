package com.metahumanz.pacilread.reader

/**
 * Builds an offset-preserving display copy of reader text.
 *
 * Imported books often contain their own paragraph indentation. The reader applies indentation
 * with a [android.text.style.LeadingMarginSpan], so keeping those source spaces visible would
 * stack both indents. Replacing them one-for-one keeps progress, bookmark, selection and TTS
 * offsets stable while making the configured indentation the only visible indentation.
 */
object ReaderDisplayTextNormalizer {
    internal const val MASKED_INDENT_CHAR = '\u2060'

    @JvmStatic
    fun maskParagraphLeadingWhitespace(source: String?): String {
        val text = source ?: ""
        if (text.isEmpty()) return text

        var masked: StringBuilder? = null
        var lineStart = 0
        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline >= 0) newline else text.length
            var contentStart = lineStart
            while (contentStart < lineEnd && isParagraphWhitespace(text[contentStart])) {
                contentStart++
            }

            // Preserve whitespace-only lines: they still carry the source's blank-line structure.
            if (contentStart > lineStart && contentStart < lineEnd) {
                val target = masked ?: StringBuilder(text).also { masked = it }
                for (index in lineStart until contentStart) {
                    target.setCharAt(index, MASKED_INDENT_CHAR)
                }
            }

            if (newline < 0) break
            lineStart = newline + 1
        }
        return masked?.toString() ?: text
    }

    internal fun isParagraphWhitespace(value: Char): Boolean =
        value == '\uFEFF' || Character.isWhitespace(value) || Character.isSpaceChar(value)
}
