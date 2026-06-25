package com.metahumanz.pacilread.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

object FileAssetHelper {
    @JvmStatic
    @Throws(IOException::class)
    fun copyUriToFolder(context: Context, uri: Uri, folderName: String?, prefix: String?): File {
        val appContext = context.applicationContext
        val folder = File(appContext.filesDir, folderName!!)
        if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建目录: ${folder.absolutePath}")

        val extension = extensionOf(resolveDisplayName(appContext, uri))
        val destination = File(folder, "${safePrefix(prefix)}_${UUID.randomUUID()}$extension")
        val inputStream = appContext.contentResolver.openInputStream(uri)
        val outputStream = FileOutputStream(destination)
        inputStream.use { input ->
            outputStream.use { output ->
                if (input == null) throw IOException("无法读取文件内容")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
        return destination
    }

    @JvmStatic
    fun resolveDisplayName(context: Context, uri: Uri): String {
        val resolver = context.applicationContext.contentResolver
        try {
            resolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        } catch (_: Exception) {
        }
        val fallback = uri.lastPathSegment
        return if (fallback.isNullOrBlank()) "asset.bin" else fallback
    }

    @JvmStatic
    fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (file.exists()) file.delete()
    }

    private fun extensionOf(fileName: String?): String {
        if (fileName == null) return ""
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex < 0) return ""
        return fileName.substring(dotIndex).lowercase(Locale.ROOT)
    }

    private fun safePrefix(prefix: String?): String {
        val value = (prefix?.trim()?.lowercase(Locale.ROOT) ?: "asset").replace(Regex("[^a-z0-9_-]+"), "_")
        return value.ifEmpty { "asset" }
    }
}
