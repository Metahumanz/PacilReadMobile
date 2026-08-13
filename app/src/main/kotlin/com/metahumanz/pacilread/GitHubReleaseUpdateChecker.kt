package com.metahumanz.pacilread

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tagName: String,
    val releasePageUrl: String,
)

class GitHubReleaseUpdateChecker {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Returns null when the repository has no published full release yet. */
    @Throws(IOException::class)
    fun latestRelease(): GitHubRelease? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PacilRead-Mobile")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub 返回 HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("GitHub 未返回发布信息")

            val release = JSONObject(body)
            val tagName = release.optString("tag_name").trim()
            val releasePageUrl = release.optString("html_url").trim()
            if (tagName.isBlank() || !releasePageUrl.startsWith(RELEASE_PAGE_URL_PREFIX)) {
                throw IOException("GitHub 发布信息不完整")
            }
            return GitHubRelease(tagName, releasePageUrl)
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Metahumanz/PacilReadMobile/releases/latest"
        private const val RELEASE_PAGE_URL_PREFIX = "https://github.com/Metahumanz/PacilReadMobile/releases/"
    }
}

object AppVersionComparator {
    /** Compares [candidateVersion] with [installedVersion], or returns null for an unsupported format. */
    fun compare(candidateVersion: String, installedVersion: String): Int? {
        val candidateParts = numericParts(candidateVersion) ?: return null
        val installedParts = numericParts(installedVersion) ?: return null
        val partCount = maxOf(candidateParts.size, installedParts.size)
        for (index in 0 until partCount) {
            val candidatePart = candidateParts.getOrElse(index) { 0L }
            val installedPart = installedParts.getOrElse(index) { 0L }
            if (candidatePart > installedPart) return 1
            if (candidatePart < installedPart) return -1
        }
        return 0
    }

    private fun numericParts(version: String): List<Long>? {
        val match = NUMERIC_VERSION_PATTERN.matchEntire(version.trim()) ?: return null
        val parts = ArrayList<Long>()
        for (part in match.groupValues[1].split('.')) {
            val value = part.toLongOrNull() ?: return null
            parts.add(value)
        }
        return parts
    }

    private val NUMERIC_VERSION_PATTERN = Regex("^[vV]?(\\d+(?:\\.\\d+)*)$")
}
