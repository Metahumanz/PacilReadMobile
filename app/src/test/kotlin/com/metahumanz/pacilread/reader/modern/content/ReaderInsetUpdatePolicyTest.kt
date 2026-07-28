package com.metahumanz.pacilread.reader.modern.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInsetUpdatePolicyTest {
    @Test
    fun menuSystemBarInsetsDoNotRefreshReaderContentDuringSuppressionWindow() {
        assertFalse(ReaderInsetUpdatePolicy.shouldRefreshReaderContent(
            suppressReflow = true,
            paginationInsetsChanged = false,
        ))
    }

    @Test
    fun paginationInsetsAlwaysRefreshReaderContent() {
        assertTrue(ReaderInsetUpdatePolicy.shouldRefreshReaderContent(
            suppressReflow = true,
            paginationInsetsChanged = true,
        ))
    }

    @Test
    fun externalInsetChangesRefreshWhenNotSuppressed() {
        assertTrue(ReaderInsetUpdatePolicy.shouldRefreshReaderContent(
            suppressReflow = false,
            paginationInsetsChanged = false,
        ))
    }
}
