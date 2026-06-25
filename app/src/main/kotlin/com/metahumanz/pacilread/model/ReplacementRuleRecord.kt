package com.metahumanz.pacilread.model

import org.json.JSONObject

class ReplacementRuleRecord {
    @JvmField var id: Long = 0
    @JvmField var pattern: String? = null
    @JvmField var replacement: String? = null
    @JvmField var scope: String? = null
    @JvmField var bookId: Long? = null
    @JvmField var regex: Boolean = false
    @JvmField var active: Boolean = false
    @JvmField var updatedAt: Long = 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("pattern", pattern ?: "")
            json.put("replacement", replacement ?: "")
            json.put("scope", scope ?: "global")
            bookId?.let { json.put("bookId", it) }
            json.put("regex", regex)
            json.put("active", active)
            json.put("updatedAt", updatedAt)
        } catch (_: Exception) {
        }
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ReplacementRuleRecord = ReplacementRuleRecord().apply {
            id = json.optLong("id", 0)
            pattern = json.optString("pattern", "")
            replacement = json.optString("replacement", "")
            scope = json.optString("scope", "global")
            if (json.has("bookId") && !json.isNull("bookId")) bookId = json.optLong("bookId", -1)
            regex = json.optBoolean("regex", false)
            active = json.optBoolean("active", true)
            updatedAt = json.optLong("updatedAt", 0)
        }
    }
}
