package com.metahumanz.pacilread.sync

fun isFileGzipChapterStorage(
    bodyTextStorage: String?,
    bodyTextPath: String?,
): Boolean {
    val path = bodyTextPath?.trim().orEmpty()
    if (path.isEmpty()) return false

    val storage = bodyTextStorage?.trim().orEmpty()
    return storage == "file_gzip" || storage.isEmpty()
}

fun normalizeChapterStorage(
    bodyTextStorage: String?,
    bodyTextPath: String?,
): String {
    if (isFileGzipChapterStorage(bodyTextStorage, bodyTextPath)) return "file_gzip"
    val storage = bodyTextStorage?.trim().orEmpty()
    return if (storage.isNotEmpty()) storage else "db"
}
