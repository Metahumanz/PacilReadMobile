package com.metahumanz.pacilread.storage

import android.content.Context
import com.metahumanz.pacilread.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

open class SnapshotManager(
    context: Context,
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
) {
    private val context = context.applicationContext
    private val snapshotsDir = File(this.context.filesDir, SNAPSHOT_DIR)

    @Throws(Exception::class)
    fun createSnapshot(reason: String?): Snapshot {
        databaseHelper.ensureLoaded()
        databaseHelper.flush()
        if (!snapshotsDir.exists() && !snapshotsDir.mkdirs()) throw IllegalStateException("无法创建恢复点目录")
        val now = System.currentTimeMillis()
        val id = String.format(Locale.ROOT, "snapshot-%d", now)
        val snapshotDir = File(snapshotsDir, id)
        if (!snapshotDir.exists() && !snapshotDir.mkdirs()) throw IllegalStateException("无法创建恢复点")
        val bundle = File(snapshotDir, BUNDLE_FILE)
        val counts = JSONObject()
        ZipOutputStream(FileOutputStream(bundle)).use { output ->
            val dataDir = databaseHelper.dataDir
            for (fileName in DATABASE_FILES) {
                val file = File(dataDir, fileName)
                if (!file.exists() || !file.isFile) continue
                counts.put(entityName(fileName), countJsonArray(file))
                writeFileEntry(output, DATABASE_PREFIX + fileName, file)
            }
            writeTextEntry(output, ENTRY_ANDROID_SETTINGS, settingsStore.exportAndroidPrivateSettingsJson().toString(2))
        }
        val manifest = JSONObject()
            .put("id", id)
            .put("createdAt", now)
            .put("reason", reason?.takeUnless { it.isBlank() } ?: "manual")
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("bundleSize", bundle.length())
            .put("counts", counts)
        FileWriter(File(snapshotDir, MANIFEST_FILE)).use { it.write(manifest.toString(2)) }
        return Snapshot.fromManifest(manifest, bundle.length())
    }

    fun listSnapshots(): List<Snapshot> {
        val snapshots = ArrayList<Snapshot>()
        val dirs = snapshotsDir.listFiles() ?: return snapshots
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val manifest = File(dir, MANIFEST_FILE)
            val bundle = File(dir, BUNDLE_FILE)
            if (!manifest.exists() || !bundle.exists()) continue
            try {
                snapshots.add(Snapshot.fromManifest(JSONObject(readFileString(manifest)), bundle.length()))
            } catch (_: Exception) {
            }
        }
        snapshots.sortWith { left, right -> java.lang.Long.compare(right.createdAt, left.createdAt) }
        return snapshots
    }

    @Throws(Exception::class)
    fun restoreSnapshot(id: String?) {
        val bundle = File(safeSnapshotDir(id), BUNDLE_FILE)
        if (!bundle.exists()) throw IllegalStateException("恢复点文件不存在")
        val tempDir = File(context.cacheDir, "snapshot_restore_${System.currentTimeMillis()}")
        deleteRecursively(tempDir)
        if (!tempDir.mkdirs()) throw IllegalStateException("无法创建恢复临时目录")
        val dataDir = databaseHelper.dataDir
        if (!dataDir.exists() && !dataDir.mkdirs()) throw IllegalStateException("无法创建数据库目录")
        var settingsJson: JSONObject? = null
        try {
            ZipInputStream(FileInputStream(bundle)).use { input ->
                val buffer = ByteArray(32768)
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = sanitizeEntryName(entry.name)
                    if (entry.isDirectory || name == null || !RESTORABLE_ENTRIES.contains(name)) {
                        input.closeEntry()
                        continue
                    }
                    if (name == ENTRY_ANDROID_SETTINGS) {
                        settingsJson = JSONObject(readEntryString(input, buffer))
                    } else if (name.startsWith(DATABASE_PREFIX)) {
                        val target = File(tempDir, name.substring(DATABASE_PREFIX.length))
                        FileOutputStream(target).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
                        }
                    }
                    input.closeEntry()
                }
            }
            for (fileName in DATABASE_FILES) {
                val source = File(tempDir, fileName)
                if (source.exists() && source.isFile) replaceFile(source, File(dataDir, fileName))
            }
            settingsJson?.let { settingsStore.importAndroidPrivateSettingsJson(it, null) }
            databaseHelper.reloadFromDisk()
        } finally {
            deleteRecursively(tempDir)
        }
    }

    @Throws(Exception::class)
    fun deleteSnapshot(id: String?) = deleteRecursively(safeSnapshotDir(id))

    @Throws(Exception::class)
    private fun safeSnapshotDir(id: String?): File {
        if (id.isNullOrBlank() || id.contains('/') || id.contains('\\')) throw IllegalArgumentException("恢复点 ID 无效")
        val dir = File(snapshotsDir, id)
        val base = snapshotsDir.canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(base)) throw IllegalArgumentException("恢复点路径无效")
        return dir
    }

    @Throws(Exception::class)
    private fun writeFileEntry(output: ZipOutputStream, entryName: String, file: File) {
        output.putNextEntry(ZipEntry(entryName))
        val buffer = ByteArray(32768)
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
        }
        output.closeEntry()
    }

    @Throws(Exception::class)
    private fun writeTextEntry(output: ZipOutputStream, entryName: String, text: String?) {
        output.putNextEntry(ZipEntry(entryName))
        output.write(text.orEmpty().toByteArray(StandardCharsets.UTF_8))
        output.closeEntry()
    }

    private fun countJsonArray(file: File): Int = try {
        val content = readFileString(file)
        if (content.trim().isEmpty()) 0 else JSONArray(content).length()
    } catch (_: Exception) {
        0
    }

    private fun entityName(fileName: String): String {
        if (fileName == "reading_stats.json") return "readingStats"
        val index = fileName.indexOf('.')
        return if (index > 0) fileName.substring(0, index) else fileName
    }

    @Throws(Exception::class)
    private fun readFileString(file: File): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }

    @Throws(Exception::class)
    private fun replaceFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IllegalStateException("无法创建恢复目标目录")
        val temp = File(parent, "${target.name}.restore.tmp")
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Throws(Exception::class)
    private fun readEntryString(input: ZipInputStream, buffer: ByteArray): String {
        val output = ByteArrayOutputStream()
        var read: Int
        while (input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
        return output.toString("UTF-8")
    }

    private fun sanitizeEntryName(name: String?): String? {
        if (name.isNullOrBlank() || name.startsWith('/') || name.contains('\\') || name.contains(':')) return null
        for (segment in name.split('/')) if (segment.isBlank() || segment == "." || segment == "..") return null
        return name
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    class Snapshot private constructor(
        @JvmField val id: String,
        @JvmField val createdAt: Long,
        @JvmField val reason: String,
        @JvmField val bundleSize: Long,
        @JvmField val counts: JSONObject,
    ) {
        companion object {
            @JvmStatic
            fun fromManifest(manifest: JSONObject, bundleSize: Long): Snapshot = Snapshot(
                manifest.optString("id", ""),
                manifest.optLong("createdAt", 0L),
                manifest.optString("reason", ""),
                manifest.optLong("bundleSize", bundleSize),
                manifest.optJSONObject("counts") ?: JSONObject(),
            )
        }
    }

    companion object {
        private const val SNAPSHOT_DIR = "snapshots"
        private const val BUNDLE_FILE = "bundle.zip"
        private const val MANIFEST_FILE = "manifest.json"
        private const val ENTRY_ANDROID_SETTINGS = "android-settings.json"
        private const val DATABASE_PREFIX = "database/"
        private val DATABASE_FILES = arrayOf(
            "books.json", "chapters.json", "rules.json", "themes.json", "bookmarks.json", "reading_stats.json",
        )
        private val RESTORABLE_ENTRIES = hashSetOf(
            "database/books.json", "database/chapters.json", "database/rules.json", "database/themes.json",
            "database/bookmarks.json", "database/reading_stats.json", ENTRY_ANDROID_SETTINGS,
        )
    }
}
