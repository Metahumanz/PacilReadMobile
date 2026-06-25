package com.metahumanz.pacilread.export

import com.metahumanz.pacilread.model.BookRecord
import java.io.File
import java.util.Locale

object BookExportNaming {
    @JvmStatic
    fun uniqueFileName(book: BookRecord?, usedNames: MutableSet<String>?): String {
        var preferred = sanitize(book?.sourceDisplayName)
        val extension = extensionFor(book)
        if (preferred.isEmpty()) {
            var title = sanitize(book?.title)
            val author = sanitize(book?.author)
            if (title.isEmpty()) title = "未命名书籍"
            preferred = if (author.isEmpty()) title + extension else "$title - $author$extension"
        } else if (!preferred.lowercase(Locale.ROOT).endsWith(extension)) {
            preferred += extension
        }
        val base = stripExtension(preferred)
        val suffix = preferred.substring(base.length)
        var candidate = preferred
        var index = 2
        while (containsIgnoreCase(usedNames, candidate)) {
            candidate = "$base (${index++})$suffix"
        }
        usedNames?.add(candidate)
        return candidate
    }

    private fun extensionFor(book: BookRecord?): String {
        val localPath = book?.localPath
        if (localPath != null) {
            val name = File(localPath).name
            val dot = name.lastIndexOf('.')
            if (dot >= 0) return name.substring(dot).lowercase(Locale.ROOT)
        }
        if (book?.bookType.equals("epub", ignoreCase = true)) return ".epub"
        if (book?.bookType.equals("pdf", ignoreCase = true)) return ".pdf"
        return ".txt"
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun sanitize(value: String?): String = value?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: ""

    private fun containsIgnoreCase(values: Set<String>?, target: String): Boolean {
        if (values == null) return false
        for (value in values) if (value.equals(target, ignoreCase = true)) return true
        return false
    }
}
