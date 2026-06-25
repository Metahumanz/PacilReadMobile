package com.metahumanz.pacilread.model

class ReadingBookStatRecord {
    @JvmField var bookIdentity: String? = null
    @JvmField var bookTitle: String? = null
    @JvmField var bookAuthor: String? = null
    @JvmField var totalDurationSeconds: Int = 0
    @JvmField var totalCharCount: Int = 0
    @JvmField var updatedAt: Long = 0
    @JvmField var localBookId: Long = -1L
    @JvmField var localCoverPath: String? = null
}
