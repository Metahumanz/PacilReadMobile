package com.metahumanz.pacilread.reader.modern.paging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPagingSnapshotWarmupPolicyTest {
    @Test
    fun controlsTransitionDefersSnapshotWarmup() {
        assertTrue(shouldSkip(controlsTransitionActive = true))
    }

    @Test
    fun idleReaderAllowsSnapshotWarmup() {
        assertFalse(shouldSkip())
    }

    @Test
    fun visibleControlsAndPageAnimationsStillDeferWarmup() {
        assertTrue(shouldSkip(controlsVisible = true))
        assertTrue(shouldSkip(pageAnimationActive = true))
        assertTrue(shouldSkip(interactivePaging = true))
    }

    private fun shouldSkip(
        controlsVisible: Boolean = false,
        controlsTransitionActive: Boolean = false,
        pageAnimationActive: Boolean = false,
        interactivePaging: Boolean = false,
    ) = ReaderPagingSnapshotWarmupPolicy.shouldSkip(
        hasChapters = true,
        controlsVisible = controlsVisible,
        controlsTransitionActive = controlsTransitionActive,
        readerEnterTransitionActive = false,
        pageAnimationActive = pageAnimationActive,
        interactivePaging = interactivePaging,
    )
}
