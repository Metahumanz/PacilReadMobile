package com.metahumanz.pacilread.sync

import android.content.Context
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import org.json.JSONArray
import org.json.JSONObject

open class ReadingStatsSyncManager(
    context: Context,
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
    private val webDavClient: WebDavClient,
) {
    @Suppress("unused")
    private val context = context.applicationContext

    fun canAutoSync(): Boolean =
        settingsStore.isReadingTimeTrackingEnabled && settingsStore.isWebDavEnabled && settingsStore.isWebDavSyncReadingStatsEnabled

    @Throws(Exception::class)
    fun uploadLocalReadingStatsSnapshot() {
        if (!settingsStore.isWebDavEnabled || !settingsStore.isWebDavSyncReadingStatsEnabled) return
        webDavClient.ensureReadingStatsDirectory()
        val deviceId = settingsStore.readingStatsDeviceId
        val rows = databaseHelper.getReadingStatsRowsForSync(deviceId)
        val payload = JSONObject()
        payload.put("schemaVersion", SCHEMA_VERSION)
        payload.put("deviceId", deviceId)
        payload.put("generatedAt", System.currentTimeMillis())
        val items = JSONArray()
        for (row in rows) {
            items.put(JSONObject().apply {
                put("date", row.date)
                put("sourceDeviceId", row.sourceDeviceId)
                put("bookIdentity", row.bookIdentity)
                put("bookTitle", row.bookTitle)
                put("bookAuthor", row.bookAuthor)
                put("durationSeconds", row.durationSeconds)
                put("charCount", row.charCount)
                put("updatedAt", row.updatedAt)
            })
        }
        payload.put("rows", items)
        webDavClient.uploadText(currentDeviceRemotePath(), payload.toString(2), "application/json; charset=utf-8")
    }

    @Throws(Exception::class)
    fun downloadAndMergeReadingStats() {
        if (!settingsStore.isWebDavEnabled || !settingsStore.isWebDavSyncReadingStatsEnabled) return
        webDavClient.ensureReadingStatsDirectory()
        val files = webDavClient.listFiles(webDavClient.readingStatsBaseUrl())
        val mergedRows = ArrayList<ReadingTimeEntryRecord>()
        for (remoteFile in files) {
            if (!remoteFile.endsWith(".json")) continue
            val payload = JSONObject(webDavClient.downloadText(remoteFile))
            val rows = payload.optJSONArray("rows") ?: continue
            for (index in 0 until rows.length()) {
                val rowObject = rows.optJSONObject(index) ?: continue
                val record = ReadingTimeEntryRecord().apply {
                    date = rowObject.optString("date", "")
                    sourceDeviceId = rowObject.optString("sourceDeviceId", payload.optString("deviceId", ""))
                    bookIdentity = rowObject.optString("bookIdentity", ReadingStatsUtils.LEGACY_BOOK_IDENTITY)
                    bookTitle = rowObject.optString("bookTitle", ReadingStatsUtils.LEGACY_BOOK_TITLE)
                    bookAuthor = rowObject.optString("bookAuthor", "")
                    durationSeconds = rowObject.optInt("durationSeconds", 0)
                    charCount = rowObject.optInt("charCount", 0)
                    updatedAt = rowObject.optLong("updatedAt", 0L)
                }
                if (record.date.isNullOrBlank() || record.bookIdentity.isNullOrBlank()) continue
                mergedRows.add(record)
            }
        }
        databaseHelper.mergeReadingStatsRows(mergedRows)
    }

    @Throws(Exception::class)
    fun clearRemoteReadingStats() {
        if (!settingsStore.isWebDavEnabled || settingsStore.webDavUrl.isBlank()) return
        val files = webDavClient.listFiles(webDavClient.readingStatsBaseUrl())
        for (file in files) if (file.endsWith(".json")) webDavClient.delete(file)
    }

    fun currentDeviceRemotePath(): String =
        webDavClient.readingStatsBaseUrl() + "device-" + sanitize(settingsStore.readingStatsDeviceId) + ".json"

    private fun sanitize(value: String?): String =
        if (value.isNullOrBlank()) "unknown-device" else value.replace(Regex("[\\\\/:\"*?<>|]"), "_")

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
