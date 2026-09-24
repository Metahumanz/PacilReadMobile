package com.metahumanz.pacilread.sync

import org.json.JSONObject

/** A summary of local data that differs from the current remote manifest. */
data class SyncBackupPlan(
    val changedFiles: List<String>,
    val changedAssets: List<String>,
    val uploadSize: Long,
)

internal fun buildSyncBackupPlan(
    localFiles: JSONObject,
    remoteFiles: JSONObject?,
    localAssets: JSONObject,
    remoteAssets: JSONObject?,
): SyncBackupPlan {
    val changedFiles = changedManifestFiles(localFiles, remoteFiles)
    val changedAssets = changedAssetKeys(localAssets, remoteAssets).sorted()
    var uploadSize = 0L
    for (fileName in changedFiles) {
        uploadSize += localFiles.optJSONObject(fileName)?.optLong("size", 0L)?.coerceAtLeast(0L) ?: 0L
    }
    for (assetKey in changedAssets) {
        uploadSize += localAssets.optJSONObject(assetKey)?.optLong("size", 0L)?.coerceAtLeast(0L) ?: 0L
    }
    return SyncBackupPlan(changedFiles, changedAssets, uploadSize)
}

private fun changedManifestFiles(localFiles: JSONObject, remoteFiles: JSONObject?): List<String> {
    val changed = ArrayList<String>()
    val keys = localFiles.keys()
    while (keys.hasNext()) {
        val fileName = keys.next()
        val localEntry = localFiles.optJSONObject(fileName) ?: continue
        val remoteEntry = remoteFiles?.optJSONObject(fileName)
        if (remoteEntry == null || localEntry.optString("sha256", "") != remoteEntry.optString("sha256", "")) {
            changed.add(fileName)
        }
    }
    return changed.sorted()
}
