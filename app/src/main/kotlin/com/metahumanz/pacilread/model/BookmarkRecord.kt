package com.metahumanz.pacilread.model

import org.json.JSONObject

class BookmarkRecord {
    @JvmField var id: Long = 0
    @JvmField var uuid: String? = null
    @JvmField var bookId: Long = 0
    @JvmField var bookIdentity: String? = null
    @JvmField var bookTitle: String? = null
    @JvmField var bookAuthor: String? = null
    @JvmField var chapterOrderIndex: Int = 0
    @JvmField var chapterTitle: String? = null
    @JvmField var chapterOffset: Int = 0
    @JvmField var progressPercent: Float = 0f
    @JvmField var summary: String? = null
    @JvmField var createdAt: Long = 0
    @JvmField var updatedAt: Long = 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("uuid", uuid ?: "")
            json.put("bookId", bookId)
            json.put("bookIdentity", bookIdentity ?: "")
            json.put("bookTitle", bookTitle ?: "")
            json.put("bookAuthor", bookAuthor ?: "")
            json.put("chapterOrderIndex", chapterOrderIndex)
            json.put("chapterTitle", chapterTitle ?: "")
            json.put("chapterOffset", chapterOffset)
            json.put("progressPercent", progressPercent.toDouble())
            json.put("summary", summary ?: "")
            json.put("createdAt", createdAt)
            json.put("updatedAt", updatedAt)
        } catch (_: Exception) {
        }
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): BookmarkRecord = BookmarkRecord().apply {
            id = json.optLong("id", 0)
            uuid = json.optString("uuid", "")
            bookId = json.optLong("bookId", -1)
            bookIdentity = json.optString("bookIdentity", "")
            bookTitle = json.optString("bookTitle", "")
            bookAuthor = json.optString("bookAuthor", "")
            chapterOrderIndex = json.optInt("chapterOrderIndex", 0)
            chapterTitle = json.optString("chapterTitle", "")
            chapterOffset = json.optInt("chapterOffset", 0)
            progressPercent = json.optDouble("progressPercent", 0.0).toFloat()
            summary = json.optString("summary", "")
            createdAt = json.optLong("createdAt", 0)
            updatedAt = json.optLong("updatedAt", 0)
        }
    }
}
