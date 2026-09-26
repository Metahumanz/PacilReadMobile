package com.metahumanz.pacilread.sync

import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun buildRemoteBookIdMapping(
    remoteBooks: JSONArray,
    localBooks: List<BookRecord>,
): Map<Long, Long> {
    val byReadingKey = HashMap<String, BookRecord>()
    val byTitleAuthor = HashMap<String, BookRecord>()
    for (book in localBooks) {
        if (!book.readingStatsKey.isNullOrEmpty()) byReadingKey[book.readingStatsKey!!] = book
        byReadingKey[ReadingStatsUtils.buildBookIdentity(book.title, book.author)] = book
        byReadingKey[ReadingStatsUtils.buildLegacyAndroidBookIdentity(book.title, book.author)] = book
        byTitleAuthor[bookIdentity(book.title, book.author)] = book
    }

    val mapping = HashMap<Long, Long>()
    for (index in 0 until remoteBooks.length()) {
        val remote = remoteBooks.optJSONObject(index) ?: continue
        val remoteId = remote.optLong("id", 0L)
        if (remoteId <= 0L) continue
        val readingKey = remote.optString("readingStatsKey", "")
        val match = if (readingKey.isNotEmpty()) {
            byReadingKey[readingKey]
        } else {
            null
        } ?: byTitleAuthor[bookIdentity(remote.optString("title", ""), remote.optString("author", ""))]
        if (match != null) mapping[remoteId] = match.id
    }
    return mapping
}

fun changedAssetKeys(first: JSONObject?, second: JSONObject?): Set<String> {
    val keys = HashSet<String>()
    first?.keys()?.let { iterator -> while (iterator.hasNext()) keys.add(iterator.next()) }
    second?.keys()?.let { iterator -> while (iterator.hasNext()) keys.add(iterator.next()) }
    return keys.filterTo(HashSet()) { key ->
        val left = first?.optJSONObject(key)
        val right = second?.optJSONObject(key)
        left == null || right == null || left.optLong("size", -1L) != right.optLong("size", -1L) ||
            left.optString("sha256", "") != right.optString("sha256", "")
    }
}

fun remapChapterTextPathForBook(path: String?, remoteBookId: Long, localBookId: Long): String? {
    if (path.isNullOrBlank() || remoteBookId == localBookId) return path?.trim()
    val trimmed = path.trim()
    val prefix = if (trimmed.startsWith("chapter_text/")) "chapter_text/" else ""
    val body = trimmed.removePrefix(prefix)
    val remotePrefix = "book_$remoteBookId/"
    return if (body.startsWith(remotePrefix)) {
        prefix + "book_$localBookId/" + body.removePrefix(remotePrefix)
    } else {
        trimmed
    }
}

private fun bookIdentity(title: String?, author: String?): String =
    (title?.trim()?.lowercase(Locale.ROOT) ?: "") + "::" + (author?.trim()?.lowercase(Locale.ROOT) ?: "")
