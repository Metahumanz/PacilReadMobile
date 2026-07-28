package com.metahumanz.pacilread.reader.modern.content

internal object ReaderInsetUpdatePolicy {
    fun shouldRefreshReaderContent(suppressReflow: Boolean, paginationInsetsChanged: Boolean): Boolean =
        paginationInsetsChanged || !suppressReflow
}
