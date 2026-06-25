package com.metahumanz.pacilread.model

class ImportedBook {
    @JvmField var title: String? = null
    @JvmField var author: String? = null
    @JvmField var sourceDisplayName: String? = null
    @JvmField var contentSha256: String? = null
    @JvmField var storedPath: String? = null
    @JvmField var coverPath: String? = null
    @JvmField var bookType: String = "text"
    @JvmField val chapters: MutableList<ChapterSeed> = ArrayList()

    class ChapterSeed(
        @JvmField val title: String?,
        @JvmField val bodyHtml: String?,
        @JvmField val bodyText: String?,
        @JvmField val orderIndex: Int,
    )
}
