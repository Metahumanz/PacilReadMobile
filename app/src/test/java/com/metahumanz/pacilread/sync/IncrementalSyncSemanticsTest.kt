package com.metahumanz.pacilread.sync

import com.metahumanz.pacilread.model.BookRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalSyncSemanticsTest {
    @Test fun mapsRemoteBookEvenWhenBooksJsonIsUnchanged() {
        val local = BookRecord().apply {
            id = 24L
            title = "示例书"
            author = "作者"
            readingStatsKey = "stable-key"
        }
        val remote = JSONArray().put(JSONObject().apply {
            put("id", 17L)
            put("title", "示例书")
            put("author", "作者")
            put("readingStatsKey", "stable-key")
        })

        assertEquals(24L, buildRemoteBookIdMapping(remote, listOf(local))[17L])
    }

    @Test fun assetOnlyChangeIsDetectedWithoutJsonChange() {
        val oldAssets = JSONObject().apply {
            put("chapter_text/book_17.zip", JSONObject().put("size", 10).put("sha256", "old"))
        }
        val newAssets = JSONObject().apply {
            put("chapter_text/book_17.zip", JSONObject().put("size", 11).put("sha256", "new"))
        }
        assertEquals(setOf("chapter_text/book_17.zip"), changedAssetKeys(newAssets, oldAssets))
    }

    @Test fun chapterPathCanBeRemappedToLocalBookId() {
        assertEquals(
            "chapter_text/book_24/chapter_100.txt.gz",
            remapChapterTextPathForBook("chapter_text/book_17/chapter_100.txt.gz", 17L, 24L),
        )
    }
}
