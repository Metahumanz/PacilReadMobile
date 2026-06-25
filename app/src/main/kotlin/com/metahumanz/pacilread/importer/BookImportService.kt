package com.metahumanz.pacilread.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.metahumanz.pacilread.model.ImportedBook
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

open class BookImportService(context: Context) {
    private val context = context.applicationContext

    init {
        PDFBoxResourceLoader.init(this.context)
    }

    @Throws(Exception::class)
    fun importFromUri(uri: Uri, pdfSplitByPage: Boolean): ImportedBook {
        val prepared = prepareFromUri(uri)
        try {
            return parsePrepared(prepared, pdfSplitByPage)
        } catch (error: Exception) {
            prepared.deleteLocalCopy()
            throw error
        }
    }

    @Throws(Exception::class)
    fun prepareFromUri(uri: Uri): PreparedImport {
        val displayName = resolveDisplayName(uri)
        val extension = extensionOf(displayName)
        if (extension != ".txt" && extension != ".epub" && extension != ".pdf") {
            throw IOException("当前仅支持导入 TXT、EPUB 与 PDF 文件")
        }
        val booksDir = File(context.filesDir, "books")
        if (!booksDir.exists() && !booksDir.mkdirs()) throw IOException("无法创建书籍缓存目录")

        val localCopy = File(booksDir, "${UUID.randomUUID()}$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                FileOutputStream(localCopy).use { outputStream ->
                    if (inputStream == null) throw IOException("无法读取导入文件")
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
        } catch (error: Exception) {
            localCopy.delete()
            throw error
        }
        val parsedName = BookFileNameParser.parse(displayName)
        return PreparedImport(uri, displayName, extension, parsedName.title, parsedName.author, localCopy, toHex(digest.digest()))
    }

    @Throws(Exception::class)
    fun parsePrepared(prepared: PreparedImport?, pdfSplitByPage: Boolean): ImportedBook {
        if (prepared?.localCopy?.isFile != true) throw IOException("导入暂存文件不存在")
        val importedBook = ImportedBook().apply {
            title = prepared.title
            author = prepared.author
            sourceDisplayName = prepared.displayName
            contentSha256 = prepared.contentSha256
            storedPath = prepared.localCopy.absolutePath
        }
        val parsedChapters: List<ImportedBook.ChapterSeed>
        when (prepared.extension) {
            ".txt" -> {
                FileInputStream(prepared.localCopy).use { parsedChapters = TxtChapterParser.parse(it) }
                importedBook.bookType = "text"
            }
            ".pdf" -> {
                parsedChapters = PdfChapterParser.parse(prepared.localCopy, pdfSplitByPage)
                importedBook.bookType = "pdf"
            }
            else -> {
                parsedChapters = EpubChapterParser.parse(prepared.localCopy)
                EpubChapterParser.extractCover(context, prepared.localCopy, "epub_cover")?.let {
                    importedBook.coverPath = it.absolutePath
                }
                importedBook.bookType = "epub"
            }
        }
        importedBook.chapters.addAll(parsedChapters)
        return importedBook
    }

    @Throws(Exception::class)
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    class PreparedImport internal constructor(
        @JvmField val sourceUri: Uri,
        @JvmField val displayName: String,
        @JvmField val extension: String,
        @JvmField val title: String,
        @JvmField val author: String?,
        @JvmField val localCopy: File,
        @JvmField val contentSha256: String,
    ) {
        fun deleteLocalCopy() {
            if (localCopy.isFile) localCopy.delete()
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Unknown.txt"
    }

    private fun extensionOf(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex < 0) "" else fileName.substring(dotIndex).lowercase(Locale.ROOT)
    }

    private companion object {
        fun toHex(bytes: ByteArray): String {
            val result = StringBuilder(bytes.size * 2)
            for (value in bytes) result.append(String.format(Locale.ROOT, "%02x", value.toInt() and 0xff))
            return result.toString()
        }
    }
}
