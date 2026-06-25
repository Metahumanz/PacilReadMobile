package com.metahumanz.pacilread.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

open class BookRecord {
    @JvmField var id = 0L
    @JvmField var title: String? = null
    @JvmField var author: String? = null
    @JvmField var localPath: String? = null
    @JvmField var coverPath: String? = null
    @JvmField var bookType: String? = null
    @JvmField var sourceDisplayName: String? = null
    @JvmField var contentSha256: String? = null
    @JvmField var readingStatsKey: String? = null
    @JvmField var progressIndex = 0
    @JvmField var progressOffset = 0
    @JvmField var lastReadAt = 0L
    @JvmField var pinned = false
    @JvmField var currentChapterTitle: String? = null
    @JvmField var chapterCount = 0
    @JvmField var createdAt = 0L
    @JvmField var updatedAt = 0L
    @JvmField var tags: MutableList<String>? = ArrayList()
    @JvmField var series: String? = ""
    @JvmField var seriesIndex: Double? = null
    @JvmField var readingStatus: String? = STATUS_UNREAD
    @JvmField var extraJson: JSONObject? = JSONObject()

    fun toJson(): JSONObject {
        val json = cloneJson(extraJson)
        try {
            json.put("id", id)
            json.put("title", title ?: "")
            json.put("author", author ?: "")
            json.put("bookType", bookType ?: "text")
            json.put("sourceDisplayName", sourceDisplayName ?: "")
            json.put("contentSha256", contentSha256 ?: "")
            json.put("readingStatsKey", readingStatsKey ?: "")
            json.put("progressIndex", progressIndex)
            json.put("progressOffset", progressOffset)
            json.put("lastReadAt", lastReadAt)
            json.put("pinned", pinned)
            json.put("chapterCount", chapterCount)
            json.put("currentChapterTitle", currentChapterTitle ?: "")
            json.put("createdAt", createdAt)
            json.put("updatedAt", updatedAt)
            json.put("tags", tagsToJson(tags))
            json.put("series", series ?: "")
            sanitizeSeriesIndex(seriesIndex)?.let { json.put("seriesIndex", it) } ?: json.remove("seriesIndex")
            json.put("readingStatus", normalizeReadingStatus(readingStatus, hasReadingProgress(progressIndex, progressOffset, lastReadAt)))
            json.put("coverFile", coverPath?.takeIf { it.isNotEmpty() }?.let { File(it).name } ?: "")
            json.put("sourceFile", localPath?.takeIf { it.isNotEmpty() }?.let { File(it).name } ?: "")
        } catch (_: Exception) {
        }
        return json
    }

    fun copyExtendedFieldsFrom(source: BookRecord?) {
        if (source == null) return
        tags = source.tags?.let(::ArrayList) ?: ArrayList()
        series = source.series ?: ""
        seriesIndex = sanitizeSeriesIndex(source.seriesIndex)
        readingStatus = normalizeReadingStatus(source.readingStatus, hasReadingProgress(progressIndex, progressOffset, lastReadAt))
        sourceDisplayName = source.sourceDisplayName ?: ""
        contentSha256 = source.contentSha256 ?: ""
        extraJson = cloneJson(source.extraJson)
    }

    companion object {
        const val STATUS_UNREAD = "unread"
        const val STATUS_READING = "reading"
        const val STATUS_FINISHED = "finished"
        private val KNOWN_FIELDS = setOf(
            "id", "title", "author", "localPath", "coverPath", "bookType", "sourceDisplayName",
            "contentSha256", "readingStatsKey", "progressIndex", "progressOffset", "lastReadAt", "pinned",
            "chapterCount", "currentChapterTitle", "createdAt", "updatedAt", "coverFile", "sourceFile", "tags",
            "series", "seriesIndex", "readingStatus",
        )

        @JvmStatic
        fun fromJson(json: JSONObject): BookRecord = BookRecord().apply {
            extraJson = collectExtraJson(json)
            id = json.optLong("id", 0)
            title = json.optString("title", "")
            author = json.optString("author", "")
            bookType = json.optString("bookType", "text")
            sourceDisplayName = json.optString("sourceDisplayName", "")
            contentSha256 = json.optString("contentSha256", "")
            readingStatsKey = json.optString("readingStatsKey", "")
            progressIndex = json.optInt("progressIndex", 0)
            progressOffset = json.optInt("progressOffset", 0)
            lastReadAt = json.optLong("lastReadAt", 0)
            pinned = json.optBoolean("pinned", false)
            chapterCount = json.optInt("chapterCount", 0)
            currentChapterTitle = json.optString("currentChapterTitle", "")
            createdAt = json.optLong("createdAt", 0)
            updatedAt = json.optLong("updatedAt", 0)
            tags = parseTags(json.optJSONArray("tags"))
            series = json.optString("series", "")
            seriesIndex = parseSeriesIndex(json)
            readingStatus = normalizeReadingStatus(
                json.optString("readingStatus", ""),
                hasReadingProgress(progressIndex, progressOffset, lastReadAt),
            )
            json.optString("coverFile", "").takeIf { it.isNotEmpty() }?.let { coverPath = it }
            json.optString("sourceFile", "").takeIf { it.isNotEmpty() }?.let { localPath = it }
        }

        @JvmStatic
        fun normalizeReadingStatus(value: String?, hasProgress: Boolean): String = when (value) {
            STATUS_READING, STATUS_FINISHED, STATUS_UNREAD -> value
            else -> if (hasProgress) STATUS_READING else STATUS_UNREAD
        }

        @JvmStatic
        fun hasReadingProgress(progressIndex: Int, progressOffset: Int, lastReadAt: Long): Boolean =
            progressIndex > 0 || progressOffset > 0 || lastReadAt > 0

        private fun parseTags(array: JSONArray?): MutableList<String> {
            val result = ArrayList<String>()
            if (array == null) return result
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim()
                if (value.isNotEmpty() && seen.add(value)) result.add(value)
            }
            return result
        }

        private fun tagsToJson(values: List<String>?): JSONArray {
            val array = JSONArray()
            if (values == null) return array
            val seen = HashSet<String>()
            for (value in values) {
                val safeValue = value.trim()
                if (safeValue.isNotEmpty() && seen.add(safeValue)) array.put(safeValue)
            }
            return array
        }

        private fun parseSeriesIndex(json: JSONObject?): Double? {
            if (json == null || !json.has("seriesIndex") || json.isNull("seriesIndex")) return null
            return sanitizeSeriesIndex(json.optDouble("seriesIndex", Double.NaN))
        }

        private fun sanitizeSeriesIndex(value: Double?): Double? =
            value?.takeUnless { it.isNaN() || it.isInfinite() }

        private fun collectExtraJson(source: JSONObject?): JSONObject {
            val extra = JSONObject()
            if (source == null) return extra
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (isKnownField(key)) continue
                try {
                    extra.put(key, source.opt(key))
                } catch (_: Exception) {
                }
            }
            return extra
        }

        private fun cloneJson(source: JSONObject?): JSONObject {
            if (source == null) return JSONObject()
            return try {
                JSONObject(source.toString())
            } catch (_: Exception) {
                JSONObject()
            }
        }

        private fun isKnownField(key: String?): Boolean = key in KNOWN_FIELDS
    }
}
