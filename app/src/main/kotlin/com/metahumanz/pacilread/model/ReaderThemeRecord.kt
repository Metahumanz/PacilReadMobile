package com.metahumanz.pacilread.model

import org.json.JSONObject

class ReaderThemeRecord {
    @JvmField var id: Long = 0
    @JvmField var name: String? = null
    @JvmField var configJson: String? = null
    @JvmField var updatedAt: Long = 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("name", name ?: "")
            json.put("configJson", configJson ?: "{}")
            json.put("updatedAt", updatedAt)
        } catch (_: Exception) {
        }
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ReaderThemeRecord = ReaderThemeRecord().apply {
            id = json.optLong("id", 0)
            name = json.optString("name", "")
            configJson = json.optString("configJson", "{}")
            updatedAt = json.optLong("updatedAt", 0)
        }
    }
}
