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
import com.metahumanz.pacilread.stats.ReadingStatsUtils
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
import java.util.UUID
import java.util.zip.GZIPInputStream
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
    fun previewIncrementalBackup(listener: StatusListener): SyncBackupPlan {
        cleanupLocalTempCache()
        ensureAnySyncScopeSelected()
        listener.onStatus("检查本地清单...")
        databaseHelper.flush()
        val dataDir = databaseHelper.getDataDir()
        val remoteManifest = resolveIncrementalSnapshotIfPresent()?.manifest
        val localFiles = buildLocalJsonManifest(dataDir)
        val prepared = prepareIncrementalAssets(
            settingsStore.isWebDavSyncBookshelfEnabled,
            settingsStore.isWebDavSyncFilesEnabled,
            settingsStore.isWebDavSyncBackgroundsEnabled,
            listener,
        )
        val localAssets = buildPreparedAssetManifest(prepared.assets)
        listener.onStatus("检查远端清单...")
        val plan = buildSyncBackupPlan(
            localFiles,
            remoteManifest?.optJSONObject("files"),
            localAssets,
            remoteManifest?.optJSONObject("assets"),
        )
        cleanupLocalTempCache()
        return plan
    }

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
        val resolved = if (SyncDiffPreview.MODE_FULL == mode) resolveFullSnapshot() else null
        val effectiveBaseUrl = resolved?.dataBaseUrl ?: baseUrl
        val manifestUrl = effectiveBaseUrl + MANIFEST_FILE
        if (webDavClient.head(manifestUrl).code != 200) throw IllegalStateException("云端 manifest 不存在")
        val manifestText = webDavClient.downloadText(manifestUrl)
        if (manifestText.isBlank()) throw IllegalStateException("云端 manifest 为空")
        val remoteManifest = JSONObject(manifestText)
        preview.remoteManifest = remoteManifest
        val dataDir = databaseHelper.getDataDir()
        for ((index, fileName) in SYNC_JSON_FILES.withIndex()) {
            listener.onStatus("预览 ${index + 1}/${SYNC_JSON_FILES.size} · $fileName...")
            listener.onProgress(index + 1, SYNC_JSON_FILES.size)
            val localArray = readLocalEntityArray(dataDir, fileName)
            val remoteArray = downloadRemoteEntityArray(effectiveBaseUrl, fileName, remoteManifest)
            preview.remoteEntities[fileName] = remoteArray
            appendDiffItems(preview, entityTypeForFile(fileName), localArray, remoteArray)
        }
        listener.onStatus("差异预览完成")
        return preview
    }

    @Throws(Exception::class)
    private fun applyRemoteEntities(preview: SyncDiffPreview, forceRemote: Boolean, listener: StatusListener) {
        databaseHelper.flush()
        databaseHelper.backupBeforeIdentityRestore()
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
                "bookmarks.json" -> mergeBookmarks(array, restoredBookIdMap, forceRemote)
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
        val content = readFileString(file) ?: throw IllegalStateException("读取本地 $fileName 失败")
        if (content.isBlank()) throw IllegalStateException("本地 $fileName 为空")
        return readEntityArray(fileName, content)
    }

    private fun downloadRemoteEntityArray(baseUrl: String, fileName: String, manifest: JSONObject): JSONArray {
        val tempFile = File(context.cacheDir, "preview_" + fileName)
        try {
            val remoteFileName = downloadJsonWithAliases(baseUrl, fileName, tempFile)
            val entry = getManifestFileEntry(manifest.optJSONObject("files"), remoteFileName)
                ?: throw IllegalStateException("manifest 缺少 $fileName 校验信息")
            validateFileAgainstManifest(tempFile, entry, fileName)
            val content = readFileString(tempFile) ?: throw IllegalStateException("读取云端 $fileName 失败")
            return readEntityArray(fileName, content)
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
            val key = ReadingStatsUtils.canonicalBookIdentity(json.optString("readingStatsKey", ""), json.optString("title", ""), json.optString("author", ""))
            if (key.isNotBlank()) return key
            return normalizeTitleAuthor(json.optString("title", ""), json.optString("author", ""))
        }
        if ("chapters" == entityType) return json.optLong("bookId", 0L).toString() + ":" + json.optInt("orderIndex", 0)
        if ("rules" == entityType) return json.optString("pattern", "") + "|" + json.optString("scope", "global") + "|" + json.optLong("bookId", 0L)
        if ("themes" == entityType) return json.optString("name", "")
        if ("bookmarks" == entityType) return json.optString("uuid", "")
        if ("readingStats" == entityType) return json.optString("sourceDeviceId", "") + "|" + json.optString("date", "") + "|" + ReadingStatsUtils.canonicalBookIdentity(json.optString("bookIdentity", ""), json.optString("bookTitle", ""), json.optString("bookAuthor", ""))
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

        if (shouldSyncSettingsSnapshot()) uploadSettingsSnapshot(listener)
        if (!shouldSyncDatabaseSnapshot()) {
            cleanupLocalTempCache()
            settingsStore.webDavLastFullBackupAt = System.currentTimeMillis()
            listener.onStatus("全量备份完成")
            return
        }

        val generationId = System.currentTimeMillis().toString() + "-" + UUID.randomUUID()
        val snapshotPath = snapshotPrefix(generationId)
        val dataDir = databaseHelper.getDataDir()
        val manifest = JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAt", System.currentTimeMillis())
            put("generationId", generationId)
            put("snapshotPrefix", snapshotPath)
        }
        val filesEntry = JSONObject()
        for (fileName in SYNC_JSON_FILES) {
            val jsonFile = localJsonFile(dataDir, fileName)
            if (!jsonFile.exists()) throw IllegalStateException("本地缺少完整快照文件: $fileName")
            val sha256 = computeFileSha256(jsonFile)
            if (sha256.isBlank()) throw IllegalStateException("计算 $fileName SHA-256 失败")
            manifestAppendFile(filesEntry, fileName, sha256, jsonFile.length())
        }
        manifest.put("files", filesEntry)

        listener.onStatus("检查本地快照资源...")
        val prepared = prepareFullSnapshotAssets(includeChapterText, includeFiles, listener)
        manifest.put("assets", buildPreparedAssetManifest(prepared.assets))
        manifest.put("scopes", JSONObject().apply {
            put("chapterText", includeChapterText)
            put("covers", includeFiles)
            put("sourceFiles", includeFiles)
        })
        val manifestText = manifest.toString(2)
        listener.onStatus("本地预检完成")
        val chapterZipCount = prepared.assets.count { it.key.startsWith("chapter_text/") }
        Log.i(TAG, "[FullBackup] generationId=$generationId snapshotPrefix=$snapshotPath JSON count=${SYNC_JSON_FILES.size} asset count=${prepared.assets.size} chapter zip count=$chapterZipCount warnings count=${prepared.warnings.size}")

        listener.onStatus("创建完整快照...")
        webDavClient.ensureBackupRootDirectory()
        webDavClient.ensureSnapshotDirectories(snapshotPath)
        val snapshotBase = webDavClient.snapshotBaseUrl(snapshotPath)
        listener.onStatus("上传 JSON 数据文件...")
        for ((index, fileName) in SYNC_JSON_FILES.withIndex()) {
            val jsonFile = localJsonFile(dataDir, fileName)
            for (directory in arrayOf("database/", "sync/")) {
                webDavClient.uploadFile(jsonFile, snapshotBase + directory + canonicalJsonFileName(fileName))
            }
            listener.onStatus("上传 JSON ${index + 1}/${SYNC_JSON_FILES.size} · $fileName")
            listener.onProgress(index + 1, SYNC_JSON_FILES.size)
        }
        uploadPreparedAssets(prepared.assets, snapshotBase, listener)
        listener.onStatus("上传快照 manifest...")
        webDavClient.uploadText(snapshotBase + "database/" + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
        webDavClient.uploadText(snapshotBase + "sync/" + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
        listener.onStatus("提交完整快照...")
        uploadSnapshotCommit(webDavClient.backupBaseUrl() + "database/" + COMMIT_FILE, generationId, snapshotPath, manifestText)
        uploadSnapshotCommit(webDavClient.backupBaseUrl() + "sync/" + COMMIT_FILE, generationId, snapshotPath, manifestText)

        listener.onStatus("更新兼容镜像...")
        webDavClient.ensureDirectory(webDavClient.backupBaseUrl() + "database/")
        webDavClient.ensureDirectory(webDavClient.backupBaseUrl() + "sync/")
        if (includeFiles) webDavClient.ensureBookAssetDirectories()
        if (includeChapterText) webDavClient.ensureChapterTextDirectory()
        uploadLegacySnapshotMirror(dataDir, prepared.assets, manifestText, includeChapterText, includeFiles, listener)
        if (includeBackgrounds) uploadBackgroundIfPresent(listener)
        cleanupRemoteUnreferencedAssetsIfEnabled(listener, includeChapterText, includeFiles, includeBackgrounds)
        settingsStore.webDavLastFullBackupAt = System.currentTimeMillis()
        cleanupLocalTempCache()
        if (prepared.warnings.isNotEmpty()) {
            Log.w(TAG, prepared.warnings.joinToString("\n"))
            listener.onStatus("全量备份完成，${prepared.warnings.size}项可选资源未备份")
        } else {
            listener.onStatus("全量备份完成")
        }
    }

    @Throws(Exception::class)
    fun incrementalBackup(listener: StatusListener) {
        cleanupLocalTempCache()
        listener.onStatus("准备同步...")
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
            val remoteSnapshot = resolveIncrementalSnapshotIfPresent()
            val remoteManifest = remoteSnapshot?.manifest
            listener.onStatus("检查本地清单...")

            val localManifest = JSONObject()
            localManifest.put("schemaVersion", 1)
            localManifest.put("generatedAt", System.currentTimeMillis())
            val localFiles = buildLocalJsonManifest(dataDir)
            localManifest.put("files", localFiles)

            val prepared = prepareIncrementalAssets(includeChapterText, includeFiles, includeBackgrounds, listener)
            val localAssets = buildPreparedAssetManifest(prepared.assets)
            localManifest.put("assets", localAssets)
            listener.onStatus("检查远端清单...")
            val plan = buildSyncBackupPlan(
                localFiles,
                remoteManifest?.optJSONObject("files"),
                localAssets,
                remoteManifest?.optJSONObject("assets"),
            )
            listener.onStatus("发现变化：JSON ${plan.changedFiles.size} 项，资源 ${plan.changedAssets.size} 项，预计上传 ${formatUploadSize(plan.uploadSize)}")
            if (plan.changedFiles.isEmpty() && plan.changedAssets.isEmpty()) {
                listener.onStatus("无变化，跳过上传")
            } else {
                listener.onStatus("正在上传增量快照...")
                val generationId = System.currentTimeMillis().toString() + "-" + UUID.randomUUID()
                val syncSnapshotPath = "sync-snapshots/$generationId"
                localManifest.put("generationId", generationId)
                localManifest.put("snapshotPrefix", syncSnapshotPath)
                val manifestText = localManifest.toString(2)

                listener.onStatus("创建增量快照...")
                webDavClient.ensureSyncSnapshotDirectories(syncSnapshotPath)
                val snapshotBase = webDavClient.snapshotBaseUrl(syncSnapshotPath)
                for ((index, fileName) in plan.changedFiles.withIndex()) {
                    val jsonFile = localJsonFile(dataDir, fileName)
                    if (!jsonFile.exists()) continue
                    listener.onStatus("上传变化 JSON ${index + 1}/${plan.changedFiles.size} · $fileName")
                    listener.onProgress(index + 1, plan.changedFiles.size)
                    webDavClient.uploadFile(jsonFile, snapshotBase + canonicalJsonFileName(fileName))
                }
                uploadPreparedAssets(prepared.assets.filter { it.key in plan.changedAssets }, snapshotBase, listener)
                listener.onStatus("上传增量 manifest...")
                webDavClient.uploadText(snapshotBase + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
                listener.onStatus("提交增量快照...")
                uploadSnapshotCommit(webDavClient.syncBaseUrl() + COMMIT_FILE, generationId, syncSnapshotPath, manifestText)
                listener.onStatus("更新增量兼容镜像...")
                uploadIncrementalLegacyMirror(dataDir, prepared.assets, manifestText, plan, listener)
            }
            saveLocalManifest(localManifest)
        }

        if (shouldSyncSettingsSnapshot()) uploadSettingsSnapshot(listener)

        settingsStore.webDavLastLiteBackupAt = System.currentTimeMillis()
        cleanupLocalTempCache()
        listener.onStatus("增量备份完成")
    }

    @Throws(Exception::class)
    fun fullRestore(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureRestoreScopeSelected()
        if (shouldSyncDatabaseSnapshot()) {
            val resolved = resolveFullSnapshot()
            val databaseUrl = resolved.dataBaseUrl
            val assetBaseUrl = resolved.assetBaseUrl
            val manifest = resolved.manifest
            val filesEntry = manifest.optJSONObject("files") ?: throw IllegalStateException("云端 manifest 缺少文件清单")
            Log.i(TAG, "[FullRestore] mode=" + if (resolved.strict) "snapshot" else "legacy")
            Log.i(TAG, "[FullRestore] generationId=" + resolved.generationId)
            Log.i(TAG, "[FullRestore] snapshotPrefix=" + resolved.snapshotPrefix)

            listener.onStatus("下载并校验 JSON 数据文件...")
            val dataDir = databaseHelper.getDataDir()
            if (!dataDir.exists()) dataDir.mkdirs()
            val stagingDir = File(context.cacheDir, "backup_restore/json")
            if (!stagingDir.exists() && !stagingDir.mkdirs()) throw IllegalStateException("无法创建恢复缓存目录")
            for ((index, fileName) in SYNC_JSON_FILES.withIndex()) {
                val target = File(stagingDir, canonicalJsonFileName(fileName))
                listener.onStatus("校验 JSON ${index + 1}/${SYNC_JSON_FILES.size} · $fileName...")
                listener.onProgress(index + 1, SYNC_JSON_FILES.size)
                val remoteFileName = downloadJsonWithAliases(databaseUrl, fileName, target)
                val entry = getManifestFileEntry(filesEntry, remoteFileName)
                    ?: throw IllegalStateException("manifest 缺少 $fileName 校验信息")
                validateFileAgainstManifest(target, entry, fileName)
                val content = readFileString(target) ?: throw IllegalStateException("读取 $fileName 失败")
                readEntityArray(fileName, content)
            }

            databaseHelper.backupBeforeIdentityRestore()
            for (fileName in SYNC_JSON_FILES) {
                File(stagingDir, canonicalJsonFileName(fileName)).copyTo(localJsonFile(dataDir, fileName), overwrite = true)
            }
            databaseHelper.reloadFromDisk()
            normalizeReplacementRules(databaseHelper.getRulesMutable())
            databaseHelper.flush()
            databaseHelper.rebaseLocalAssetPaths()

            val assets = manifest.optJSONObject("assets") ?: JSONObject()
            val strictSnapshot = resolved.strict
            val scopes = manifest.optJSONObject("scopes")
            listener.onStatus("恢复书籍资源文件...")
            restoreBookAssetFiles(
                listener = listener,
                includeSourceFiles = settingsStore.isWebDavSyncFilesEnabled && scopes?.optBoolean("sourceFiles", true) != false,
                assets = assets,
                strictCovers = strictSnapshot && scopes?.optBoolean("covers", false) == true,
                strictSourceFiles = strictSnapshot && scopes?.optBoolean("sourceFiles", false) == true,
                includeCovers = scopes?.optBoolean("covers", true) != false,
                remoteBaseUrl = assetBaseUrl,
            )
            if (scopes?.optBoolean("chapterText", true) != false) {
                listener.onStatus("恢复章节正文...")
                restoreChapterTextFiles(listener, assets, strictSnapshot && scopes?.optBoolean("chapterText", false) == true, assetBaseUrl)
            }
        }

        restoreSettingsJsonIfPresent(listener)
        cleanupLocalTempCache()
        listener.onStatus("全量恢复完成")
    }

    @Throws(Exception::class)
    fun incrementalRestore(listener: StatusListener) {
        cleanupLocalTempCache()
        ensureRestoreScopeSelected()

        if (shouldSyncDatabaseSnapshot()) {
            val resolved = resolveIncrementalSnapshotIfPresent()
                ?: throw IllegalStateException("云端 manifest 不存在，请先执行增量备份")
            val remoteManifest = resolved.manifest
            val remoteDataBaseUrl = resolved.dataBaseUrl
            val remoteAssetBaseUrl = resolved.assetBaseUrl
            val localManifest = loadLocalManifest()
            val changedFiles = compareManifests(remoteManifest.optJSONObject("files"), localManifest?.optJSONObject("files"))
            val changedAssetKeys = compareAssetKeys(remoteManifest.optJSONObject("assets"), localManifest?.optJSONObject("assets"))
            if (changedFiles.isEmpty() && changedAssetKeys.isEmpty()) {
                listener.onStatus("已是最新，无需恢复")
                cleanupLocalTempCache()
                return
            }

            databaseHelper.flush()
            databaseHelper.backupBeforeIdentityRestore()
            listener.onStatus("下载并合并变化的数据...")

            var mergedBooks = false
            var mergedChapters = false
            val restoredBookIdMap: MutableMap<Long, Long> = HashMap(buildRemoteBookIdMapping(
                downloadRemoteEntityArray(remoteDataBaseUrl, "books.json", remoteManifest),
                databaseHelper.getBooks(),
            ))

            val changedJsonFiles = SYNC_JSON_FILES.filter(changedFiles::contains)
            for ((index, fileName) in changedJsonFiles.withIndex()) {
                if (!changedFiles.contains(fileName)) continue
                val tempFile = File(context.cacheDir, "restore_" + fileName)
                listener.onStatus("下载变化 JSON ${index + 1}/${changedJsonFiles.size} · $fileName...")
                listener.onProgress(index + 1, changedJsonFiles.size)
                val remoteFileName = downloadJsonWithAliases(remoteDataBaseUrl, fileName, tempFile)
                val entry = getManifestFileEntry(remoteManifest.optJSONObject("files"), remoteFileName)
                    ?: throw IllegalStateException("manifest 缺少 $fileName 校验信息")
                validateFileAgainstManifest(tempFile, entry, fileName)
                val content = readFileString(tempFile) ?: throw IllegalStateException("读取 $fileName 失败")
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
                    "bookmarks.json" -> mergeBookmarks(array, restoredBookIdMap)
                    READING_STATS_CANONICAL_FILE -> mergeReadingStats(array)
                }
                tempFile.delete()
            }

            if (mergedBooks || mergedChapters) clampProgressIndex()
            databaseHelper.flush()
            databaseHelper.rebaseLocalAssetPaths()
            restoreChangedAssets(
                listener,
                remoteManifest.optJSONObject("assets"),
                localManifest?.optJSONObject("assets"),
                restoredBookIdMap,
                remoteAssetBaseUrl,
                resolved.strict,
            )
            saveLocalManifest(remoteManifest)
        }

        restoreSettingsJsonIfPresent(listener)
        cleanupLocalTempCache()
        listener.onStatus("增量恢复完成")
    }

    @Throws(Exception::class)
    private fun resolveFullSnapshot(): ResolvedRemoteSnapshot {
        val root = webDavClient.backupBaseUrl()
        val rootDatabase = root + "database/"
        val commitUrl = rootDatabase + COMMIT_FILE
        val commitResponse = webDavClient.head(commitUrl)
        if (commitResponse.code == 200) {
            val commit = JSONObject(webDavClient.downloadText(commitUrl))
            val prefix = commit.optString("snapshotPrefix", "").trim()
            if (prefix.isBlank()) throw IllegalStateException("完整快照提交信息不一致")
            val snapshotBase = webDavClient.snapshotBaseUrl(prefix)
            val manifestUrl = snapshotBase + "database/" + MANIFEST_FILE
            if (webDavClient.head(manifestUrl).code != 200) throw IllegalStateException("完整快照提交信息不一致")
            val manifestText = webDavClient.downloadText(manifestUrl)
            val manifest = JSONObject(manifestText)
            val pointer = validateSnapshotPointer(commit, manifest, manifestText)
            return ResolvedRemoteSnapshot(
                manifest = manifest,
                manifestText = manifestText,
                dataBaseUrl = snapshotBase + "database/",
                assetBaseUrl = snapshotBase,
                strict = true,
                generationId = pointer.generationId,
                snapshotPrefix = pointer.snapshotPrefix,
            )
        }
        if (commitResponse.code != 404) throw IllegalStateException("读取完整快照提交信息失败: HTTP ${commitResponse.code}")
        val manifestUrl = rootDatabase + MANIFEST_FILE
        if (webDavClient.head(manifestUrl).code != 200) throw IllegalStateException("云端没有完整 manifest，请先执行全量备份")
        val manifestText = webDavClient.downloadText(manifestUrl)
        if (manifestText.isBlank()) throw IllegalStateException("云端 manifest 为空")
        val manifest = JSONObject(manifestText)
        if (manifest.optString("generationId", "").isNotBlank()) {
            throw IllegalStateException("完整快照尚未提交完成")
        }
        return ResolvedRemoteSnapshot(
            manifest = manifest,
            manifestText = manifestText,
            dataBaseUrl = rootDatabase,
            assetBaseUrl = root,
            strict = false,
            generationId = null,
            snapshotPrefix = null,
        )
    }

    @Throws(Exception::class)
    private fun resolveIncrementalSnapshotIfPresent(): ResolvedRemoteSnapshot? {
        val root = webDavClient.backupBaseUrl()
        val syncBase = webDavClient.syncBaseUrl()
        val commitUrl = syncBase + COMMIT_FILE
        val commitResponse = webDavClient.head(commitUrl)
        if (commitResponse.code == 200) {
            val commit = JSONObject(webDavClient.downloadText(commitUrl))
            val prefix = commit.optString("snapshotPrefix", "").trim()
            if (prefix.isBlank()) throw IllegalStateException("完整快照提交信息不一致")
            val snapshotBase = webDavClient.snapshotBaseUrl(prefix)
            val dataBase = if (prefix.startsWith("snapshots/")) snapshotBase + "sync/" else snapshotBase
            val manifestUrl = dataBase + MANIFEST_FILE
            if (webDavClient.head(manifestUrl).code != 200) throw IllegalStateException("完整快照提交信息不一致")
            val manifestText = webDavClient.downloadText(manifestUrl)
            val manifest = JSONObject(manifestText)
            val pointer = validateSnapshotPointer(commit, manifest, manifestText)
            return ResolvedRemoteSnapshot(
                manifest = manifest,
                manifestText = manifestText,
                dataBaseUrl = dataBase,
                assetBaseUrl = snapshotBase,
                strict = true,
                generationId = pointer.generationId,
                snapshotPrefix = pointer.snapshotPrefix,
            )
        }
        if (commitResponse.code != 404) throw IllegalStateException("读取增量快照提交信息失败: HTTP ${commitResponse.code}")
        val manifestUrl = syncBase + MANIFEST_FILE
        if (webDavClient.head(manifestUrl).code != 200) return null
        val manifestText = webDavClient.downloadText(manifestUrl)
        if (manifestText.isBlank()) return null
        val manifest = JSONObject(manifestText)
        if (manifest.optString("generationId", "").isNotBlank()) {
            throw IllegalStateException("完整快照尚未提交完成")
        }
        return ResolvedRemoteSnapshot(
            manifest = manifest,
            manifestText = manifestText,
            dataBaseUrl = syncBase,
            assetBaseUrl = root,
            strict = false,
            generationId = null,
            snapshotPrefix = null,
        )
    }

    @Throws(Exception::class)
    private fun uploadIncrementalLegacyMirror(
        dataDir: File,
        assets: List<PreparedAsset>,
        manifestText: String,
        plan: SyncBackupPlan,
        listener: StatusListener,
    ) {
        val syncBase = webDavClient.syncBaseUrl()
        for (fileName in plan.changedFiles) {
            val file = localJsonFile(dataDir, fileName)
            if (!file.exists()) continue
            webDavClient.uploadFile(file, syncBase + canonicalJsonFileName(fileName))
            uploadReadingStatsLegacyCopyIfNeeded(file, syncBase, fileName)
        }
        val changedAssetKeys = plan.changedAssets.toHashSet()
        for ((index, asset) in assets.withIndex()) {
            if (asset.key !in changedAssetKeys) continue
            listener.onStatus("更新增量兼容资源 ${index + 1}/${assets.size} · ${asset.key}")
            val remoteUrl = if (asset.key.startsWith("backgrounds/")) {
                webDavClient.androidSettingsBackgroundsBaseUrl() + asset.file.name
            } else {
                webDavClient.backupBaseUrl() + asset.key
            }
            webDavClient.uploadFile(asset.file, remoteUrl)
        }
        webDavClient.uploadText(syncBase + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
    }

    private fun buildLocalJsonManifest(dataDir: File): JSONObject {
        val files = JSONObject()
        for (fileName in SYNC_JSON_FILES) {
            val jsonFile = localJsonFile(dataDir, fileName)
            if (!jsonFile.exists()) continue
            manifestAppendFile(files, fileName, computeFileSha256(jsonFile), jsonFile.length())
        }
        return files
    }

    private fun formatUploadSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kib)
        return String.format(Locale.ROOT, "%.1f MB", kib / 1024.0)
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

    private fun restoreBookAssetFiles(
        listener: StatusListener,
        includeSourceFiles: Boolean,
        assets: JSONObject? = null,
        strictCovers: Boolean = false,
        strictSourceFiles: Boolean = false,
        includeCovers: Boolean = true,
        remoteBaseUrl: String = webDavClient.backupBaseUrl(),
    ) {
        val books = databaseHelper.getBooks()
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            if (includeCovers && !book.coverPath.isNullOrBlank()) {
                val coverFile = File(book.coverPath!!)
                val remotePath = remoteBaseUrl + "covers/" + coverFile.name
                restoreRemoteFileIfPresent(
                    remotePath,
                    coverFile,
                    "恢复封面 " + (i + 1) + "/" + total + "...",
                    listener,
                    assets?.optJSONObject("covers/" + coverFile.name),
                    strictCovers,
                )
            }
            if (includeSourceFiles && !book.localPath.isNullOrBlank()) {
                val sourceFile = File(book.localPath!!)
                val remotePath = remoteBaseUrl + "books/" + sourceFile.name
                restoreRemoteFileIfPresent(
                    remotePath,
                    sourceFile,
                    "恢复书籍源文件 " + (i + 1) + "/" + total + "...",
                    listener,
                    assets?.optJSONObject("books/" + sourceFile.name),
                    strictSourceFiles,
                )
            }
        }
    }

    private fun restoreRemoteFileIfPresent(
        remotePath: String,
        destination: File,
        status: String,
        listener: StatusListener,
        expected: JSONObject? = null,
        strict: Boolean = false,
    ) {
        try {
            if (strict && expected == null) throw IllegalStateException("资源清单缺少: $remotePath")
            val head = webDavClient.head(remotePath)
            if (head.code == 404 || head.code < 200 || head.code >= 300) {
                if (strict) throw IllegalStateException("云端资源缺失: $remotePath")
                return
            }
            var remoteLength = -1L
            try {
                remoteLength = webDavClient.remoteContentLength(remotePath)
            } catch (_: Exception) {
            }
            if (!strict && destination.exists() && remoteLength >= 0 && destination.length() == remoteLength) return
            val parent = destination.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            listener.onStatus(status)
            webDavClient.downloadBinaryFile(remotePath, destination)
            if (expected != null) validateFileAgainstManifest(destination, expected, destination.name)
        } catch (error: Exception) {
            if (strict) throw error
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
                    if (isAssetChanged(remoteAssets, assetKey, backgroundFile)) {
                        listener.onStatus("上传背景图片...")
                        webDavClient.uploadFile(backgroundFile, remotePath)
                    }
                }
            }
        }
    }

    private fun restoreChangedAssets(
        listener: StatusListener,
        remoteAssets: JSONObject?,
        localAssets: JSONObject?,
        restoredBookIdMap: Map<Long, Long> = emptyMap(),
        remoteBaseUrl: String = webDavClient.backupBaseUrl(),
        strictSnapshot: Boolean = false,
    ) {
        if (remoteAssets == null) return
        val books = databaseHelper.getBooks()
        for (book in books) {
            if (!book.coverPath.isNullOrBlank()) {
                val coverFile = File(book.coverPath!!)
                val assetKey = "covers/" + coverFile.name
                if (isAssetChanged(remoteAssets, assetKey, coverFile)) {
                    restoreRemoteFileIfPresent(
                        incrementalAssetRemoteUrl(remoteBaseUrl, assetKey, strictSnapshot), coverFile,
                        "恢复封面...", listener, remoteAssets.optJSONObject(assetKey),
                    )
                }
            }
            if (settingsStore.isWebDavSyncFilesEnabled && !book.localPath.isNullOrBlank()) {
                val sourceFile = File(book.localPath!!)
                val assetKey = "books/" + sourceFile.name
                if (isAssetChanged(remoteAssets, assetKey, sourceFile)) {
                    restoreRemoteFileIfPresent(
                        incrementalAssetRemoteUrl(remoteBaseUrl, assetKey, strictSnapshot), sourceFile,
                        "恢复源文件...", listener, remoteAssets.optJSONObject(assetKey),
                    )
                }
            }
        }
        val keys = remoteAssets.keys()
        while (keys.hasNext()) {
            val assetKey = keys.next()
            if (assetKey.startsWith("backgrounds/")) {
                val localPath = settingsStore.readerBackgroundPath
                if (localPath.isNotBlank()) {
                    val destination = File(localPath)
                    if (isAssetChanged(remoteAssets, assetKey, destination)) {
                        restoreRemoteFileIfPresent(
                            incrementalAssetRemoteUrl(remoteBaseUrl, assetKey, strictSnapshot),
                            destination,
                            "恢复背景图片...",
                            listener,
                            remoteAssets.optJSONObject(assetKey),
                        )
                    }
                }
                continue
            }
            if (!assetKey.startsWith("chapter_text/") || !assetKey.endsWith(".zip")) continue
            val remoteBookId = assetKey.removePrefix("chapter_text/book_").removeSuffix(".zip").toLongOrNull() ?: continue
            val localBookId = restoredBookIdMap[remoteBookId] ?: remoteBookId
            val book = books.firstOrNull { it.id == localBookId } ?: continue
            val expected = remoteAssets.optJSONObject(assetKey) ?: continue
            val archive = File(context.cacheDir, "restore_chapter_${localBookId}.zip")
            try {
                listener.onStatus("恢复章节正文包 · $assetKey")
                val remotePath = incrementalAssetRemoteUrl(remoteBaseUrl, assetKey, strictSnapshot)
                webDavClient.downloadBinaryFile(remotePath, archive)
                validateFileAgainstManifest(archive, expected, assetKey)
                extractChapterTextArchive(archive, book, remoteBookId, localBookId)
                if (!validateBookChapterTextFiles(book)) throw IllegalStateException("章节正文文件验收失败: ${book.title}")
            } finally {
                archive.delete()
            }
        }
    }

    private fun incrementalAssetRemoteUrl(baseUrl: String, assetKey: String, strictSnapshot: Boolean): String {
        if (!strictSnapshot && assetKey.startsWith("backgrounds/")) {
            return webDavClient.androidSettingsBackgroundsBaseUrl() + assetKey.removePrefix("backgrounds/")
        }
        val snapshotUrl = if (assetKey.startsWith("backgrounds/")) {
            baseUrl + assetKey.removePrefix("backgrounds/")
        } else {
            baseUrl + assetKey
        }
        if (!strictSnapshot || webDavClient.head(snapshotUrl).code != 404) return snapshotUrl
        return if (assetKey.startsWith("backgrounds/")) {
            webDavClient.androidSettingsBackgroundsBaseUrl() + assetKey.removePrefix("backgrounds/")
        } else {
            webDavClient.backupBaseUrl() + assetKey
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
            if (localAsset.optString("sha256", "") != remoteAsset.optString("sha256", "")) return false
        }
        return true
    }

    private fun isAssetChanged(manifestAssets: JSONObject?, assetKey: String, localSize: Long): Boolean {
        if (manifestAssets == null) return true
        val asset = manifestAssets.optJSONObject(assetKey) ?: return true
        return asset.optLong("size", -1) != localSize
    }

    private fun isAssetChanged(manifestAssets: JSONObject?, assetKey: String, localFile: File): Boolean {
        if (manifestAssets == null) return true
        val asset = manifestAssets.optJSONObject(assetKey) ?: return true
        if (asset.optLong("size", -1L) != localFile.length()) return true
        val remoteSha = asset.optString("sha256", "")
        return remoteSha.isBlank() || !remoteSha.equals(computeFileSha256(localFile), ignoreCase = true)
    }

    private fun collectAssetManifest(assets: JSONObject, includeChapterText: Boolean, includeFiles: Boolean, includeBackgrounds: Boolean) {
        try {
            val tempDir = File(context.cacheDir, "backup")
            if (!tempDir.exists()) tempDir.mkdirs()
            if (includeChapterText || includeFiles) {
                val books = databaseHelper.getBooks()
                for (book in books) {
                    if (includeFiles) {
                        if (!book.coverPath.isNullOrBlank()) {
                            val coverFile = File(book.coverPath!!)
                            if (coverFile.exists()) {
                                val entry = JSONObject()
                                entry.put("size", coverFile.length())
                                entry.put("sha256", computeFileSha256(coverFile))
                                assets.put("covers/" + coverFile.name, entry)
                            }
                        }
                        if (!book.localPath.isNullOrBlank()) {
                            val sourceFile = File(book.localPath!!)
                            if (sourceFile.exists()) {
                                val entry = JSONObject()
                                entry.put("size", sourceFile.length())
                                entry.put("sha256", computeFileSha256(sourceFile))
                                assets.put("books/" + sourceFile.name, entry)
                            }
                        }
                    }
                    if (includeChapterText) {
                        val files = collectChapterTextFiles(book)
                        if (files.isNotEmpty()) {
                            val archiveName = "book_" + book.id + ".zip"
                            val archive = File(tempDir, archiveName)
                            writeChapterTextArchive(archive, files, 0, 1, object : StatusListener {
                                override fun onStatus(status: String) = Unit
                            })
                            val entry = JSONObject()
                            entry.put("size", archive.length())
                            entry.put("sha256", computeFileSha256(archive))
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
                        entry.put("sha256", computeFileSha256(bgFile))
                        assets.put("backgrounds/" + bgFile.name, entry)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun prepareFullSnapshotAssets(
        includeChapterText: Boolean,
        includeFiles: Boolean,
        listener: StatusListener,
    ): PreparedFullSnapshot {
        val assets: MutableList<PreparedAsset> = ArrayList()
        val warnings: MutableList<String> = ArrayList()
        val books = databaseHelper.getBooks()
        if (includeChapterText) assets.addAll(prepareChapterTextArchives(books, listener))
        if (includeFiles) {
            val optional = prepareOptionalBookAssets(books, true)
            assets.addAll(optional.first)
            warnings.addAll(optional.second)
        }
        return PreparedFullSnapshot(assets, warnings)
    }

    private fun prepareIncrementalAssets(
        includeChapterText: Boolean,
        includeFiles: Boolean,
        includeBackgrounds: Boolean,
        listener: StatusListener,
    ): PreparedFullSnapshot {
        val prepared = prepareFullSnapshotAssets(includeChapterText, includeFiles, listener)
        val assets = ArrayList(prepared.assets)
        val warnings = ArrayList(prepared.warnings)
        if (includeBackgrounds) {
            val path = settingsStore.readerBackgroundPath
            if (path.isNotBlank()) {
                val file = File(path)
                if (file.exists() && file.isFile) assets.add(PreparedAsset("backgrounds/${file.name}", file))
                else warnings.add("背景图片缺失")
            }
        }
        return PreparedFullSnapshot(assets, warnings)
    }

    @Throws(Exception::class)
    private fun prepareChapterTextArchives(
        books: List<BookRecord>,
        listener: StatusListener,
    ): List<PreparedAsset> {
        val result: MutableList<PreparedAsset> = ArrayList()
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IllegalStateException("无法创建备份缓存目录")
        for ((index, book) in books.withIndex()) {
            val expected = databaseHelper.getChaptersWithExternalStorage(book.id)
            if (expected.isEmpty()) continue
            listener.onStatus("检查章节正文 ${index + 1}/${books.size}")
            val files = collectChapterTextFiles(book)
            if (files.size != expected.size) {
                throw IllegalStateException("章节正文文件不完整：《${book.title}》\n预期${expected.size}章，实际${files.size}个文件")
            }
            for (file in files) {
                validateGzipFile(file.file, "章节正文损坏: ${book.title}")
            }
            listener.onStatus("打包章节正文 ${index + 1}/${books.size}")
            val archive = File(tempDir, chapterTextArchiveFileName(book.id))
            writeChapterTextArchive(archive, files, index + 1, books.size, listener)
            if (!archive.exists() || archive.length() <= 0L) throw IllegalStateException("章节正文包为空: ${book.title}")
            listener.onStatus("校验章节正文 ${index + 1}/${books.size}")
            result.add(PreparedAsset("chapter_text/${archive.name}", archive))
        }
        return result
    }

    private fun prepareOptionalBookAssets(
        books: List<BookRecord>,
        includeFiles: Boolean,
    ): Pair<List<PreparedAsset>, List<String>> {
        if (!includeFiles) return Pair(emptyList(), emptyList())
        val assets: MutableList<PreparedAsset> = ArrayList()
        val warnings: MutableList<String> = ArrayList()
        for (book in books) {
            if (!book.coverPath.isNullOrBlank()) {
                val file = File(book.coverPath!!)
                if (file.exists() && file.isFile) assets.add(PreparedAsset("covers/${file.name}", file))
                else warnings.add("封面缺失：《${book.title}》")
            }
            if (!book.localPath.isNullOrBlank()) {
                val file = File(book.localPath!!)
                if (file.exists() && file.isFile) assets.add(PreparedAsset("books/${file.name}", file))
                else warnings.add("源文件缺失：《${book.title}》")
            }
        }
        return Pair(assets, warnings)
    }

    @Throws(Exception::class)
    private fun buildPreparedAssetManifest(assets: List<PreparedAsset>): JSONObject {
        val manifest = JSONObject()
        for (asset in assets) manifestAppendAsset(manifest, asset.key, asset.file)
        return manifest
    }

    @Throws(Exception::class)
    private fun uploadPreparedAssets(
        assets: List<PreparedAsset>,
        remoteBaseUrl: String,
        listener: StatusListener,
    ) {
        for ((index, asset) in assets.withIndex()) {
            listener.onStatus("上传资源 ${index + 1}/${assets.size} · ${asset.key}")
            webDavClient.uploadFile(asset.file, remoteBaseUrl + asset.key)
        }
    }

    @Throws(Exception::class)
    private fun uploadPreparedChangedAssets(
        assets: List<PreparedAsset>,
        remoteAssets: JSONObject?,
        listener: StatusListener,
    ) {
        for ((index, asset) in assets.withIndex()) {
            if (!isAssetChanged(remoteAssets, asset.key, asset.file)) continue
            listener.onStatus("上传变化资源 ${index + 1}/${assets.size} · ${asset.key}")
            val remoteUrl = if (asset.key.startsWith("backgrounds/")) {
                webDavClient.androidSettingsBackgroundsBaseUrl() + asset.file.name
            } else {
                webDavClient.backupBaseUrl() + asset.key
            }
            webDavClient.uploadFile(asset.file, remoteUrl)
        }
    }

    @Throws(Exception::class)
    private fun uploadBackgroundIfPresent(listener: StatusListener) {
        val path = settingsStore.readerBackgroundPath
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists() || !file.isFile) return
        listener.onStatus("上传背景图片...")
        webDavClient.uploadFile(file, webDavClient.androidSettingsBackgroundsBaseUrl() + file.name)
    }

    @Throws(Exception::class)
    private fun uploadLegacySnapshotMirror(
        dataDir: File,
        assets: List<PreparedAsset>,
        manifestText: String,
        includeChapterText: Boolean,
        includeFiles: Boolean,
        listener: StatusListener,
    ) {
        val root = webDavClient.backupBaseUrl()
        for (fileName in SYNC_JSON_FILES) {
            val file = localJsonFile(dataDir, fileName)
            for (directory in arrayOf("database/", "sync/")) {
                webDavClient.uploadFile(file, root + directory + canonicalJsonFileName(fileName))
                uploadReadingStatsLegacyCopyIfNeeded(file, root + directory, fileName)
            }
        }
        uploadPreparedAssets(assets, root, listener)
        webDavClient.uploadText(root + "database/" + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
        webDavClient.uploadText(root + "sync/" + MANIFEST_FILE, manifestText, "application/json; charset=utf-8")
    }

    @Throws(Exception::class)
    private fun collectFullAssetManifest(includeChapterText: Boolean, includeFiles: Boolean): JSONObject {
        val assets = JSONObject()
        val books = databaseHelper.getBooks()
        val backupDir = File(context.cacheDir, "backup")
        for (book in books) {
            if (includeChapterText) {
                val expected = databaseHelper.getChaptersWithExternalStorage(book.id)
                if (expected.isNotEmpty()) {
                    val archive = File(backupDir, chapterTextArchiveFileName(book.id))
                    if (!archive.exists()) throw IllegalStateException("章节正文包未生成: ${book.title}")
                    manifestAppendAsset(assets, "chapter_text/" + archive.name, archive)
                }
            }
            if (includeFiles) {
                if (!book.coverPath.isNullOrBlank()) {
                    val cover = File(book.coverPath!!)
                    if (!cover.exists() || !cover.isFile) throw IllegalStateException("封面文件缺失: ${book.title}")
                    manifestAppendAsset(assets, "covers/" + cover.name, cover)
                }
                if (!book.localPath.isNullOrBlank()) {
                    val source = File(book.localPath!!)
                    if (!source.exists() || !source.isFile) throw IllegalStateException("书籍源文件缺失: ${book.title}")
                    manifestAppendAsset(assets, "books/" + source.name, source)
                }
            }
        }
        return assets
    }

    @Throws(Exception::class)
    private fun manifestAppendAsset(assets: JSONObject, key: String, file: File) {
        val sha256 = computeFileSha256(file)
        if (sha256.isBlank()) throw IllegalStateException("计算资源 SHA-256 失败: $key")
        val entry = JSONObject()
        entry.put("size", file.length())
        entry.put("sha256", sha256)
        assets.put(key, entry)
    }

    @Throws(Exception::class)
    private fun uploadChapterTextArchives(books: List<BookRecord>, listener: StatusListener) {
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IllegalStateException("无法创建备份缓存目录")
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            listener.onProgress(i + 1, total)
            val files = collectChapterTextFiles(book)
            val expectedCount = databaseHelper.getChaptersWithExternalStorage(book.id).size
            if (expectedCount == 0) continue
            if (files.size != expectedCount) throw IllegalStateException("章节正文文件不完整: ${book.title}")
            for (file in files) validateGzipFile(file.file, "章节正文损坏: ${book.title}")
            val archive = File(tempDir, chapterTextArchiveFileName(book.id))
            writeChapterTextArchive(archive, files, i + 1, total, listener)
            val remotePath = chapterTextArchiveRemotePath(webDavClient.backupBaseUrl(), book.id)
            listener.onStatus("上传章节正文包 " + (i + 1) + "/" + total + " · " + formatFileSize(archive.length()) + "...")
            webDavClient.uploadFile(archive, remotePath)
        }
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
        val total = Math.max(books.size, 1)
        for ((index, book) in books.withIndex()) {
            listener.onProgress(index + 1, total)
            val files = collectChapterTextFiles(book)
            if (files.isEmpty()) continue
            val archiveName = "book_" + book.id + ".zip"
            val assetKey = "chapter_text/" + archiveName
            val archive = File(tempDir, archiveName)
            writeChapterTextArchive(archive, files, 0, books.size, listener)
            if (!isAssetChanged(remoteAssets, assetKey, archive)) continue
            listener.onStatus("上传章节正文包...")
            webDavClient.uploadFile(archive, chapterTextArchiveRemotePath(webDavClient.backupBaseUrl(), book.id))
        }
    }

    @Throws(Exception::class)
    private fun uploadCoverFiles(books: List<BookRecord>, listener: StatusListener) {
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            listener.onProgress(i + 1, total)
            if (book.coverPath.isNullOrBlank()) continue
            val coverFile = File(book.coverPath!!)
            if (!coverFile.exists() || !coverFile.isFile) throw IllegalStateException("封面文件缺失: ${book.title}")
            val remotePath = webDavClient.backupBaseUrl() + "covers/" + coverFile.name
            listener.onStatus("上传封面 " + (i + 1) + "/" + total + " · " + formatFileSize(coverFile.length()) + "...")
            webDavClient.uploadFile(coverFile, remotePath)
        }
    }

    @Throws(Exception::class)
    private fun uploadChangedCoverFiles(books: List<BookRecord>, listener: StatusListener, remoteAssets: JSONObject?, localAssets: JSONObject?) {
        val total = Math.max(books.size, 1)
        for ((index, book) in books.withIndex()) {
            listener.onProgress(index + 1, total)
            if (book.coverPath.isNullOrBlank()) continue
            val coverFile = File(book.coverPath!!)
            if (!coverFile.exists()) continue
            val assetKey = "covers/" + coverFile.name
            if (isAssetChanged(remoteAssets, assetKey, coverFile)) {
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
            listener.onProgress(i + 1, total)
            if (book.localPath.isNullOrBlank()) continue
            val localFile = File(book.localPath!!)
            if (!localFile.exists() || !localFile.isFile) throw IllegalStateException("书籍源文件缺失: ${book.title}")
            val remotePath = webDavClient.backupBaseUrl() + "books/" + localFile.name
            listener.onStatus("上传书籍源文件 " + (i + 1) + "/" + total + " · " + formatFileSize(localFile.length()) + "...")
            webDavClient.uploadFile(localFile, remotePath)
        }
    }

    @Throws(Exception::class)
    private fun uploadChangedSourceFiles(books: List<BookRecord>, listener: StatusListener, remoteAssets: JSONObject?, localAssets: JSONObject?) {
        val total = Math.max(books.size, 1)
        for ((index, book) in books.withIndex()) {
            listener.onProgress(index + 1, total)
            if (book.localPath.isNullOrBlank()) continue
            val localFile = File(book.localPath!!)
            if (!localFile.exists()) continue
            val assetKey = "books/" + localFile.name
            if (isAssetChanged(remoteAssets, assetKey, localFile)) {
                listener.onStatus("上传源文件...")
                webDavClient.uploadFile(localFile, webDavClient.backupBaseUrl() + "books/" + localFile.name)
            }
        }
    }

    private fun restoreChapterTextFiles(
        listener: StatusListener,
        assets: JSONObject? = null,
        strict: Boolean = false,
        remoteBaseUrl: String = webDavClient.backupBaseUrl(),
    ) {
        val books = databaseHelper.getBooks()
        val total = Math.max(books.size, 1)
        for (i in books.indices) {
            val book = books[i]
            listener.onProgress(i + 1, total)
            if (restoreBookChapterTextArchiveIfPresent(book, i + 1, total, listener, assets, strict, remoteBaseUrl)) continue
            if (strict && databaseHelper.getChaptersWithExternalStorage(book.id).isNotEmpty()) {
                throw IllegalStateException("章节正文包恢复失败: ${book.title}")
            }
            restoreChapterTextFilesForBook(book, i + 1, total, listener, remoteBaseUrl)
        }
    }

    private fun restoreBookChapterTextArchiveIfPresent(
        book: BookRecord,
        bookIndex: Int,
        totalBooks: Int,
        listener: StatusListener,
        assets: JSONObject? = null,
        strict: Boolean = false,
        remoteBaseUrl: String = webDavClient.backupBaseUrl(),
    ): Boolean {
        val remotePath = chapterTextArchiveRemotePath(remoteBaseUrl, book.id)
        val expected = assets?.optJSONObject("chapter_text/" + chapterTextArchiveFileName(book.id))
        return try {
            if (strict && expected == null) throw IllegalStateException("正文资源清单缺少: ${book.title}")
            if (webDavClient.head(remotePath).code != 200) {
                if (strict) throw IllegalStateException("云端正文包缺失: ${book.title}")
                return false
            }
            val tempDir = File(context.cacheDir, "backup_restore")
            if (!tempDir.exists() && !tempDir.mkdirs()) return false
            val archive = File(tempDir, chapterTextArchiveFileName(book.id))
            listener.onStatus("下载章节正文包 " + bookIndex + "/" + totalBooks + "...")
            webDavClient.downloadBinaryFile(remotePath, archive)
            if (expected != null) validateFileAgainstManifest(archive, expected, "章节正文包 ${book.title}")
            listener.onStatus("解包章节正文 " + bookIndex + "/" + totalBooks + "...")
            val restored = extractChapterTextArchive(archive, book)
            if (!validateBookChapterTextFiles(book)) throw IllegalStateException("章节正文文件验收失败: ${book.title}")
            listener.onStatus("章节正文包已恢复 " + bookIndex + "/" + totalBooks + " · " + restored + " 个文件")
            restored > 0
        } catch (error: Exception) {
            if (strict) throw error
            Log.w(TAG, "恢复章节正文包失败 book " + book.id + "，回退逐文件恢复", error)
            false
        }
    }

    private fun restoreChapterTextFilesForBook(
        book: BookRecord,
        bookIndex: Int,
        totalBooks: Int,
        listener: StatusListener,
        remoteBaseUrl: String = webDavClient.backupBaseUrl(),
    ) {
        val chapters = databaseHelper.getChaptersWithExternalStorage(book.id)
        for (chapter in chapters) {
            if (chapter.bodyTextPath.isNullOrBlank()) continue
            try {
                val remotePath = remoteBaseUrl + "chapter_text/" + chapter.bodyTextPath
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
            val remoteKey = ReadingStatsUtils.canonicalBookIdentity(remoteJson.optString("readingStatsKey", ""), remoteJson.optString("title", ""), remoteJson.optString("author", ""))
            val remoteTitle = remoteJson.optString("title", "")
            val remoteAuthor = remoteJson.optString("author", "")
            val remoteUpdatedAt = remoteJson.optLong("updatedAt", 0)
            val remoteBook = BookRecord.fromJson(remoteJson)
            remoteBook.readingStatsKey = remoteKey

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
                newChapter.bodyTextPath = remapChapterTextPath(newChapter.bodyTextPath, remoteBookId, bookId)
                newChapter.id = nextRecordId(localChapters, newChapter.id)
                localChapters.add(newChapter)
                localByKey[key] = newChapter
            } else {
                localMatch.title = remoteJson.optString("title", "")
                val rawStorage = if (remoteJson.has("bodyTextStorage") && !remoteJson.isNull("bodyTextStorage")) {
                    remoteJson.optString("bodyTextStorage", "")
                } else {
                    ""
                }
                val remotePath = remoteJson.optString("bodyTextPath", "")
                val remoteStorage = normalizeChapterStorage(rawStorage, remotePath)
                val remoteSize = remoteJson.optLong("bodyTextSize", 0)
                if (isFileGzipChapterStorage(remoteStorage, remotePath)) {
                    localMatch.bodyTextStorage = remoteStorage
                    localMatch.bodyTextPath = remapChapterTextPath(remotePath, remoteBookId, bookId)
                    localMatch.bodyTextSize = remoteSize
                }
            }
        }
    }

    private fun remapChapterTextPath(path: String?, remoteBookId: Long, localBookId: Long): String? {
        return remapChapterTextPathForBook(path, remoteBookId, localBookId)
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

    private fun mergeBookmarks(remoteBookmarks: JSONArray, restoredBookIdMap: Map<Long, Long>) =
        mergeBookmarks(remoteBookmarks, restoredBookIdMap, false)

    private fun mergeBookmarks(remoteBookmarks: JSONArray, restoredBookIdMap: Map<Long, Long>, forceRemote: Boolean) {
        val localBookmarks = databaseHelper.getBookmarksMutable()
        val localByUuid: MutableMap<String, BookmarkRecord> = HashMap()
        for (bookmark in localBookmarks) bookmark.uuid?.let { localByUuid[it] = bookmark }

        for (i in 0 until remoteBookmarks.length()) {
            val remoteJson = remoteBookmarks.optJSONObject(i) ?: continue
            val remoteBookmark = BookmarkRecord.fromJson(remoteJson)
            if (remoteBookmark.bookId > 0L) {
                remoteBookmark.bookId = restoredBookIdMap[remoteBookmark.bookId] ?: remoteBookmark.bookId
            }
            remoteBookmark.bookIdentity = ReadingStatsUtils.canonicalBookIdentity(
                remoteBookmark.bookIdentity, remoteBookmark.bookTitle, remoteBookmark.bookAuthor,
            )
            val bookmarkBook = databaseHelper.getBooksMutable().firstOrNull { it.id == remoteBookmark.bookId }
            if (bookmarkBook != null && remoteBookmark.bookIdentity == ReadingStatsUtils.buildLegacyAndroidBookIdentity(bookmarkBook.title, bookmarkBook.author)) {
                remoteBookmark.bookIdentity = bookmarkBook.readingStatsKey
            }
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
        val candidates = if (READING_STATS_CANONICAL_FILE == fileName) {
            listOf(READING_STATS_CANONICAL_FILE, READING_STATS_LEGACY_FILE)
        } else {
            listOf(fileName)
        }
        for (candidate in candidates) {
            val url = baseUrl + candidate
            if (webDavClient.head(url).code == 200) {
                webDavClient.downloadBinaryFile(url, target)
                return candidate
            }
        }
        if (baseUrl.contains("/sync-snapshots/")) {
            val legacyBase = webDavClient.syncBaseUrl()
            for (candidate in candidates) {
                val url = legacyBase + candidate
                if (webDavClient.head(url).code == 200) {
                    webDavClient.downloadBinaryFile(url, target)
                    return candidate
                }
            }
        }
        throw IllegalStateException("云端文件不存在: $fileName")
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
        val array = if (READING_STATS_CANONICAL_FILE == canonicalJsonFileName(fileName) && trimmed.startsWith("{")) {
            val rows = JSONObject(trimmed).optJSONArray("rows")
                ?: throw IllegalStateException("$fileName 缺少 rows 数组")
            rows
        } else {
            JSONArray(trimmed)
        }
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i) == null) throw IllegalStateException("$fileName 第 ${i + 1} 项不是对象")
        }
        return array
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

    @Throws(Exception::class)
    private fun uploadSnapshotCommit(
        url: String,
        generationId: String,
        snapshotPath: String,
        manifestText: String,
    ) {
        if (generationId.isBlank() || snapshotPath.isBlank()) throw IllegalStateException("完整快照缺少定位信息")
        val commit = buildSnapshotCommit(generationId, snapshotPath, manifestText, System.currentTimeMillis())
        val tempDir = File(context.cacheDir, "backup")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IllegalStateException("无法创建备份缓存目录")
        val commitFile = File(tempDir, COMMIT_FILE)
        FileWriter(commitFile).use { writer -> writer.write(commit.toString(2)) }
        webDavClient.uploadFile(commitFile, url)
        commitFile.delete()
    }

    @Throws(Exception::class)
    private fun validateSnapshotCommit(databaseUrl: String, manifest: JSONObject, manifestText: String) {
        val generationId = manifest.optString("generationId", "")
        if (generationId.isBlank()) return
        val commitUrl = databaseUrl + COMMIT_FILE
        if (webDavClient.head(commitUrl).code != 200) throw IllegalStateException("完整快照尚未提交完成")
        val commit = JSONObject(webDavClient.downloadText(commitUrl))
        if (!snapshotCommitMatches(
                manifestText,
                generationId,
                commit.optString("generationId", ""),
                commit.optString("manifestSha256", ""),
            )) {
            throw IllegalStateException("完整快照 manifest 校验失败")
        }
    }

    private fun computeTextSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        val builder = StringBuilder()
        for (b in digest) builder.append(String.format(Locale.ROOT, "%02x", b))
        return builder.toString()
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
        val entry = getManifestFileEntry(filesEntry, fileName)
        if (entry == null) return null
        return if (entry.has("sha256")) entry.optString("sha256", "") else null
    }

    private fun getManifestFileEntry(filesEntry: JSONObject?, fileName: String): JSONObject? {
        if (filesEntry == null) return null
        val canonical = canonicalJsonFileName(fileName)
        var entry = filesEntry.optJSONObject(canonical)
        if (entry == null && READING_STATS_CANONICAL_FILE == canonical) {
            entry = filesEntry.optJSONObject(READING_STATS_LEGACY_FILE)
        }
        return entry
    }

    @Throws(Exception::class)
    private fun validateFileAgainstManifest(file: File, entry: JSONObject, label: String) {
        val expectedSize = entry.optLong("size", -1L)
        val expectedHash = entry.optString("sha256", "")
        if (expectedSize < 0 || expectedHash.isBlank()) throw IllegalStateException("$label 缺少完整校验信息")
        if (file.length() != expectedSize) throw IllegalStateException("$label 大小校验失败")
        if (computeFileSha256(file) != expectedHash) throw IllegalStateException("$label SHA-256 校验失败")
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

    private fun compareAssetKeys(remoteAssets: JSONObject?, localAssets: JSONObject?): Set<String> {
        return changedAssetKeys(remoteAssets, localAssets)
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
    private fun extractChapterTextArchive(
        archive: File,
        book: BookRecord? = null,
        remoteBookId: Long? = null,
        localBookId: Long? = null,
    ): Int {
        val baseDir = databaseHelper.resolveChapterTextFile("chapter_text/__base__")
            ?: throw IllegalStateException("无法定位章节正文目录")
        val realBase = baseDir.parentFile ?: throw IllegalStateException("无法定位章节正文目录")
        if (!realBase.exists() && !realBase.mkdirs()) throw IllegalStateException("无法创建章节正文目录")
        val basePath = realBase.canonicalPath + File.separator
        val expectedPaths = if (book == null) null else databaseHelper.getChaptersWithExternalStorage(book.id)
            .mapNotNull { sanitizeChapterTextArchiveEntryName(it.bodyTextPath) }
            .toSet()
        if (expectedPaths != null) {
            val archivePaths: MutableSet<String> = HashSet()
            ZipInputStream(FileInputStream(archive)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val entryName = sanitizeChapterTextArchiveEntryName(entry.name)
                        val mapped = if (entryName != null && remoteBookId != null && localBookId != null) {
                            remapChapterTextPath(entryName, remoteBookId, localBookId)
                        } else {
                            entryName
                        }
                        mapped?.let(archivePaths::add)
                    }
                    input.closeEntry()
                }
            }
            val missing = expectedPaths.filterNot(archivePaths::contains)
            if (missing.isNotEmpty()) throw IllegalStateException("章节正文 ZIP 缺少 ${missing.size} 个预期文件")
        }
        val buffer = ByteArray(8192)
        var restored = 0
        ZipInputStream(FileInputStream(archive)).use { zipInputStream ->
            while (true) {
                val entry = zipInputStream.nextEntry ?: break
                if (entry.isDirectory) {
                    zipInputStream.closeEntry()
                    continue
                }
                val rawEntryName = sanitizeChapterTextArchiveEntryName(entry.name)
                val entryName = if (rawEntryName != null && remoteBookId != null && localBookId != null) {
                    remapChapterTextPath(rawEntryName, remoteBookId, localBookId)
                } else {
                    rawEntryName
                }
                if (entryName == null) {
                    zipInputStream.closeEntry()
                    continue
                }
                if (expectedPaths != null && !expectedPaths.contains(entryName)) {
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

    @Throws(Exception::class)
    private fun validateGzipFile(file: File, message: String) {
        try {
            GZIPInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) {
                    // 完整读取以触发 gzip CRC 与尾部校验。
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException(message, error)
        }
    }

    private fun validateBookChapterTextFiles(book: BookRecord): Boolean {
        return try {
            val chapters = databaseHelper.getChaptersWithExternalStorage(book.id)
            for (chapter in chapters) {
                val entryName = sanitizeChapterTextArchiveEntryName(chapter.bodyTextPath) ?: return false
                val file = databaseHelper.resolveChapterTextFile(entryName) ?: return false
                if (!file.exists() || !file.isFile) return false
                validateGzipFile(file, "章节正文损坏: ${book.title}")
            }
            true
        } catch (_: Exception) {
            false
        }
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

    private fun chapterTextArchiveRemotePath(remoteBaseUrl: String, bookId: Long): String =
        remoteBaseUrl + "chapter_text/" + chapterTextArchiveFileName(bookId)

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
        return canonicalChapterTextArchiveEntryName(value)
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

    private data class PreparedAsset(
        val key: String,
        val file: File,
    )

    private data class PreparedFullSnapshot(
        val assets: List<PreparedAsset>,
        val warnings: List<String>,
    )

    private data class ResolvedRemoteSnapshot(
        val manifest: JSONObject,
        val manifestText: String,
        val dataBaseUrl: String,
        val assetBaseUrl: String,
        val strict: Boolean,
        val generationId: String?,
        val snapshotPrefix: String?,
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
        private const val COMMIT_FILE = "commit.json"

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

        @JvmStatic
        fun snapshotCommitMatches(
            manifestText: String,
            generationId: String,
            commitGenerationId: String,
            expectedHash: String,
        ): Boolean {
            if (generationId.isBlank() || generationId != commitGenerationId) return false
            if (expectedHash.isBlank()) return false
            val digest = MessageDigest.getInstance("SHA-256").digest(manifestText.toByteArray(StandardCharsets.UTF_8))
            val builder = StringBuilder()
            for (b in digest) builder.append(String.format(Locale.ROOT, "%02x", b))
            return expectedHash.equals(builder.toString(), ignoreCase = true)
        }

        @JvmStatic
        fun canonicalChapterTextArchiveEntryName(value: String?): String? {
            if (value.isNullOrBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")) return null
            val normalized = if (value.startsWith("chapter_text/")) value.substring("chapter_text/".length) else value
            for (segment in normalized.split("/")) {
                if (segment.isBlank() || "." == segment || ".." == segment) return null
            }
            return normalized
        }
    }
}



