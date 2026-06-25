package com.metahumanz.pacilread.model

import org.json.JSONObject

class ChapterRecord {
    @JvmField var id: Long = 0
    @JvmField var bookId: Long = 0
    @JvmField var title: String? = null
    @JvmField var bodyHtml: String? = null
    @JvmField var bodyText: String? = null
    @JvmField var orderIndex: Int = 0
    @JvmField var bodyTextPath: String? = null
    @JvmField var bodyTextStorage: String? = "db"
    @JvmField var bodyTextSize: Long = 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("bookId", bookId)
            json.put("title", title ?: "")
            json.put("orderIndex", orderIndex)
            json.put("bodyTextPath", bodyTextPath ?: "")
            json.put("bodyTextStorage", bodyTextStorage ?: "db")
            json.put("bodyTextSize", bodyTextSize)
        } catch (_: Exception) {
        }
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ChapterRecord = ChapterRecord().apply {
            id = json.optLong("id", 0)
            bookId = json.optLong("bookId", 0)
            title = json.optString("title", "")
            orderIndex = json.optInt("orderIndex", 0)
            bodyTextPath = json.optString("bodyTextPath", "")
            bodyTextStorage = json.optString("bodyTextStorage", "db")
            bodyTextSize = json.optLong("bodyTextSize", 0)
            bodyHtml = ""
            bodyText = ""
        }
    }
}
