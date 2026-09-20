package com.metahumanz.pacilread.sync

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class SnapshotPointer(
    val generationId: String,
    val snapshotPrefix: String,
    val manifestSha256: String,
)

fun snapshotPrefix(generationId: String): String = "snapshots/$generationId"

fun sha256Text(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") {
        String.format(Locale.ROOT, "%02x", it)
    }
}

fun buildSnapshotCommit(
    generationId: String,
    snapshotPrefix: String,
    manifestText: String,
    committedAt: Long,
): JSONObject = JSONObject().apply {
    put("schemaVersion", 1)
    put("generationId", generationId)
    put("snapshotPrefix", snapshotPrefix)
    put("manifestSha256", sha256Text(manifestText))
    put("committedAt", committedAt)
}

fun validateSnapshotPointer(
    commit: JSONObject,
    manifest: JSONObject,
    manifestText: String,
): SnapshotPointer {
    val generationId = commit.optString("generationId", "").trim()
    val prefix = commit.optString("snapshotPrefix", "").trim()
    val hash = commit.optString("manifestSha256", "").trim()
    if (generationId.isEmpty() || prefix.isEmpty() || hash.isEmpty() ||
        manifest.optString("generationId", "").trim() != generationId ||
        manifest.optString("snapshotPrefix", "").trim() != prefix ||
        !hash.equals(sha256Text(manifestText), ignoreCase = true)
    ) {
        throw IllegalStateException("完整快照提交信息不一致")
    }
    return SnapshotPointer(generationId, prefix, hash)
}
