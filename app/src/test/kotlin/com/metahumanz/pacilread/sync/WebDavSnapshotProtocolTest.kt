package com.metahumanz.pacilread.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class WebDavSnapshotProtocolTest {
    @Test
    fun `提交标记必须同时匹配代次和 manifest 哈希`() {
        val manifestText = """{"schemaVersion":1,"generationId":"g-1"}"""
        val manifestSha256 = sha256(manifestText)

        assertTrue(WebDavBackupManager.snapshotCommitMatches(manifestText, "g-1", "g-1", manifestSha256))
        assertFalse(WebDavBackupManager.snapshotCommitMatches(manifestText, "g-2", "g-1", manifestSha256))
        assertFalse(WebDavBackupManager.snapshotCommitMatches(manifestText + " ", "g-1", "g-1", manifestSha256))
    }

    @Test
    fun `正文 ZIP 路径兼容桌面与移动端前缀并拒绝越界`() {
        assertEquals(
            "book_7/chapter_9.txt.gz",
            WebDavBackupManager.canonicalChapterTextArchiveEntryName("chapter_text/book_7/chapter_9.txt.gz"),
        )
        assertEquals(
            "book_7/chapter_9.txt.gz",
            WebDavBackupManager.canonicalChapterTextArchiveEntryName("book_7/chapter_9.txt.gz"),
        )
        assertNull(WebDavBackupManager.canonicalChapterTextArchiveEntryName("../chapter_9.txt.gz"))
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}
