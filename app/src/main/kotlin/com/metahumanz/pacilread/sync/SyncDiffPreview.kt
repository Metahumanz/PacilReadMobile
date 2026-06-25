package com.metahumanz.pacilread.sync

import org.json.JSONArray
import org.json.JSONObject

class SyncDiffPreview(@JvmField val mode: String?) {
    @JvmField val items: MutableList<SyncDiffItem> = ArrayList()
    @JvmField val remoteEntities: MutableMap<String, JSONArray> = HashMap()
    @JvmField var remoteManifest: JSONObject? = null

    fun countStatus(status: String): Int = items.count { status == it.status }

    fun conflictCount(): Int = countStatus(SyncDiffItem.STATUS_CONFLICT)
    fun remoteCount(): Int = countStatus(SyncDiffItem.STATUS_REMOTE)
    fun localCount(): Int = countStatus(SyncDiffItem.STATUS_LOCAL)
    fun unchangedCount(): Int = countStatus(SyncDiffItem.STATUS_UNCHANGED)

    companion object {
        const val MODE_FULL = "full"
        const val MODE_INCREMENTAL = "incremental"
    }
}
