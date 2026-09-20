package com.metahumanz.pacilread.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotProtocolTest {
    private val generationId = "123-abc"
    private val prefix = snapshotPrefix(generationId)
    private val manifest = JSONObject().apply {
        put("generationId", generationId)
        put("snapshotPrefix", prefix)
    }
    private val manifestText = manifest.toString(2)

    @Test fun validCommitResolves() {
        val commit = buildSnapshotCommit(generationId, prefix, manifestText, 123L)
        val pointer = validateSnapshotPointer(commit, manifest, manifestText)
        assertEquals(generationId, pointer.generationId)
        assertEquals(prefix, pointer.snapshotPrefix)
        assertEquals(123L, commit.optLong("committedAt"))
    }

    @Test fun generationMismatchFails() = assertInvalid(buildSnapshotCommit("other", prefix, manifestText, 1L))
    @Test fun prefixMismatchFails() = assertInvalid(buildSnapshotCommit(generationId, "snapshots/other", manifestText, 1L))
    @Test fun shaMismatchFails() = assertInvalid(JSONObject().apply {
        put("generationId", generationId)
        put("snapshotPrefix", prefix)
        put("manifestSha256", "bad")
    })

    private fun assertInvalid(commit: JSONObject) {
        assertThrows(IllegalStateException::class.java) {
            validateSnapshotPointer(commit, manifest, manifestText)
        }
    }
}
