package com.metahumanz.pacilread.model

import org.json.JSONObject

class ReadingTimeEntryRecord {
    @JvmField var id: Long = 0
    @JvmField var date: String? = null
    @JvmField var sourceDeviceId: String? = null
    @JvmField var bookIdentity: String? = null
    @JvmField var bookTitle: String? = null
    @JvmField var bookAuthor: String? = null
    @JvmField var durationSeconds: Int = 0
    @JvmField var charCount: Int = 0
    @JvmField var updatedAt: Long = 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("date", date ?: "")
            json.put("sourceDeviceId", sourceDeviceId ?: "")
            json.put("bookIdentity", bookIdentity ?: "")
            json.put("bookTitle", bookTitle ?: "")
            json.put("bookAuthor", bookAuthor ?: "")
            json.put("durationSeconds", durationSeconds)
            json.put("charCount", charCount)
            json.put("updatedAt", updatedAt)
        } catch (_: Exception) {
        }
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ReadingTimeEntryRecord = ReadingTimeEntryRecord().apply {
            id = json.optLong("id", 0)
            date = json.optString("date", "")
            sourceDeviceId = json.optString("sourceDeviceId", "")
            bookIdentity = json.optString("bookIdentity", "")
            bookTitle = json.optString("bookTitle", "")
            bookAuthor = json.optString("bookAuthor", "")
            durationSeconds = json.optInt("durationSeconds", 0)
            charCount = json.optInt("charCount", 0)
            updatedAt = json.optLong("updatedAt", 0)
        }
    }
}
