package com.metahumanz.pacilread.sync

import android.content.Context
import android.util.Log
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.BookmarkRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReaderThemeRecord
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.storage.SnapshotManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

open class WebDavBackupManager(
    context: Context,
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
    private val webDavClient: WebDavClient,
) {
    private val context: Context = context.applicationContext

    @Throws(Exception::class)
    fun previewFullRestore(listener: StatusListener): SyncDiffPreview =
        previewRestore(SyncDiffPreview.MODE_FULL, webDavClient.backupBaseUrl() + "database/", listener)

    @Throws(Exception::class)
    fun previewIncrementalRestore(listener: StatusListener): SyncDiffPreview =
        previewRestore(SyncDiffPreview.MODE_INCREMENTAL, webDavClient.syncBaseUrl(), listener)

    @Throws(Exception::class)
    fun applySyncResolution(preview: SyncDiffPreview?, resolution: String?, listener: StatusListener) {
        if (preview == null) {
            throw IllegalStateException("差异预览不存在")
        }
        val safeResolution = if (RESOLUTION_REMOTE == resolution || RESOLUTION_LOCAL == resolution) {
            resolution
        } else {
            RESOLUTION_MERGE
        }
        listener.onStatus("创建差异决议前本地恢复点...")
        SnapshotManager(context, databaseHelper, settingsStore).createSnapshot("webdav-diff-resolution")
        if (RESOLUTION_LOCAL == safeResolution) {
            listener.onStatus("保留本地数据并回写 sync/...")
            uploadResolvedSyncSnapshot(listener)
            return
        }
        if (RESOLUTION_REMOTE == safeResolution && SyncDiffPreview.MODE_FULL == preview.mode) {
            fullRestore(listener)
            uploadResolvedSyncSnapshot(listener)
            return
        }
        applyRemoteEntities(preview, RESOLUTION_REMOTE == safeResolution, listener)
        uploadResolvedSyncSnapshot(listener)
    }

    @Throws(Exception::class)
    private fun previewRestore(mode: String, baseUrl: String, listener: StatusListener): SyncDiffPreview {
        ensureRestoreScopeSelected()
        listener.onStatus("下载云端清单...")
        val preview = SyncDiffPreview(mode)
        preview.remoteManifest = downloadManifestIfExists(baseUrl + MANIFEST_FILE)
        val dataDir = databaseHelper.getDataDir()
        for (fileName in SYNC_JSON_FILES) {
            listener.onStatus("预览 " + fileName + "...")
            val localArray = readLocalEntityArray(dataDir, fileName)
            val remoteArray = downloadRemoteEntityArray(baseUrl, fileName)
            preview.remoteEntities[fileName] = remoteArray
            appendDiffItems(preview, entityTypeForFile(fileName), localArray, remoteArray)
        }
        listener.onStatus("差异预览完成")
        return preview
    }

    @Throws(Exception::class)
    private fun applyRemoteEntities(preview: SyncDiffPreview, forceRemote: Boolean, listener: StatusListener) {
        databaseHelper.flush()
        var mergedBooks = false
        var mergedChapters = false
        val restoredBookIdMap: MutableMap<Long, Long> = HashMap()
        for (fileName in SYNC_JSON_FILES) {
            val array = preview.remoteEntities[fileName] ?: continue
            listener.onStatus("应用 " + fileName + "...")
            when (fileName) {
                "books.json" -> {
                    restoredBookIdMap.putAll(mergeBooks(array, forceRemote))
                    mergedBooks = true
                }
                "chapters.json" -> {
                    mergeChapters(array, restoredBookIdMap)
                    mergedChapters = true
                }
                "rules.json" -> mergeRules(array, restoredBookIdMap, forceRemote)
                "themes.json" -> mergeThemes(array, forceRemote)
                "bookmarks.json" -> mergeBookmarks(array, forceRemote)
                READING_STATS_CANONICAL_FILE -> mergeReadingStats(array)
            }
        }
        if (mergedBooks || mergedChapters) {
            clampProgressIndex()
        }
        databaseHelper.flush()
        databaseHelper.rebaseLocalAssetPaths()
        if (SyncDiffPreview.MODE_FULL == preview.mode) {
            restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled)
            restoreChapterTextFiles(listener)
        } else {
            val localManifest = loadLocalManifest()
            restoreChangedAssets(
                listener,
                preview.remoteManifest?.optJSONObject("assets"),
                localManifest?.optJSONObject("assets"),
            )
        }
        preview.remoteManifest?.let { saveLocalManifest(it) }
        restoreSettingsJsonIfPresent(listener)
        listener.onStatus("差异决议已应用")
    }

    @Throws(Exception::class)
    private fun uploadResolvedSyncSnapshot(listener: StatusListener) {
        databaseHelper.flush()
        val dataDir = databaseHelper.getDataDir()
        var manifest = downloadManifestIfExists(webDavClient.syncBaseUrl() + MANIFEST_FILE)
        if (manifest == null) {
            manifest = JSONObject()
            manifest.put("schemaVersion", 1)
        }
        manifest.put("generatedAt", System.currentTimeMillis())
        val filesEntry = JSONObject()
        for (fileName in SYNC_JSON_FILES) {
            val jsonFile = localJsonFile(dataDir, fileName)
            if (!jsonFile.exists()) continue
            val sha256 = computeFileSha256(jsonFile)
            manifestAppendFile(filesEntry, fileName, sha256, jsonFile.length())
            listener.onStatus("回写 " + fileName + "...")
            webDavClient.uploadFile(jsonFile, webDavClient.syncBaseUrl() + fileName)
            uploadReadingStatsLegacyCopyIfNeeded(jsonFile, webDavClient.syncBaseUrl(), fileName)
        }
        manifest.put("files", filesEntry)
        uploadManifest(webDavClient.syncBaseUrl() + MANIFEST_FILE, manifest)
        saveLocalManifest(manifest)
        listener.onStatus("决议结果已回写 sync/")
    }

    private fun readLocalEntityArray(dataDir: File, fileName: String): JSONArray {
        val file = localJsonFile(dataDir, fileName)
        if (!file.exists()) return JSONArray()
        return try {
            val content = readFileString(file)
            if (content == null || content.trim().isEmpty()) JSONArray() else readEntityArray(fileName, content)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun downloadRemoteEntityArray(baseUrl: String, fileName: String): JSONArray {
        val tempFile = File(context.cacheDir, "preview_" + fileName)
        return try {
            downloadJsonWithAliases(baseUrl, fileName, tempFile)
            val content = readFileString(tempFile)
            if (content == null || content.trim().isEmpty()) JSONArray() else readEntityArray(fileName, content)
        } catch (_: Exception) {
            JSONArray()
        } finally {
            tempFile.delete()
        }
    }

    private fun appendDiffItems(preview: SyncDiffPreview, entityType: String, localArray: JSONArray, remoteArray: JSONArray) {
        val localByKey = indexEntityArray(entityType, localArray)
        val remoteByKey = indexEntityArray(entityType, remoteArray)
        val keys: MutableSet<String> = HashSet()
        keys.addAll(localByKey.keys)
        keys.addAll(remoteByKey.keys)
        for (key in keys) {
            val local = localByKey[key]
            val remote = remoteByKey[key]
            val item = SyncDiffItem()
            item.entityType = entityType
            item.key = key
            item.localUpdatedAt = updatedAtOf(local)
            item.remoteUpdatedAt = updatedAtOf(remote)
            val display = remote ?: local
            item.title = entityTitle(entityType, display)
            if (local == null) {
                item.status = SyncDiffItem.STATUS_REMOTE
                item.summary = "云端新增"
            } else if (remote == null) {
                item.status = SyncDiffItem.STATUS_LOCAL
                item.summary = "仅本地存在"
            } else if (local.toString() == remote.toString()) {
                item.status = SyncDiffItem.STATUS_UNCHANGED
                item.summary = "未变化"
            } else {
                item.status = SyncDiffItem.STATUS_CONFLICT
                item.summary = conflictSummary(entityType, local, remote)
            }
            preview.items.add(item)
        }
    }

    private fun indexEntityArray(entityType: String, array: JSONArray): Map<String, JSONObject> {
        val map: MutableMap<String, JSONObject> = LinkedHashMap()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            var key = entityKey(entityType, json)
            if (key.isBlank()) key = "$entityType#$i"
            map[key] = json
        }
        return map
    }

    private fun entityTypeForFile(fileName: String): String {
        if (READING_STATS_CANONICAL_FILE == fileName) return "readingStats"
        val index = fileName.indexOf('.')
        return if (index > 0) fileName.substring(0, index) else fileName
    }

    private fun entityKey(entityType: String, json: JSONObject): String {
        if ("books" == entityType) {
            val key = json.optString("readingStatsKey", "")
            if (key.isNotBlank()) return key
            return normalizeTitleAuthor(json.optString("title", ""), json.optString("author", ""))
        }
        if ("chapters" == entityType) return json.optLong("bookId", 0L).toString() + ":" + json.optInt("orderIndex", 0)
        if ("rules" == entityType) return json.optString("pattern", "") + "|" + json.optString("scope", "global") + "|" + json.optLong("bookId", 0L)
        if ("themes" == entityType) return json.optString("name", "")
        if ("bookmarks" == entityType) return json.optString("uuid", "")
        if ("readingStats" == entityType) return json.optString("sourceDeviceId", "") + "|" + json.optString("date", "") + "|" + json.optString("bookIdentity", "")
        return json.optString("id", "")
    }

    private fun entityTitle(entityType: String, json: JSONObject?): String {
        if (json == null) return entityType
        if ("books" == entityType) return json.optString("title", "未命名书籍")
        if ("chapters" == entityType) return json.optString("title", "章节")
        if ("rules" == entityType) return json.optString("pattern", "替换规则")
        if ("themes" == entityType) return json.optString("name", "主题")
        if ("bookmarks" == entityType) return json.optString("summary", "书签")
        if ("readingStats" == entityType) return json.optString("bookTitle", "阅读统计") + " · " + json.optString("date", "")
        return entityType
    }

    private fun conflictSummary(entityType: String, local: JSONObject, remote: JSONObject): String {
        val changes: MutableList<String> = ArrayList()
        appendFieldChange(changes, "title", "书名", local, remote)
        appendFieldChange(changes, "author", "作者", local, remote)
        appendFieldChange(changes, "tags", "标签", local, remote)
        appendFieldChange(changes, "series", "系列", local, remote)
        appendFieldChange(changes, "readingStatus", "阅读状态", local, remote)
        appendFieldChange(changes, "progressIndex", "章节进度", local, remote)
        appendFieldChange(changes, "progressOffset", "章内位置", local, remote)
        appendFieldChange(changes, "durationSeconds", "时长", local, remote)
        appendFieldChange(changes, "charCount", "字数", local, remote)
        if (changes.isEmpty()) changes.add("内容不同")
        return changes.joinToString("，")
    }

    private fun appendFieldChange(changes: MutableList<String>, field: String, label: String, local: JSONObject, remote: JSONObject) {
        val left = local.opt(field)
        val right = remote.opt(field)
        val leftText = left?.toString() ?: ""
        val rightText = right?.toString() ?: ""
        if (leftText != rightText) {
            changes.add(label + ": " + trimPreview(leftText) + " -> " + trimPreview(rightText))
        }
    }

    private fun trimPreview(value: String?): String {
        val safe = value ?: ""
        return if (safe.length > 28) safe.substring(0, 28) + "..." else safe
    }

    private fun updatedAtOf(json: JSONObject?): Long = json?.optLong("updatedAt", 0L) ?: 0L

    @Throws(Exception::class)
    fun fullBackup(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureAnySyncScopeSelected()
        normalizeReplacementRules(databaseHelper.getRulesMutable())
        databaseHelper.flush()
        val includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled
        val includeFiles = settingsStore.isWebDavSyncFilesEnabled
        val includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled

        listener.onStatus("创建云端目录...")
        webDavClient.ensureBackupRootDirectory()
        webDavClient.ensureDirectory(webDavClient.backupBaseUrl() + "database/")
        if (includeFiles) webDavClient.ensureBookAssetDirectories()
        if (shouldSyncSettingsSnapshot()) webDavClient.ensureAndroidSettingsDirectory()
        if (includeChapterText) webDavClient.ensureChapterTextDirectory()

        if (shouldSyncDatabaseSnapshot()) {
            listener.onStatus("上传 JSON 数据文件...")
            val dataDir = databaseHelper.getDataDir()
            val manifest = JSONObject()
            manifest.put("schemaVersion", 1)
            manifest.put("generatedAt", System.currentTimeMillis())
            val filesEntry = JSONObject()

            for (fileName in SYNC_JSON_FILES) {
                val jsonFile = localJsonFile(dataDir, fileName)
                if (!jsonFile.exists()) continue
                val sha256 = computeFileSha256(jsonFile)
                manifestAppendFile(filesEntry, fileName, sha256, jsonFile.length())
                listener.onStatus("上传 " + fileName + "...")
                webDavClient.uploadFile(jsonFile, webDavClient.backupBaseUrl() + "database/" + fileName)
                uploadReadingStatsLegacyCopyIfNeeded(jsonFile, webDavClient.backupBaseUrl() + "database/", fileName)
            }

            manifest.put("files", filesEntry)
            manifest.put("assets", JSONObject())
            uploadManifest(webDavClient.backupBaseUrl() + "database/" + MANIFEST_FILE, manifest)
        }

        if (shouldSyncSettingsSnapshot()) uploadSettingsSnapshot(listener)

        uploadLocalAssets(listener, includeChapterText, includeFiles, includeBackgrounds)
        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds)
        settingsStore.webDavLastFullBackupAt = System.currentTimeMillis()
        cleanupLocalTempCache()
        listener.onStatus("全量备份完成")
    }

    @Throws(Exception::class)
    fun incrementalBackup(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureAnySyncScopeSelected()
        normalizeReplacementRules(databaseHelper.getRulesMutable())
        databaseHelper.flush()
        val includeChapterText = settingsStore.isWebDavSyncBookshelfEnabled
        val includeFiles = settingsStore.isWebDavSyncFilesEnabled
        val includeBackgrounds = settingsStore.isWebDavSyncBackgroundsEnabled

        listener.onStatus("创建云端目录...")
        webDavClient.ensureBackupRootDirectory()
        webDavClient.ensureDirectory(webDavClient.syncBaseUrl())
        if (includeFiles) webDavClient.ensureBookAssetDirectories()
        if (shouldSyncSettingsSnapshot()) webDavClient.ensureAndroidSettingsDirectory()
        if (includeChapterText) webDavClient.ensureChapterTextDirectory()

        if (shouldSyncDatabaseSnapshot()) {
            val dataDir = databaseHelper.getDataDir()
            val manifestUrl = webDavClient.syncBaseUrl() + MANIFEST_FILE

            val localManifest = JSONObject()
            localManifest.put("schemaVersion", 1)
            localManifest.put("generatedAt", System.currentTimeMillis())
            val localFiles = JSONObject()
            for (fileName in SYNC_JSON_FILES) {
                val jsonFile = localJsonFile(dataDir, fileName)
                if (!jsonFile.exists()) continue
                val sha256 = computeFileSha256(jsonFile)
                manifestAppendFile(localFiles, fileName, sha256, jsonFile.length())
            }
            localManifest.put("files", localFiles)

            val remoteManifest = downloadManifestIfExists(manifestUrl)
            var uploadedFiles = 0
            for (fileName in SYNC_JSON_FILES) {
                val jsonFile = localJsonFile(dataDir, fileName)
                if (!jsonFile.exists()) continue
                val localHash = getManifestFileHash(localFiles, fileName)
                val remoteHash = getManifestFileHash(remoteManifest?.optJSONObject("files"), fileName)
                if (remoteHash != null && remoteHash == localHash) continue
                listener.onStatus("上传 " + fileName + "...")
                webDavClient.uploadFile(jsonFile, webDavClient.syncBaseUrl() + fileName)
                uploadReadingStatsLegacyCopyIfNeeded(jsonFile, webDavClient.syncBaseUrl(), fileName)
                uploadedFiles++
            }

            val localAssets = JSONObject()
            collectAssetManifest(localAssets, includeChapterText, includeFiles, includeBackgrounds)
            localManifest.put("assets", localAssets)

            if (uploadedFiles == 0 && assetsUnchanged(localAssets, remoteManifest)) {
                listener.onStatus("无变化，跳过上传")
            } else {
                uploadManifest(manifestUrl, localManifest)
            }

            uploadChangedAssets(
                listener,
                includeChapterText,
                includeFiles,
                includeBackgrounds,
                remoteManifest?.optJSONObject("assets"),
                localAssets,
            )
            saveLocalManifest(localManifest)
        }

        if (shouldSyncSettingsSnapshot()) uploadSettingsSnapshot(listener)

        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds)
        settingsStore.webDavLastLiteBackupAt = System.currentTimeMillis()
        cleanupLocalTempCache()
        listener.onStatus("增量备份完成")
    }

    @Throws(Exception::class)
    fun fullRestore(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureRestoreScopeSelected()
        listener.onStatus("创建恢复前本地恢复点...")
        SnapshotManager(context, databaseHelper, settingsStore).createSnapshot("webdav-full-restore")

        if (shouldSyncDatabaseSnapshot()) {
            val databaseUrl = webDavClient.backupBaseUrl() + "database/"
            val manifestUrl = databaseUrl + MANIFEST_FILE
            if (webDavClient.head(databaseUrl + "books.json").code == 404) {
                throw IllegalStateException("云端没有全量备份，请先执行全量备份")
            }

            listener.onStatus("下载 JSON 数据文件...")
            val dataDir = databaseHelper.getDataDir()
            if (!dataDir.exists()) dataDir.mkdirs()

            val manifest = downloadManifestIfExists(manifestUrl)
            for (fileName in SYNC_JSON_FILES) {
                val target = localJsonFile(dataDir, fileName)
                listener.onStatus("下载 " + fileName + "...")
                try {
                    val remoteFileName = downloadJsonWithAliases(databaseUrl, fileName, target)
                    if (manifest != null) {
                        val expectedHash = getManifestFileHash(manifest.optJSONObject("files"), remoteFileName)
                        if (expectedHash != null) {
                            val actualHash = computeFileSha256(target)
                            if (expectedHash != actualHash) {
                                Log.w(TAG, fileName + " SHA-256 校验不匹配，继续使用")
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "下载 " + fileName + " 失败: " + error.message)
                }
            }
            databaseHelper.reloadFromDisk()
            normalizeReplacementRules(databaseHelper.getRulesMutable())
            databaseHelper.flush()
            databaseHelper.rebaseLocalAssetPaths()

            listener.onStatus("恢复书籍资源文件...")
            restoreBookAssetFiles(listener, settingsStore.isWebDavSyncFilesEnabled)
            listener.onStatus("恢复章节正文...")
            restoreChapterTextFiles(listener)
        }

        restoreSettingsJsonIfPresent(listener)
        cleanupLocalTempCache()
        listener.onStatus("全量恢复完成")
    }

    @Throws(Exception::class)
    fun incrementalRestore(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureRestoreScopeSelected()
        listener.onStatus("创建恢复前本地恢复点...")
        SnapshotManager(context, databaseHelper, settingsStore).createSnapshot("webdav-incremental-restore")

        if (shouldSyncDatabaseSnapshot()) {
            val manifestUrl = webDavClient.syncBaseUrl() + MANIFEST_FILE
            if (webDavClient.head(webDavClient.syncBaseUrl() + "books.json").code == 404) {
                throw IllegalStateException("云端没有增量备份，请先执行增量备份或改用全量恢复")
            }

            val remoteManifest = downloadManifestIfExists(manifestUrl)
                ?: throw IllegalStateException("云端 manifest 不存在，请先执行增量备份")
            val localManifest = loadLocalManifest()
            val changedFiles = compareManifests(remoteManifest.optJSONObject("files"), localManifest?.optJSONObject("files"))
            if (changedFiles.isEmpty()) {
                listener.onStatus("已是最新，无需恢复")
                cleanupLocalTempCache()
                return
            }

            databaseHelper.flush()
            listener.onStatus("下载并合并变化的数据...")

            var mergedBooks = false
            var mergedChapters = false
            val restoredBookIdMap: MutableMap<Long, Long> = HashMap()

            for (fileName in SYNC_JSON_FILES) {
                if (!changedFiles.contains(fileName)) continue
                val tempFile = File(context.cacheDir, "restore_" + fileName)
                listener.onStatus("下载 " + fileName + "...")
                val remoteFileName = downloadJsonWithAliases(webDavClient.syncBaseUrl(), fileName, tempFile)

                val expectedHash = getManifestFileHash(remoteManifest.optJSONObject("files"), remoteFileName)
                if (expectedHash != null) {
                    val actualHash = computeFileSha256(tempFile)
                    if (expectedHash != actualHash) {
                        throw IllegalStateException(fileName + " 校验失败，传输可能不完整")
                    }
                }

                val content = readFileString(tempFile)
                if (content == null || content.trim().isEmpty()) continue
                val array = readEntityArray(fileName, content)

                when (fileName) {
                    "books.json" -> {
                        restoredBookIdMap.putAll(mergeBooks(array))
                        mergedBooks = true
                    }
                    "chapters.json" -> {
                        mergeChapters(array, restoredBookIdMap)
                        mergedChapters = true
                    }
                    "rules.json" -> mergeRules(array, restoredBookIdMap)
                    "themes.json" -> mergeThemes(array)
                    "bookmarks.json" -> mergeBookmarks(array)
                    READING_STATS_CANONICAL_FILE -> mergeReadingStats(array)
                }
                tempFile.delete()
            }

            if (mergedBooks || mergedChapters) clampProgressIndex()
            databaseHelper.flush()
            databaseHelper.rebaseLocalAssetPaths()
            restoreChangedAssets(listener, remoteManifest.optJSONObject("assets"), localManifest?.optJSONObject("assets"))
            saveLocalManifest(remoteManifest)
        }

        restoreSettingsJsonIfPresent(listener)
        cleanupLocalTempCache()
        listener.onStatus("增量恢复完成")
    }

    fun lastFullBackupLabel(): String {
        val value = settingsStore.webDavLastFullBackupAt
        return if (value <= 0) {
            "尚未备份"
        } else {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(Date(value))
        }
    }

    fun lastLiteBackupLabel(): String {
        val value = settingsStore.webDavLastLiteBackupAt
        return if (value <= 0) {
            "尚未备份"
        } else {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(Date(value))
        }
    }

    @Throws(Exception::class)
    private fun uploadSettingsSnapshot(listener: StatusListener) {
        listener.onStatus("上传 Android 设置...")
        webDavClient.uploadText(
            webDavClient.androidSettingsSnapshotUrl(),
            settingsStore.exportAndroidPrivateSettingsJson().toString(2),
            "application/json; charset=utf-8",
        )
    }

    @Throws(Exception::class)
    private fun restoreSettingsJsonIfPresent(listener: StatusListener) {
        if (!shouldSyncSettingsSnapshot()) return
        val remotePath = webDavClient.androidSettingsSnapshotUrl()
        if (webDavClient.head(remotePath).code != 200) return
        listener.onStatus("恢复 Android 设置...")
        val json = webDavClient.downloadText(remotePath)
        val settingsJson = JSONObject(json)
        val restoredBackgroundPath = restoreBackgroundIfPresent(listener, settingsJson)
        settingsStore.importAndroidPrivateSettingsJson(settingsJson, restoredBackgroundPath)
    }

    private fun restoreBackgroundIfPresent(listener: StatusListener, settingsJson: JSONObject): String? {
        val backgroundFileName = settingsStore.androidSettingsBackgroundFileName(settingsJson)
        if (backgroundFileName.isBlank()) return null
        return try {
            val remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFileName
            if (webDavClient.head(remotePath).code != 200) return null
            val folder = File(context.filesDir, "backgrounds")
            if (!folder.exists() && !folder.mkdirs()) return null
            val destination = File(folder, backgroundFileName)
            listener.onStatus("恢复背景图片...")
            webDavClient.downloadBinaryFile(remotePath, destination)
            destination.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreBookAssetFiles(listener: StatusListener, includeSourceFiles: Boolean) {
        val books = databaseHelper.getBooks()
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            if (!book.coverPath.isNullOrBlank()) {
                val coverFile = File(book.coverPath!!)
                val remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.name
                restoreRemoteFileIfPresent(remotePath, coverFile, "恢复封面 " + (i + 1) + "/" + total + "...", listener)
            }
            if (includeSourceFiles && !book.localPath.isNullOrBlank()) {
                val sourceFile = File(book.localPath!!)
                val remotePath = webDavClient.backupBaseUrl() + "books/" + sourceFile.name
                restoreRemoteFileIfPresent(remotePath, sourceFile, "恢复书籍源文件 " + (i + 1) + "/" + total + "...", listener)
            }
        }
    }

    private fun restoreRemoteFileIfPresent(remotePath: String, destination: File, status: String, listener: StatusListener) {
        try {
            val head = webDavClient.head(remotePath)
            if (head.code == 404 || head.code < 200 || head.code >= 300) return
            var remoteLength = -1L
            try {
                remoteLength = webDavClient.remoteContentLength(remotePath)
            } catch (_: Exception) {
            }
            if (destination.exists() && remoteLength >= 0 && destination.length() == remoteLength) return
            val parent = destination.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            listener.onStatus(status)
            webDavClient.downloadBinaryFile(remotePath, destination)
        } catch (error: Exception) {
            Log.w(TAG, "恢复资源文件失败: " + remotePath, error)
        }
    }

    @Throws(Exception::class)
    private fun uploadLocalAssets(listener: StatusListener, includeChapterText: Boolean, includeFiles: Boolean, includeBackgrounds: Boolean) {
        val books = if (includeChapterText || includeFiles) databaseHelper.getBooks() else ArrayList()
        if (includeChapterText) uploadChapterTextArchives(books, listener)
        if (includeFiles) {
            uploadCoverFiles(books, listener)
            uploadSourceFiles(books, listener)
        }
        if (!includeBackgrounds) return
        val backgroundPath = settingsStore.readerBackgroundPath
        if (backgroundPath.isNotBlank()) {
            val backgroundFile = File(backgroundPath)
            if (backgroundFile.exists()) {
                val remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.name
                if (shouldUploadFile(remotePath, backgroundFile)) {
                    listener.onStatus("上传背景图片 " + formatFileSize(backgroundFile.length()) + "...")
                    webDavClient.uploadFile(backgroundFile, remotePath)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun uploadChangedAssets(
        listener: StatusListener,
        includeChapterText: Boolean,
        includeFiles: Boolean,
        includeBackgrounds: Boolean,
        remoteAssets: JSONObject?,
        localAssets: JSONObject?,
    ) {
        val books = if (includeChapterText || includeFiles) databaseHelper.getBooks() else ArrayList()
        if (includeChapterText) uploadChangedChapterTextArchives(books, listener, remoteAssets, localAssets)
        if (includeFiles) {
            uploadChangedCoverFiles(books, listener, remoteAssets, localAssets)
            uploadChangedSourceFiles(books, listener, remoteAssets, localAssets)
        }
        if (includeBackgrounds) {
            val backgroundPath = settingsStore.readerBackgroundPath
            if (backgroundPath.isNotBlank()) {
                val backgroundFile = File(backgroundPath)
                if (backgroundFile.exists()) {
                    val remotePath = webDavClient.androidSettingsBackgroundsBaseUrl() + backgroundFile.name
                    val assetKey = "backgrounds/" + backgroundFile.name
                    val localSize = backgroundFile.length()
                    if (isAssetChanged(remoteAssets, assetKey, localSize)) {
                        listener.onStatus("上传背景图片...")
                        webDavClient.uploadFile(backgroundFile, remotePath)
                    }
                }
            }
        }
    }

    private fun restoreChangedAssets(listener: StatusListener, remoteAssets: JSONObject?, localAssets: JSONObject?) {
        if (remoteAssets == null) return
        val books = databaseHelper.getBooks()
        for (book in books) {
            if (!book.coverPath.isNullOrBlank()) {
                val coverFile = File(book.coverPath!!)
                val remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.name
                val assetKey = "covers/" + coverFile.name
                if (isAssetChanged(remoteAssets, assetKey, if (coverFile.exists()) coverFile.length() else -1)) {
                    restoreRemoteFileIfPresent(remotePath, coverFile, "恢复封面...", listener)
                }
            }
            if (settingsStore.isWebDavSyncFilesEnabled && !book.localPath.isNullOrBlank()) {
                val sourceFile = File(book.localPath!!)
                val remotePath = webDavClient.backupBaseUrl() + "books/" + sourceFile.name
                val assetKey = "books/" + sourceFile.name
                if (isAssetChanged(remoteAssets, assetKey, if (sourceFile.exists()) sourceFile.length() else -1)) {
                    restoreRemoteFileIfPresent(remotePath, sourceFile, "恢复源文件...", listener)
                }
            }
        }
    }

    private fun assetsUnchanged(localAssets: JSONObject, remoteManifest: JSONObject?): Boolean {
        if (remoteManifest == null) return false
        val remoteAssets = remoteManifest.optJSONObject("assets") ?: return localAssets.length() == 0
        if (localAssets.length() != remoteAssets.length()) return false
        val keys = localAssets.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val localAsset = localAssets.optJSONObject(key)
            val remoteAsset = remoteAssets.optJSONObject(key)
            if (localAsset == null || remoteAsset == null) return false
            if (localAsset.optLong("size") != remoteAsset.optLong("size")) return false
        }
        return true
    }

    private fun isAssetChanged(manifestAssets: JSONObject?, assetKey: String, localSize: Long): Boolean {
        if (manifestAssets == null) return true
        val asset = manifestAssets.optJSONObject(assetKey) ?: return true
        return asset.optLong("size", -1) != localSize
    }

    private fun collectAssetManifest(assets: JSONObject, includeChapterText: Boolean, includeFiles: Boolean, includeBackgrounds: Boolean) {
        try {
            if (includeChapterText || includeFiles) {
                val books = databaseHelper.getBooks()
                for (book in books) {
                    if (includeFiles) {
                        if (!book.coverPath.isNullOrBlank()) {
                            val coverFile = File(book.coverPath!!)
                            if (coverFile.exists()) {
                                val entry = JSONObject()
                                entry.put("size", coverFile.length())
                                assets.put("covers/" + coverFile.name, entry)
                            }
                        }
                        if (!book.localPath.isNullOrBlank()) {
                            val sourceFile = File(book.localPath!!)
                            if (sourceFile.exists()) {
                                val entry = JSONObject()
                                entry.put("size", sourceFile.length())
                                assets.put("books/" + sourceFile.name, entry)
                            }
                        }
                    }
                    if (includeChapterText) {
                        val files = collectChapterTextFiles(book)
                        if (files.isNotEmpty()) {
                            val archiveName = "book_" + book.id + ".zip"
                            var estimatedSize = 0L
                            for (file in files) estimatedSize += file.file.length()
                            val entry = JSONObject()
                            entry.put("size", estimatedSize)
                            assets.put("chapter_text/" + archiveName, entry)
                        }
                    }
                }
            }
            if (includeBackgrounds) {
                val bgPath = settingsStore.readerBackgroundPath
                if (bgPath.isNotBlank()) {
                    val bgFile = File(bgPath)
                    if (bgFile.exists()) {
                        val entry = JSONObject()
                        entry.put("size", bgFile.length())
                        assets.put("backgrounds/" + bgFile.name, entry)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Throws(Exception::class)
    private fun uploadChapterTextArchives(books: List<BookRecord>, listener: StatusListener) {
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IllegalStateException("无法创建备份缓存目录")
        val total = Math.max(books.size, 1)
        var uploaded = 0
        var skipped = 0
        for (i in books.indices) {
            val book = books[i]
            val files = collectChapterTextFiles(book)
            if (files.isEmpty()) continue
            val archive = File(tempDir, chapterTextArchiveFileName(book.id))
            writeChapterTextArchive(archive, files, i + 1, total, listener)
            val remotePath = chapterTextArchiveRemotePath(book.id)
            if (shouldUploadFile(remotePath, archive)) {
                uploaded++
                listener.onStatus("上传章节正文包 " + (i + 1) + "/" + total + " · " + formatFileSize(archive.length()) + "...")
                webDavClient.uploadFile(archive, remotePath)
            } else {
                skipped++
            }
        }
        if (uploaded == 0 && skipped > 0) listener.onStatus("章节正文包未变化，跳过上传")
    }

    @Throws(Exception::class)
    private fun uploadChangedChapterTextArchives(
        books: List<BookRecord>,
        listener: StatusListener,
        remoteAssets: JSONObject?,
        localAssets: JSONObject?,
    ) {
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IllegalStateException("无法创建备份缓存目录")
        for (book in books) {
            val files = collectChapterTextFiles(book)
            if (files.isEmpty()) continue
            val archiveName = "book_" + book.id + ".zip"
            val assetKey = "chapter_text/" + archiveName
            var estimatedSize = 0L
            for (file in files) estimatedSize += file.file.length()
            if (!isAssetChanged(remoteAssets, assetKey, estimatedSize)) continue
            val archive = File(tempDir, archiveName)
            writeChapterTextArchive(archive, files, 0, books.size, listener)
            listener.onStatus("上传章节正文包...")
            webDavClient.uploadFile(archive, chapterTextArchiveRemotePath(book.id))
        }
    }

    @Throws(Exception::class)
    private fun uploadCoverFiles(books: List<BookRecord>, listener: StatusListener) {
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            if (book.coverPath.isNullOrBlank()) continue
            val coverFile = File(book.coverPath!!)
            if (!coverFile.exists() || !coverFile.isFile) continue
            val remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.name
            if (shouldUploadFile(remotePath, coverFile)) {
                listener.onStatus("上传封面 " + (i + 1) + "/" + total + " · " + formatFileSize(coverFile.length()) + "...")
                webDavClient.uploadFile(coverFile, remotePath)
            }
        }
    }

    @Throws(Exception::class)
    private fun uploadChangedCoverFiles(books: List<BookRecord>, listener: StatusListener, remoteAssets: JSONObject?, localAssets: JSONObject?) {
        for (book in books) {
            if (book.coverPath.isNullOrBlank()) continue
            val coverFile = File(book.coverPath!!)
            if (!coverFile.exists()) continue
            val assetKey = "covers/" + coverFile.name
            if (isAssetChanged(remoteAssets, assetKey, coverFile.length())) {
                listener.onStatus("上传封面...")
                webDavClient.uploadFile(coverFile, webDavClient.backupBaseUrl() + "covers/" + coverFile.name)
            }
        }
    }

    @Throws(Exception::class)
    private fun uploadSourceFiles(books: List<BookRecord>, listener: StatusListener) {
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            if (book.localPath.isNullOrBlank()) continue
            val localFile = File(book.localPath!!)
            if (!localFile.exists() || !localFile.isFile) continue
            val remotePath = webDavClient.backupBaseUrl() + "books/" + localFile.name
            if (shouldUploadFile(remotePath, localFile)) {
                listener.onStatus("上传书籍源文件 " + (i + 1) + "/" + total + " · " + formatFileSize(localFile.length()) + "...")
                webDavClient.uploadFile(localFile, remotePath)
            }
        }
    }

    @Throws(Exception::class)
    private fun uploadChangedSourceFiles(books: List<BookRecord>, listener: StatusListener, remoteAssets: JSONObject?, localAssets: JSONObject?) {
        for (book in books) {
            if (book.localPath.isNullOrBlank()) continue
            val localFile = File(book.localPath!!)
            if (!localFile.exists()) continue
            val assetKey = "books/" + localFile.name
            if (isAssetChanged(remoteAssets, assetKey, localFile.length())) {
                listener.onStatus("上传源文件...")
                webDavClient.uploadFile(localFile, webDavClient.backupBaseUrl() + "books/" + localFile.name)
            }
        }
    }

    private fun restoreChapterTextFiles(listener: StatusListener) {
        val books = databaseHelper.getBooks()
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            if (restoreBookChapterTextArchiveIfPresent(book, i + 1, total, listener)) continue
            restoreChapterTextFilesForBook(book, i + 1, total, listener)
        }
    }

    private fun restoreBookChapterTextArchiveIfPresent(book: BookRecord, bookIndex: Int, totalBooks: Int, listener: StatusListener): Boolean {
        val remotePath = chapterTextArchiveRemotePath(book.id)
        return try {
            if (webDavClient.head(remotePath).code != 200) return false
            val tempDir = File(context.cacheDir, "backup_restore")
            if (!tempDir.exists() && !tempDir.mkdirs()) return false
            val archive = File(tempDir, chapterTextArchiveFileName(book.id))
            listener.onStatus("下载章节正文包 " + bookIndex + "/" + totalBooks + "...")
            webDavClient.downloadBinaryFile(remotePath, archive)
            listener.onStatus("解包章节正文 " + bookIndex + "/" + totalBooks + "...")
            val restored = extractChapterTextArchive(archive)
            listener.onStatus("章节正文包已恢复 " + bookIndex + "/" + totalBooks + " · " + restored + " 个文件")
            restored > 0
        } catch (error: Exception) {
            Log.w(TAG, "恢复章节正文包失败 book " + book.id + "，回退逐文件恢复", error)
            false
        }
    }

    private fun restoreChapterTextFilesForBook(book: BookRecord, bookIndex: Int, totalBooks: Int, listener: StatusListener) {
        val chapters = databaseHelper.getChaptersWithExternalStorage(book.id)
        for (chapter in chapters) {
            if (chapter.bodyTextPath.isNullOrBlank()) continue
            try {
                val remotePath = webDavClient.backupBaseUrl() + "chapter_text/" + chapter.bodyTextPath
                if (webDavClient.head(remotePath).code != 200) {
                    Log.w(TAG, "章节正文缺失 chapter " + chapter.id + ": " + chapter.bodyTextPath)
                    continue
                }
                listener.onStatus("恢复章节正文 " + bookIndex + "/" + totalBooks + "...")
                val localFile = databaseHelper.resolveChapterTextFile(chapter.bodyTextPath)
                if (localFile != null) {
                    val parent = localFile.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    webDavClient.downloadBinaryFile(remotePath, localFile)
                }
            } catch (error: Exception) {
                Log.w(TAG, "恢复章节正文失败 chapter " + chapter.id, error)
            }
        }
    }

    private fun mergeBooks(remoteBooks: JSONArray): Map<Long, Long> = mergeBooks(remoteBooks, false)

    private fun mergeBooks(remoteBooks: JSONArray, forceRemote: Boolean): Map<Long, Long> {
        val localBooks = databaseHelper.getBooksMutable()
        val remoteToLocalBookIds: MutableMap<Long, Long> = HashMap()
        val localByKey: MutableMap<String, BookRecord> = HashMap()
        val localByTitleAuthor: MutableMap<String, BookRecord> = HashMap()
        for (book in localBooks) {
            if (!book.readingStatsKey.isNullOrEmpty()) localByKey[book.readingStatsKey!!] = book
            val taKey = normalizeTitleAuthor(book.title, book.author)
            if (taKey.isNotEmpty()) localByTitleAuthor[taKey] = book
        }

        val matchedIds: MutableSet<Long> = HashSet()
        for (i in 0 until remoteBooks.length()) {
            val remoteJson = remoteBooks.optJSONObject(i) ?: continue
            val remoteId = remoteJson.optLong("id", 0)
            val remoteKey = remoteJson.optString("readingStatsKey", "")
            val remoteTitle = remoteJson.optString("title", "")
            val remoteAuthor = remoteJson.optString("author", "")
            val remoteUpdatedAt = remoteJson.optLong("updatedAt", 0)
            val remoteBook = BookRecord.fromJson(remoteJson)

            var localMatch: BookRecord? = null
            if (remoteKey.isNotEmpty()) localMatch = localByKey[remoteKey]
            if (localMatch == null) {
                val taKey = normalizeTitleAuthor(remoteTitle, remoteAuthor)
                localMatch = localByTitleAuthor[taKey]
            }

            if (localMatch == null) {
                val newBook = remoteBook
                newBook.id = nextRecordId(localBooks, remoteId)
                localBooks.add(newBook)
                localMatch = newBook
                if (remoteKey.isNotEmpty()) localByKey[remoteKey] = newBook
                val taKey = normalizeTitleAuthor(remoteTitle, remoteAuthor)
                if (taKey.isNotEmpty()) localByTitleAuthor[taKey] = newBook
            } else if (forceRemote || remoteUpdatedAt > localMatch.updatedAt) {
                matchedIds.add(localMatch.id)
                localMatch.title = remoteTitle
                localMatch.author = remoteAuthor
                localMatch.bookType = remoteJson.optString("bookType", "text")
                localMatch.readingStatsKey = remoteKey
                localMatch.progressIndex = remoteJson.optInt("progressIndex", 0)
                localMatch.progressOffset = remoteJson.optInt("progressOffset", 0)
                localMatch.lastReadAt = remoteJson.optLong("lastReadAt", 0)
                localMatch.pinned = remoteJson.optBoolean("pinned", false)
                localMatch.chapterCount = remoteJson.optInt("chapterCount", 0)
                localMatch.currentChapterTitle = remoteJson.optString("currentChapterTitle", "")
                localMatch.updatedAt = remoteUpdatedAt
                localMatch.copyExtendedFieldsFrom(remoteBook)
            }
            if (remoteId > 0) {
                remoteToLocalBookIds[remoteId] = localMatch.id
            }
        }
        return remoteToLocalBookIds
    }

    private fun mergeChapters(remoteChapters: JSONArray, restoredBookIdMap: Map<Long, Long>) {
        val localChapters = databaseHelper.getChaptersMutable()
        val localByKey: MutableMap<String, ChapterRecord> = HashMap()
        for (chapter in localChapters) {
            localByKey[chapter.bookId.toString() + ":" + chapter.orderIndex] = chapter
        }

        for (i in 0 until remoteChapters.length()) {
            val remoteJson = remoteChapters.optJSONObject(i) ?: continue
            val remoteBookId = remoteJson.optLong("bookId", 0)
            val bookId = restoredBookIdMap[remoteBookId] ?: remoteBookId
            val orderIndex = remoteJson.optInt("orderIndex", 0)
            val key = bookId.toString() + ":" + orderIndex
            val localMatch = localByKey[key]

            if (localMatch == null) {
                val newChapter = ChapterRecord.fromJson(remoteJson)
                newChapter.bookId = bookId
                newChapter.id = nextRecordId(localChapters, newChapter.id)
                localChapters.add(newChapter)
                localByKey[key] = newChapter
            } else {
                localMatch.title = remoteJson.optString("title", "")
                val remoteStorage = remoteJson.optString("bodyTextStorage", "db")
                val remotePath = remoteJson.optString("bodyTextPath", "")
                val remoteSize = remoteJson.optLong("bodyTextSize", 0)
                if ("file_gzip" == remoteStorage && remotePath.isNotEmpty()) {
                    localMatch.bodyTextStorage = remoteStorage
                    localMatch.bodyTextPath = remotePath
                    localMatch.bodyTextSize = remoteSize
                }
            }
        }
    }

    private fun mergeRules(remoteRules: JSONArray, restoredBookIdMap: Map<Long, Long>) =
        mergeRules(remoteRules, restoredBookIdMap, false)

    private fun mergeRules(remoteRules: JSONArray, restoredBookIdMap: Map<Long, Long>, forceRemote: Boolean) {
        val localRules = databaseHelper.getRulesMutable()
        normalizeReplacementRules(localRules)
        val localByKey: MutableMap<String, ReplacementRuleRecord> = HashMap()
        for (rule in localRules) localByKey[buildRuleKey(rule)] = rule

        for (i in 0 until remoteRules.length()) {
            val remoteJson = remoteRules.optJSONObject(i) ?: continue
            val remoteRule = ReplacementRuleRecord.fromJson(remoteJson)
            normalizeRuleScope(remoteRule)
            if ("book" == remoteRule.scope && remoteRule.bookId != null) {
                remoteRule.bookId = restoredBookIdMap[remoteRule.bookId] ?: remoteRule.bookId
            }
            val key = buildRuleKey(remoteRule)
            val localMatch = localByKey[key]
            if (localMatch == null) {
                remoteRule.id = nextRecordId(localRules, remoteRule.id)
                localRules.add(remoteRule)
                localByKey[key] = remoteRule
            } else if (forceRemote || remoteRule.updatedAt > localMatch.updatedAt) {
                copyReplacementRuleFields(localMatch, remoteRule)
            }
        }
        normalizeReplacementRules(localRules)
    }

    private fun mergeThemes(remoteThemes: JSONArray) = mergeThemes(remoteThemes, false)

    private fun mergeThemes(remoteThemes: JSONArray, forceRemote: Boolean) {
        val localThemes = databaseHelper.getThemesMutable()
        val localByName: MutableMap<String, ReaderThemeRecord> = HashMap()
        for (theme in localThemes) theme.name?.let { localByName[it] = theme }

        for (i in 0 until remoteThemes.length()) {
            val remoteJson = remoteThemes.optJSONObject(i) ?: continue
            val remoteTheme = ReaderThemeRecord.fromJson(remoteJson)
            val localMatch = localByName[remoteTheme.name]
            if (localMatch == null) {
                remoteTheme.id = 0
                localThemes.add(remoteTheme)
            } else if (forceRemote || remoteTheme.updatedAt > localMatch.updatedAt) {
                localMatch.configJson = remoteTheme.configJson
                localMatch.updatedAt = remoteTheme.updatedAt
            }
        }
    }

    private fun mergeBookmarks(remoteBookmarks: JSONArray) = mergeBookmarks(remoteBookmarks, false)

    private fun mergeBookmarks(remoteBookmarks: JSONArray, forceRemote: Boolean) {
        val localBookmarks = databaseHelper.getBookmarksMutable()
        val localByUuid: MutableMap<String, BookmarkRecord> = HashMap()
        for (bookmark in localBookmarks) bookmark.uuid?.let { localByUuid[it] = bookmark }

        for (i in 0 until remoteBookmarks.length()) {
            val remoteJson = remoteBookmarks.optJSONObject(i) ?: continue
            val remoteBookmark = BookmarkRecord.fromJson(remoteJson)
            val localMatch = localByUuid[remoteBookmark.uuid]
            if (localMatch == null) {
                remoteBookmark.id = 0
                localBookmarks.add(remoteBookmark)
            } else if (forceRemote || remoteBookmark.updatedAt > localMatch.updatedAt) {
                localMatch.bookId = remoteBookmark.bookId
                localMatch.bookIdentity = remoteBookmark.bookIdentity
                localMatch.bookTitle = remoteBookmark.bookTitle
                localMatch.bookAuthor = remoteBookmark.bookAuthor
                localMatch.chapterOrderIndex = remoteBookmark.chapterOrderIndex
                localMatch.chapterTitle = remoteBookmark.chapterTitle
                localMatch.chapterOffset = remoteBookmark.chapterOffset
                localMatch.progressPercent = remoteBookmark.progressPercent
                localMatch.summary = remoteBookmark.summary
                localMatch.updatedAt = remoteBookmark.updatedAt
            }
        }
    }

    private fun mergeReadingStats(remoteRows: JSONArray) {
        val rows: MutableList<ReadingTimeEntryRecord> = ArrayList()
        for (i in 0 until remoteRows.length()) {
            val remoteJson = remoteRows.optJSONObject(i) ?: continue
            val row = ReadingTimeEntryRecord.fromJson(remoteJson)
            if (row.date.isNullOrBlank()) continue
            rows.add(row)
        }
        databaseHelper.mergeReadingStatsRows(rows)
    }

    private fun clampProgressIndex() {
        val books = databaseHelper.getBooksMutable()
        val chapters = databaseHelper.getChaptersMutable()
        val chapterCounts: MutableMap<Long, Int> = HashMap()
        for (chapter in chapters) chapterCounts[chapter.bookId] = (chapterCounts[chapter.bookId] ?: 0) + 1
        for (book in books) {
            val count = chapterCounts[book.id] ?: 0
            book.chapterCount = count
            if (count > 0) {
                book.progressIndex = Math.max(0, Math.min(book.progressIndex, count - 1))
            } else {
                book.progressIndex = 0
                book.currentChapterTitle = ""
            }
        }
    }

    private fun localJsonFile(dataDir: File, fileName: String): File =
        File(dataDir, if (READING_STATS_CANONICAL_FILE == fileName) READING_STATS_LEGACY_FILE else fileName)

    private fun canonicalJsonFileName(fileName: String): String =
        if (READING_STATS_LEGACY_FILE == fileName) READING_STATS_CANONICAL_FILE else fileName

    @Throws(Exception::class)
    private fun downloadJsonWithAliases(baseUrl: String, fileName: String, target: File): String {
        if (READING_STATS_CANONICAL_FILE != fileName) {
            webDavClient.downloadBinaryFile(baseUrl + fileName, target)
            return fileName
        }
        if (webDavClient.head(baseUrl + READING_STATS_CANONICAL_FILE).code == 200) {
            webDavClient.downloadBinaryFile(baseUrl + READING_STATS_CANONICAL_FILE, target)
            return READING_STATS_CANONICAL_FILE
        }
        webDavClient.downloadBinaryFile(baseUrl + READING_STATS_LEGACY_FILE, target)
        return READING_STATS_LEGACY_FILE
    }

    @Throws(Exception::class)
    private fun uploadReadingStatsLegacyCopyIfNeeded(jsonFile: File, baseUrl: String, fileName: String) {
        if (READING_STATS_CANONICAL_FILE == fileName) {
            webDavClient.uploadFile(jsonFile, baseUrl + READING_STATS_LEGACY_FILE)
        }
    }

    @Throws(Exception::class)
    private fun readEntityArray(fileName: String, content: String?): JSONArray {
        val trimmed = content?.trim() ?: ""
        if (trimmed.startsWith("{")) {
            val rows = JSONObject(trimmed).optJSONArray("rows")
            if (rows != null) return rows
        }
        return JSONArray(trimmed)
    }

    @Throws(Exception::class)
    private fun uploadManifest(url: String, manifest: JSONObject) {
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists()) tempDir.mkdirs()
        val manifestFile = File(tempDir, MANIFEST_FILE)
        FileWriter(manifestFile).use { writer -> writer.write(manifest.toString(2)) }
        webDavClient.uploadFile(manifestFile, url)
        manifestFile.delete()
    }

    private fun downloadManifestIfExists(url: String): JSONObject? {
        return try {
            if (webDavClient.head(url).code != 200) return null
            val content = webDavClient.downloadText(url)
            if (content.trim().isEmpty()) null else JSONObject(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun manifestAppendFile(container: JSONObject, fileName: String, sha256: String, size: Long) {
        try {
            val entry = JSONObject()
            entry.put("sha256", sha256)
            entry.put("size", size)
            container.put(canonicalJsonFileName(fileName), entry)
        } catch (_: Exception) {
        }
    }

    private fun getManifestFileHash(filesEntry: JSONObject?, fileName: String): String? {
        if (filesEntry == null) return null
        val canonical = canonicalJsonFileName(fileName)
        var entry = filesEntry.optJSONObject(canonical)
        if (entry == null && READING_STATS_CANONICAL_FILE == canonical) {
            entry = filesEntry.optJSONObject(READING_STATS_LEGACY_FILE)
        }
        if (entry == null) return null
        return if (entry.has("sha256")) entry.optString("sha256", "") else null
    }

    private fun compareManifests(remoteFiles: JSONObject?, localFiles: JSONObject?): Set<String> {
        val changed: MutableSet<String> = HashSet()
        if (remoteFiles == null) return changed
        val keys = remoteFiles.keys()
        while (keys.hasNext()) {
            val fileName = canonicalJsonFileName(keys.next())
            var remoteEntry = remoteFiles.optJSONObject(fileName)
            if (remoteEntry == null && READING_STATS_CANONICAL_FILE == fileName) {
                remoteEntry = remoteFiles.optJSONObject(READING_STATS_LEGACY_FILE)
            }
            if (remoteEntry == null) continue
            val remoteHash = remoteEntry.optString("sha256", "")
            var localHash = getManifestFileHash(localFiles, fileName)
            if (localHash == null) localHash = ""
            if (remoteHash != localHash) changed.add(fileName)
        }
        return changed
    }

    private fun saveLocalManifest(manifest: JSONObject) {
        try {
            val file = File(databaseHelper.getDataDir(), "last_manifest.json")
            FileWriter(file).use { writer -> writer.write(manifest.toString(2)) }
        } catch (_: Exception) {
        }
    }

    private fun loadLocalManifest(): JSONObject? {
        return try {
            val file = File(databaseHelper.getDataDir(), "last_manifest.json")
            if (!file.exists()) return null
            val content = readFileString(file)
            if (content == null || content.trim().isEmpty()) null else JSONObject(content)
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldUploadFile(remotePath: String, localFile: File): Boolean {
        return try {
            webDavClient.remoteContentLength(remotePath) != localFile.length()
        } catch (_: Exception) {
            true
        }
    }

    private fun normalizeReplacementRules(rules: MutableList<ReplacementRuleRecord>?) {
        if (rules.isNullOrEmpty()) return
        val uniqueByKey: MutableMap<String, ReplacementRuleRecord> = LinkedHashMap()
        for (rule in ArrayList(rules)) {
            normalizeRuleScope(rule)
            val key = buildRuleKey(rule)
            val existing = uniqueByKey[key]
            if (existing == null) uniqueByKey[key] = rule else mergeDuplicateReplacementRule(existing, rule)
        }
        rules.clear()
        rules.addAll(uniqueByKey.values)
        ensureUniqueRuleIds(rules)
    }

    private fun normalizeRuleScope(rule: ReplacementRuleRecord?) {
        if (rule == null) return
        if ("book" == rule.scope) return
        rule.scope = "global"
        rule.bookId = null
    }

    private fun mergeDuplicateReplacementRule(target: ReplacementRuleRecord?, incoming: ReplacementRuleRecord?) {
        if (target == null || incoming == null) return
        val stableId = if (target.id > 0) target.id else incoming.id
        if (incoming.updatedAt > target.updatedAt) {
            copyReplacementRuleFields(target, incoming)
            target.id = stableId
        } else if (target.id <= 0 && incoming.id > 0) {
            target.id = incoming.id
        }
    }

    private fun copyReplacementRuleFields(target: ReplacementRuleRecord, source: ReplacementRuleRecord) {
        val id = target.id
        target.pattern = source.pattern
        target.replacement = source.replacement
        target.scope = source.scope
        target.bookId = source.bookId
        target.regex = source.regex
        target.active = source.active
        target.updatedAt = source.updatedAt
        target.id = id
        normalizeRuleScope(target)
    }

    private fun ensureUniqueRuleIds(rules: List<ReplacementRuleRecord>) {
        val usedIds: MutableSet<Long> = HashSet()
        for (rule in rules) {
            if (rule.id <= 0 || usedIds.contains(rule.id)) {
                rule.id = nextUnusedId(usedIds)
            }
            usedIds.add(rule.id)
        }
    }

    private fun nextRecordId(records: List<*>, preferredId: Long): Long {
        val usedIds: MutableSet<Long> = HashSet()
        for (record in records) {
            val id = recordId(record)
            if (id > 0) usedIds.add(id)
        }
        if (preferredId > 0 && !usedIds.contains(preferredId)) return preferredId
        return nextUnusedId(usedIds)
    }

    private fun nextUnusedId(usedIds: Set<Long>): Long {
        var maxId = 0L
        for (id in usedIds) if (id > maxId) maxId = id
        var candidate = if (maxId > 0) maxId + 1 else System.currentTimeMillis() * 1000L
        while (usedIds.contains(candidate)) candidate++
        return candidate
    }

    private fun recordId(record: Any?): Long {
        if (record is BookRecord) return record.id
        if (record is ChapterRecord) return record.id
        if (record is ReplacementRuleRecord) return record.id
        if (record is ReaderThemeRecord) return record.id
        if (record is BookmarkRecord) return record.id
        if (record is ReadingTimeEntryRecord) return record.id
        return 0
    }

    private fun buildRuleKey(rule: ReplacementRuleRecord): String {
        val scope = if ("book" == rule.scope) "book" else "global"
        val bookId = if ("book" == scope && rule.bookId != null) rule.bookId!! else 0L
        return (rule.pattern ?: "") + "|" + scope + "|" + bookId
    }

    private fun normalizeTitleAuthor(title: String?, author: String?): String =
        (title?.trim()?.lowercase(Locale.ROOT) ?: "") + "::" + (author?.trim()?.lowercase(Locale.ROOT) ?: "")

    private fun readFileString(file: File): String? {
        return try {
            val bytes = ByteArray(file.length().toInt())
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
            }
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Throws(Exception::class)
    private fun writeChapterTextArchive(archive: File, files: List<ChapterTextFile>, bookIndex: Int, totalBooks: Int, listener: StatusListener) {
        ZipOutputStream(FileOutputStream(archive)).use { zipOutputStream ->
            val buffer = ByteArray(8192)
            val addedPaths: MutableSet<String> = HashSet()
            for (textFile in files) {
                if (!addedPaths.add(textFile.relativePath)) continue
                val entry = ZipEntry(textFile.relativePath)
                entry.time = 0L
                zipOutputStream.putNextEntry(entry)
                FileInputStream(textFile.file).use { inputStream ->
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        zipOutputStream.write(buffer, 0, read)
                    }
                }
                zipOutputStream.closeEntry()
            }
        }
    }

    @Throws(Exception::class)
    private fun extractChapterTextArchive(archive: File): Int {
        val baseDir = databaseHelper.resolveChapterTextFile("chapter_text/__base__")
            ?: throw IllegalStateException("无法定位章节正文目录")
        val realBase = baseDir.parentFile ?: throw IllegalStateException("无法定位章节正文目录")
        if (!realBase.exists() && !realBase.mkdirs()) throw IllegalStateException("无法创建章节正文目录")
        val basePath = realBase.canonicalPath + File.separator
        val buffer = ByteArray(8192)
        var restored = 0
        ZipInputStream(FileInputStream(archive)).use { zipInputStream ->
            while (true) {
                val entry = zipInputStream.nextEntry ?: break
                if (entry.isDirectory) {
                    zipInputStream.closeEntry()
                    continue
                }
                val entryName = sanitizeChapterTextArchiveEntryName(entry.name)
                if (entryName == null) {
                    zipInputStream.closeEntry()
                    continue
                }
                val destination = databaseHelper.resolveChapterTextFile(entryName)
                if (destination == null) {
                    zipInputStream.closeEntry()
                    continue
                }
                val destinationPath = destination.canonicalPath
                if (!destinationPath.startsWith(basePath)) {
                    zipInputStream.closeEntry()
                    continue
                }
                val parent = destination.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    zipInputStream.closeEntry()
                    continue
                }
                FileOutputStream(destination).use { outputStream ->
                    while (true) {
                        val read = zipInputStream.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                    }
                }
                restored++
                zipInputStream.closeEntry()
            }
        }
        return restored
    }

    private fun collectChapterTextFiles(book: BookRecord): List<ChapterTextFile> {
        val files: MutableList<ChapterTextFile> = ArrayList()
        val chapters = databaseHelper.getChaptersWithExternalStorage(book.id)
        for (chapter in chapters) {
            if (chapter.bodyTextPath.isNullOrBlank()) continue
            val entryName = sanitizeChapterTextArchiveEntryName(chapter.bodyTextPath)
            if (entryName == null) continue
            val localFile = databaseHelper.resolveChapterTextFile(entryName)
            if (localFile == null || !localFile.exists() || !localFile.isFile) continue
            files.add(ChapterTextFile(entryName, localFile))
        }
        return files
    }

    private fun cleanupRemoteUnreferencedAssetsIfEnabled(listener: StatusListener, includeChapterText: Boolean, includeFiles: Boolean, includeBackgrounds: Boolean) {
        if (!settingsStore.isWebDavCleanRemoteOrphansEnabled) return
        val books = databaseHelper.getBooks()
        val result = RemoteCleanupResult()
        listener.onStatus("清理远端未引用文件...")
        if (includeChapterText) cleanupRemoteChapterText(books, result)
        if (includeFiles) {
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "books/", collectSourceFileNames(books), result)
            cleanupRemotePlainFileDirectory(webDavClient.backupBaseUrl() + "covers/", collectCoverFileNames(books), result)
        }
        if (includeBackgrounds) {
            val keepBackgrounds: MutableSet<String> = HashSet()
            val bgName = settingsStore.readerBackgroundFileName()
            if (bgName.isNotBlank()) keepBackgrounds.add(bgName)
            cleanupRemotePlainFileDirectory(webDavClient.androidSettingsBackgroundsBaseUrl(), keepBackgrounds, result)
        }
        var summary = "远端清理完成，删除 " + result.deleted + " 个"
        if (result.failed > 0) summary += "，失败 " + result.failed + " 个"
        listener.onStatus(summary)
    }

    private fun cleanupRemoteChapterText(books: List<BookRecord>, result: RemoteCleanupResult) {
        val keepArchiveNames: MutableSet<String> = HashSet()
        val keepLegacyDirectoryNames: MutableSet<String> = HashSet()
        for (book in books) {
            keepLegacyDirectoryNames.add("book_" + book.id)
            if (collectChapterTextFiles(book).isNotEmpty()) {
                keepArchiveNames.add(chapterTextArchiveFileName(book.id))
            }
        }
        cleanupKnownRemoteFile(webDavClient.backupBaseUrl() + "chapter_text.zip", result)
        cleanupRemoteDirectory(webDavClient.backupBaseUrl() + "chapter_text/", result) { _, name, directory ->
            if (!directory && "chapter_text.zip" == name) return@cleanupRemoteDirectory true
            if (!directory && name.matches(Regex("book_\\d+\\.zip"))) return@cleanupRemoteDirectory !keepArchiveNames.contains(name)
            if (directory && name.matches(Regex("book_\\d+"))) return@cleanupRemoteDirectory !keepLegacyDirectoryNames.contains(name)
            false
        }
    }

    private fun cleanupRemotePlainFileDirectory(remoteDirectoryUrl: String, keepNames: Set<String>, result: RemoteCleanupResult) {
        cleanupRemoteDirectory(remoteDirectoryUrl, result) { _, name, directory -> !directory && !keepNames.contains(name) }
    }

    private fun cleanupRemoteDirectory(remoteDirectoryUrl: String, result: RemoteCleanupResult, predicate: RemoteCleanupPredicate) {
        val remoteFiles: List<String> = try {
            webDavClient.listFiles(remoteDirectoryUrl)
        } catch (error: Exception) {
            result.failed++
            Log.w(TAG, "列出远端目录失败: " + remoteDirectoryUrl, error)
            return
        }
        for (remoteUrl in remoteFiles) {
            val name = remoteFileName(remoteUrl)
            if (name.isNullOrBlank()) continue
            val directory = remoteUrl.endsWith("/")
            if (!predicate.shouldDelete(remoteUrl, name, directory)) continue
            deleteRemoteFile(remoteUrl, result)
        }
    }

    private fun cleanupKnownRemoteFile(remoteUrl: String, result: RemoteCleanupResult) {
        try {
            if (webDavClient.head(remoteUrl).code == 200) deleteRemoteFile(remoteUrl, result)
        } catch (_: Exception) {
        }
    }

    private fun deleteRemoteFile(remoteUrl: String, result: RemoteCleanupResult) {
        try {
            webDavClient.delete(remoteUrl)
            result.deleted++
        } catch (error: Exception) {
            result.failed++
            Log.w(TAG, "删除远端残留失败: " + remoteUrl, error)
        }
    }

    private fun collectSourceFileNames(books: List<BookRecord>): Set<String> {
        val names: MutableSet<String> = HashSet()
        for (book in books) {
            if (!book.localPath.isNullOrBlank()) names.add(File(book.localPath!!).name)
        }
        return names
    }

    private fun collectCoverFileNames(books: List<BookRecord>): Set<String> {
        val names: MutableSet<String> = HashSet()
        for (book in books) {
            if (!book.coverPath.isNullOrBlank()) names.add(File(book.coverPath!!).name)
        }
        return names
    }

    private fun chapterTextArchiveRemotePath(bookId: Long): String =
        webDavClient.backupBaseUrl() + "chapter_text/" + chapterTextArchiveFileName(bookId)

    private fun chapterTextArchiveFileName(bookId: Long): String = "book_" + bookId + ".zip"

    private fun remoteFileName(remoteUrl: String?): String? {
        if (remoteUrl.isNullOrBlank()) return null
        var value = remoteUrl ?: return null
        while (value.endsWith("/")) value = value.substring(0, value.length - 1)
        val index = value.lastIndexOf('/')
        val name = if (index >= 0) value.substring(index + 1) else value
        return try {
            URLDecoder.decode(name, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            name
        }
    }

    private fun sanitizeChapterTextArchiveEntryName(value: String?): String? {
        if (value.isNullOrBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) return null
        for (segment in value.split("/")) {
            if (segment.isBlank() || "." == segment || ".." == segment) return null
        }
        return value
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun ensureAnySyncScopeSelected() {
        if (shouldSyncDatabaseSnapshot() || shouldSyncSettingsSnapshot() || settingsStore.isWebDavSyncFilesEnabled || settingsStore.isWebDavSyncBackgroundsEnabled) return
        throw IllegalStateException("请先选择至少一种同步内容")
    }

    private fun ensureRestoreScopeSelected() {
        if (shouldSyncDatabaseSnapshot() || shouldSyncSettingsSnapshot()) return
        throw IllegalStateException("当前未包含书架、界面、主题或背景快照，无法执行恢复")
    }

    private fun shouldSyncDatabaseSnapshot(): Boolean =
        settingsStore.isWebDavSyncBookshelfEnabled || settingsStore.isWebDavSyncThemesEnabled

    private fun shouldSyncSettingsSnapshot(): Boolean =
        settingsStore.isWebDavSyncUiSettingsEnabled || settingsStore.isWebDavSyncThemesEnabled || settingsStore.isWebDavSyncBackgroundsEnabled

    private fun cleanupLocalTempCache() {
        val cacheDir = context.cacheDir ?: return
        deletePathRecursively(File(cacheDir, "backup"))
        deletePathRecursively(File(cacheDir, "backup_restore"))
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.startsWith("restore_")) file.delete()
        }
    }

    private fun deletePathRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) deletePathRecursively(child)
            }
        }
        file.delete()
    }

    fun interface StatusListener {
        fun onStatus(status: String)
        fun onProgress(current: Int, total: Int) = Unit
    }

    private fun interface RemoteCleanupPredicate {
        fun shouldDelete(remoteUrl: String, name: String, directory: Boolean): Boolean
    }

    private class RemoteCleanupResult {
        var deleted = 0
        var failed = 0
    }

    private class ChapterTextFile(
        val relativePath: String,
        val file: File,
    )

    companion object {
        private const val TAG = "WebDavBackup"
        const val RESOLUTION_LOCAL = "local"
        const val RESOLUTION_REMOTE = "remote"
        const val RESOLUTION_MERGE = "merge"
        private const val READING_STATS_CANONICAL_FILE = "readingStats.json"
        private const val READING_STATS_LEGACY_FILE = "reading_stats.json"
        private val SYNC_JSON_FILES = arrayOf(
            "books.json",
            "chapters.json",
            "rules.json",
            "themes.json",
            "bookmarks.json",
            READING_STATS_CANONICAL_FILE,
        )
        private const val MANIFEST_FILE = "manifest.json"

        @JvmStatic
        fun computeFileSha256(file: File): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                    }
                }
                val builder = StringBuilder()
                for (b in digest.digest()) {
                    builder.append(String.format(Locale.ROOT, "%02x", b))
                }
                builder.toString()
            } catch (_: Exception) {
                ""
            }
        }
    }
}



