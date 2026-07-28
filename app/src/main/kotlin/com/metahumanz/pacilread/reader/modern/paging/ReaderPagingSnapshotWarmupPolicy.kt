package com.metahumanz.pacilread.reader.modern.paging

internal object ReaderPagingSnapshotWarmupPolicy {
    fun shouldSkip(
        hasChapters: Boolean,
        controlsVisible: Boolean,
        controlsTransitionActive: Boolean,
        readerEnterTransitionActive: Boolean,
        pageAnimationActive: Boolean,
        interactivePaging: Boolean,
    ): Boolean = !hasChapters || controlsVisible || controlsTransitionActive || readerEnterTransitionActive ||
        pageAnimationActive || interactivePaging
}
