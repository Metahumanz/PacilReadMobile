package com.metahumanz.pacilread.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalBackupTest {
    @Test fun unchangedManifestsProduceEmptyPlanAndZeroUpload() {
        val files = JSONObject().put("books.json", fileEntry("same", 12))
        val assets = JSONObject().put("covers/book.jpg", fileEntry("cover", 24))

        val plan = buildSyncBackupPlan(files, JSONObject(files.toString()), assets, JSONObject(assets.toString()))

        assertEquals(emptyList<String>(), plan.changedFiles)
        assertEquals(emptyList<String>(), plan.changedAssets)
        assertEquals(0L, plan.uploadSize)
    }

    @Test fun changedJsonIsIncludedInPlanAndUploadSize() {
        val files = JSONObject().put("books.json", fileEntry("new", 31))
        val remoteFiles = JSONObject().put("books.json", fileEntry("old", 12))

        val plan = buildSyncBackupPlan(files, remoteFiles, JSONObject(), JSONObject())

        assertEquals(listOf("books.json"), plan.changedFiles)
        assertEquals(emptyList<String>(), plan.changedAssets)
        assertEquals(31L, plan.uploadSize)
    }

    @Test fun changedChapterArchiveIsIncludedInAssetPlanAndUploadSize() {
        val assets = JSONObject().put("chapter_text/book_42.zip", fileEntry("new", 2048))
        val remoteAssets = JSONObject().put("chapter_text/book_42.zip", fileEntry("old", 1024))

        val plan = buildSyncBackupPlan(JSONObject(), JSONObject(), assets, remoteAssets)

        assertEquals(emptyList<String>(), plan.changedFiles)
        assertEquals(listOf("chapter_text/book_42.zip"), plan.changedAssets)
        assertEquals(2048L, plan.uploadSize)
    }

    private fun fileEntry(sha256: String, size: Long) = JSONObject()
        .put("sha256", sha256)
        .put("size", size)
}
