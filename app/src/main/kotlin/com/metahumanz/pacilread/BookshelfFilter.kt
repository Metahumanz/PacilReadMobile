package com.metahumanz.pacilread

import com.metahumanz.pacilread.model.BookRecord
import java.util.Locale

object BookshelfFilter {
    @JvmStatic
    fun matches(book: BookRecord?, query: String?, tag: String?, series: String?, status: String?): Boolean {
        if (book == null) return false
        val normalizedQuery = normalize(query)
        val queryMatch = normalizedQuery.isEmpty() ||
            normalize(book.title).contains(normalizedQuery) ||
            normalize(book.author).contains(normalizedQuery)
        val tagMatch = tag.isNullOrEmpty() || book.tags?.contains(tag) == true
        val seriesMatch = series.isNullOrEmpty() || safe(book.series) == series
        val statusMatch = status.isNullOrEmpty() || safe(book.readingStatus) == status
        return queryMatch && tagMatch && seriesMatch && statusMatch
    }

    private fun normalize(value: String?): String = safe(value).trim().lowercase(Locale.ROOT)

    private fun safe(value: String?): String = value ?: ""
}
