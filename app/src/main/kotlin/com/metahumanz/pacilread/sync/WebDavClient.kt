package com.metahumanz.pacilread.sync

import android.util.Base64
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.storage.SettingsStore
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.max

open class WebDavClient(private val settingsStore: SettingsStore) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(Exception::class)
    fun probe(): Response {
        val base = requireConfiguredServerUrl()
        val response = requestText(base, "PROPFIND", null, "0")
        requireSuccessfulResponse(response, "连接服务器", false)
        ensureProgressDirectory()
        return response
    }

    @Throws(Exception::class)
    fun ensureProgressDirectory() {
        val serverUrl = requireConfiguredServerUrl()
        var lastError: Exception? = null
        var ensuredAny = false
        for (location in progressLocations()) {
            try {
                ensureDirectoryTree(serverUrl, location.parentDirectory, "初始化进度父目录")
                requireSuccessfulResponse(
                    requestText("${location.baseUrl}bookProgress/", "MKCOL", null, null),
                    "初始化进度目录",
                    true,
                )
                ensuredAny = true
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (!ensuredAny && lastError != null) throw lastError
    }

    @Throws(Exception::class)
    fun ensureBackupDirectories() {
        ensureBackupRootDirectory()
        ensureBookAssetDirectories()
        ensureAndroidSettingsDirectory()
    }

    @Throws(Exception::class)
    fun ensureBackupRootDirectory() {
        ensureDirectoryTree(requireConfiguredServerUrl(), settingsStore.webDavDir, "初始化备份目录")
    }

    @Throws(Exception::class)
    fun ensureBookAssetDirectories() {
        val base = backupBaseUrl()
        requireSuccessfulResponse(requestText("${base}books/", "MKCOL", null, null), "初始化书籍备份目录", true)
        requireSuccessfulResponse(requestText("${base}covers/", "MKCOL", null, null), "初始化封面备份目录", true)
    }

    @Throws(Exception::class)
    fun ensureChapterTextDirectory() {
        val base = backupBaseUrl()
        requireSuccessfulResponse(requestText("${base}chapter_text/", "MKCOL", null, null), "初始化章节正文备份目录", true)
    }

    @Throws(Exception::class)
    fun ensureReadingStatsDirectory() {
        ensureDirectoryTree(requireConfiguredServerUrl(), settingsStore.webDavDir, "初始化备份目录")
        requireSuccessfulResponse(requestText(readingStatsBaseUrl(), "MKCOL", null, null), "初始化阅读统计目录", true)
    }

    fun backupBaseUrl(): String = backupRootBaseUrl()

    fun syncBaseUrl(): String = "${backupBaseUrl()}sync/"

    fun databaseBaseUrl(): String = "${backupBaseUrl()}database/"

    @Throws(Exception::class)
    fun ensureDirectory(directoryUrl: String) {
        requireSuccessfulResponse(requestText(directoryUrl, "MKCOL", null, null), "创建目录", true)
    }

    fun backupRootBaseUrl(): String = appendDirectory(requireConfiguredServerUrl(), settingsStore.webDavDir)

    fun readingStatsBaseUrl(): String = "${backupBaseUrl()}readingStats/"

    fun androidSettingsBaseUrl(): String = appendDirectory(backupRootBaseUrl(), settingsStore.webDavSettingsSubdir)

    fun androidSettingsBackgroundsBaseUrl(): String = "${androidSettingsBaseUrl()}backgrounds/"

    fun androidSettingsSnapshotUrl(): String = "${androidSettingsBaseUrl()}android-settings.json"

    fun settingsSnapshotUrl(): String = androidSettingsSnapshotUrl()

    @Throws(Exception::class)
    fun downloadProgress(book: BookRecord): ProgressPayload? {
        var latestPayload: ProgressPayload? = null
        var lastError: Exception? = null
        for (target in progressDownloadFileTargets(book)) {
            try {
                val response = requestText(target.url, "GET", null, null)
                if (response.code == 404) continue
                requireSuccessfulResponse(response, "下载阅读进度", false)
                val body = response.body
                if (body.isNullOrBlank()) continue
                val jsonObject = JSONObject(body)
                val payload = ProgressPayload().apply {
                    author = jsonObject.optString("author", "")
                    name = jsonObject.optString("name", "")
                    chapterIndex = jsonObject.optInt("durChapterIndex", 0)
                    chapterPosition = jsonObject.optInt("durChapterPos", 0)
                    chapterTime = jsonObject.optLong("durChapterTime", 0L)
                    chapterTitle = jsonObject.optString("durChapterTitle", "")
                }
                if (latestPayload == null || payload.chapterTime > latestPayload.chapterTime) latestPayload = payload
            } catch (error: Exception) {
                if (target.required) lastError = error
            }
        }
        if (latestPayload != null) return latestPayload
        if (lastError != null) throw lastError
        return null
    }

    @Throws(Exception::class)
    fun uploadProgress(book: BookRecord, chapter: ChapterRecord, charPosition: Int) {
        if (!settingsStore.isWebDavEnabled) return
        val payload = JSONObject().apply {
            put("author", if (book.author.isNullOrBlank()) "未知" else book.author)
            put("durChapterIndex", chapter.orderIndex)
            put("durChapterPos", max(0, charPosition))
            put("durChapterTime", System.currentTimeMillis())
            put("durChapterTitle", chapter.title)
            put("name", book.title)
        }
        val body = payload.toString(2)
        var lastError: Exception? = null
        var uploadedAny = false
        for (url in progressUploadFileUrls(book)) {
            try {
                requireSuccessfulResponse(requestText(url, "PUT", body, null), "上传阅读进度", false)
                uploadedAny = true
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (!uploadedAny && lastError != null) throw lastError
    }

    private fun safeProgressFileName(book: BookRecord): String {
        val safeTitle = sanitize(book.title ?: "Unknown")
        val safeAuthor = sanitize(if (book.author.isNullOrBlank()) "未知" else book.author!!)
        return "${safeTitle}_${safeAuthor}.json"
    }

    private fun progressUploadFileUrls(book: BookRecord): List<String> {
        val fileName = encodePathSegment(safeProgressFileName(book))
        val urls = ArrayList<String>()
        for (location in progressLocations()) addUnique(urls, "${location.baseUrl}bookProgress/$fileName")
        return urls
    }

    private fun progressDownloadFileTargets(book: BookRecord): List<ProgressFileTarget> {
        val fileName = encodePathSegment(safeProgressFileName(book))
        val targets = ArrayList<ProgressFileTarget>()
        for (url in progressUploadFileUrls(book)) addUniqueTarget(targets, url, true)
        if (shouldReadLegacyRootProgressLocation()) {
            addUniqueTarget(targets, "${requireConfiguredServerUrl()}bookProgress/$fileName", false)
        }
        return targets
    }

    private fun shouldReadLegacyRootProgressLocation(): Boolean =
        requireConfiguredProgressBaseUrl() == requireConfiguredServerUrl()

    private fun progressLocations(): List<ProgressLocation> {
        val locations = ArrayList<ProgressLocation>()
        val progressBase = requireConfiguredProgressBaseUrl()
        val serverUrl = requireConfiguredServerUrl()
        if (progressBase != serverUrl) addProgressLocation(locations, progressBase, settingsStore.webDavProgressDir)
        addProgressLocation(locations, backupRootBaseUrl(), settingsStore.webDavDir)
        return locations
    }

    private fun addProgressLocation(locations: MutableList<ProgressLocation>, baseUrl: String?, parentDirectory: String?) {
        if (baseUrl.isNullOrBlank()) return
        for (location in locations) if (location.baseUrl == baseUrl) return
        locations.add(ProgressLocation(baseUrl, parentDirectory))
    }

    private fun addUnique(values: MutableList<String>, value: String?) {
        if (!value.isNullOrBlank() && !values.contains(value)) values.add(value)
    }

    private fun addUniqueTarget(targets: MutableList<ProgressFileTarget>, url: String?, required: Boolean) {
        if (url.isNullOrBlank()) return
        for (target in targets) if (target.url == url) return
        targets.add(ProgressFileTarget(url, required))
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun sanitize(input: String): String = input.replace(Regex("[\\\\/:\"*?<>|]"), "_")

    @Throws(Exception::class)
    fun head(url: String): Response = requestText(url, "HEAD", null, null)

    @Throws(Exception::class)
    fun delete(remoteUrl: String) {
        requireSuccessfulResponse(requestText(remoteUrl, "DELETE", null, null), "删除云端文件", true)
    }

    @Throws(Exception::class)
    fun listFiles(remoteDirectoryUrl: String): List<String> {
        val response = requestText(remoteDirectoryUrl, "PROPFIND", null, "1")
        if (response.code == 404) return ArrayList()
        requireSuccessfulResponse(response, "列出云端目录", false)
        val results = ArrayList<String>()
        val matcher = HREF_PATTERN.matcher(response.body.orEmpty())
        while (matcher.find()) {
            val rawHref = decodeXmlEntities(matcher.group(1))
            if (rawHref.isNullOrBlank()) continue
            try {
                val absoluteUrl = URL(URL(remoteDirectoryUrl), rawHref).toString()
                if (sameDirectory(remoteDirectoryUrl, absoluteUrl)) continue
                if (absoluteUrl.startsWith(remoteDirectoryUrl)) {
                    results.add(absoluteUrl)
                    continue
                }
                val decodedAbsolute = URLDecoder.decode(absoluteUrl, StandardCharsets.UTF_8.name())
                if (decodedAbsolute.startsWith(remoteDirectoryUrl)) results.add(decodedAbsolute)
            } catch (_: Exception) {
            }
        }
        return results
    }

    @Throws(Exception::class)
    fun uploadText(remoteUrl: String, content: String, contentType: String) {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = contentType
        val response = requestBytesAsText(remoteUrl, "PUT", content.toByteArray(StandardCharsets.UTF_8), null, headers)
        requireSuccessfulResponse(response, "上传文本", false)
    }

    @Throws(Exception::class)
    fun downloadText(remoteUrl: String): String {
        val response = requestText(remoteUrl, "GET", null, null)
        if (response.code in 200..299) return response.body.orEmpty()
        throw IllegalStateException("HTTP ${response.code}")
    }

    @Throws(Exception::class)
    fun uploadFile(localFile: File, remoteUrl: String) {
        retryNetwork(RetryAction {
            val requestBody = localFile.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader())
                .put(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                requireSuccessfulResponse(Response(response.code, ""), "上传文件", false)
            }
        }, "上传文件")
    }

    @Throws(Exception::class)
    fun remoteContentLength(remoteUrl: String): Long {
        val request = Request.Builder()
            .url(remoteUrl)
            .header("Authorization", authorizationHeader())
            .head()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return -1L
            requireSuccessfulResponse(Response(response.code, ""), "检查云端文件", false)
            val value = response.header("Content-Length")
            if (value.isNullOrBlank()) return -1L
            return try {
                value.toLong()
            } catch (_: NumberFormatException) {
                -1L
            }
        }
    }

    @Throws(Exception::class)
    fun downloadFile(remoteUrl: String, destination: File) {
        val response = requestBinary(remoteUrl, "GET")
        if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
        ensureDestinationParent(destination)
        FileOutputStream(destination).use { it.write(response.bytes) }
    }

    @Throws(Exception::class)
    fun downloadBinaryFile(remoteUrl: String, destination: File) {
        retryNetwork(RetryAction {
            val response = requestBinary(remoteUrl, "GET")
            if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
            ensureDestinationParent(destination)
            FileOutputStream(destination).use { it.write(response.bytes) }
        }, "下载文件")
    }

    private fun ensureDestinationParent(destination: File) {
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建目录: ${destination.parent}")
        }
    }

    private fun retryNetwork(action: RetryAction, description: String) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                action.execute()
                return
            } catch (error: IOException) {
                lastError = error
                if (attempt < maxAttempts) Thread.sleep(1000L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("$description 失败")
    }

    private fun interface RetryAction {
        @Throws(Exception::class)
        fun execute()
    }

    private fun requestText(url: String, method: String, body: String?, depth: String?): Response =
        requestBytesAsText(url, method, body?.toByteArray(StandardCharsets.UTF_8), depth, null)

    private fun requestBytesAsText(
        url: String,
        method: String,
        body: ByteArray?,
        depth: String?,
        extraHeaders: Map<String, String>?,
    ): Response {
        val response = requestBytes(url, method, body, depth, extraHeaders)
        return Response(response.code, String(response.bytes, StandardCharsets.UTF_8))
    }

    private fun requestBinary(url: String, method: String): BinaryResponse = requestBytes(url, method, null, null, null)

    private fun requestBytes(
        url: String,
        method: String,
        body: ByteArray?,
        depth: String?,
        extraHeaders: Map<String, String>?,
    ): BinaryResponse {
        val builder = Request.Builder().url(url).header("Authorization", authorizationHeader())
        if (depth != null) builder.header("Depth", depth)
        var contentType: MediaType? = DEFAULT_BODY_TYPE
        if (extraHeaders != null) {
            for ((key, value) in extraHeaders) {
                if (key.equals("Content-Type", ignoreCase = true)) contentType = value.toMediaTypeOrNull()
                else builder.header(key, value)
            }
        }
        val requestBody = body?.toRequestBody(contentType)
        httpClient.newCall(builder.method(method, requestBody).build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return BinaryResponse(response.code, bytes)
        }
    }

    private fun authorizationHeader(): String {
        val raw = "${settingsStore.webDavUser}:${settingsStore.webDavPassword}"
        return "Basic ${Base64.encodeToString(raw.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)}"
    }

    @Throws(Exception::class)
    fun ensureAndroidSettingsDirectory() {
        ensureDirectoryTree(backupRootBaseUrl(), settingsStore.webDavSettingsSubdir, "初始化 Android 设置目录")
        requireSuccessfulResponse(
            requestText(androidSettingsBackgroundsBaseUrl(), "MKCOL", null, null),
            "初始化 Android 背景目录",
            true,
        )
    }

    private fun ensureDirectoryTree(parentUrl: String, directory: String?, action: String) {
        val normalizedParent = if (parentUrl.endsWith('/')) parentUrl else "$parentUrl/"
        var normalizedDirectory = directory?.trim().orEmpty()
        while (normalizedDirectory.startsWith('/')) normalizedDirectory = normalizedDirectory.substring(1)
        if (normalizedDirectory.isBlank()) return
        var currentUrl = normalizedParent
        for (segment in normalizedDirectory.split('/')) {
            if (segment.isBlank()) continue
            currentUrl += "$segment/"
            requireSuccessfulResponse(requestText(currentUrl, "MKCOL", null, null), action, true)
        }
    }

    private fun appendDirectory(baseUrl: String, directory: String?): String {
        val normalizedBase = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        var normalizedDirectory = directory?.trim().orEmpty()
        while (normalizedDirectory.startsWith('/')) normalizedDirectory = normalizedDirectory.substring(1)
        if (normalizedDirectory.isBlank()) return normalizedBase
        if (!normalizedDirectory.endsWith('/')) normalizedDirectory += "/"
        return normalizedBase + normalizedDirectory
    }

    private fun sameDirectory(directoryUrl: String, absoluteUrl: String): Boolean {
        val normalizedDirectory = if (directoryUrl.endsWith('/')) directoryUrl else "$directoryUrl/"
        val normalizedAbsolute = if (absoluteUrl.endsWith('/')) absoluteUrl else "$absoluteUrl/"
        return normalizedDirectory == normalizedAbsolute
    }

    private fun decodeXmlEntities(value: String?): String? = value
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&quot;", "\"")
        ?.replace("&#39;", "'")

    private fun requireConfiguredServerUrl(): String {
        val base = settingsStore.webDavUrl
        if (base.isNullOrBlank()) throw IllegalStateException("请先填写 WebDAV 服务器地址")
        return base
    }

    private fun requireConfiguredProgressBaseUrl(): String {
        val base = settingsStore.webDavProgressBaseUrl
        if (base.isNullOrBlank()) throw IllegalStateException("请先填写 WebDAV 服务器地址")
        return base
    }

    private fun requireSuccessfulResponse(response: Response?, action: String, allowExistingCollection: Boolean) {
        if (response == null) throw IllegalStateException("${action}失败")
        val code = response.code
        val success = code in 200..299
        val existingCollection = allowExistingCollection && (code == 405 || code == 301 || code == 302)
        if (success || existingCollection) return
        throw IllegalStateException("${action}失败: HTTP $code")
    }

    @Suppress("unused")
    private fun readFullyBytes(inputStream: InputStream): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            outputStream.write(buffer, 0, read)
        }
        return outputStream.toByteArray()
    }

    open class Response(@JvmField val code: Int, @JvmField val body: String?)

    open class ProgressPayload {
        @JvmField var author: String? = null
        @JvmField var name: String? = null
        @JvmField var chapterIndex = 0
        @JvmField var chapterPosition = 0
        @JvmField var chapterTime = 0L
        @JvmField var chapterTitle: String? = null
    }

    private class ProgressLocation(val baseUrl: String, val parentDirectory: String?)
    private class ProgressFileTarget(val url: String, val required: Boolean)
    private class BinaryResponse(val code: Int, val bytes: ByteArray)

    private companion object {
        val HREF_PATTERN: Pattern = Pattern.compile("(?i)<[^>]*href[^>]*>(.*?)</[^>]*href>")
        val DEFAULT_BODY_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
