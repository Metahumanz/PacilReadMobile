package com.metahumanz.pacilread.sync;

import android.util.Base64;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.storage.SettingsStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class WebDavClient {
    private static final Pattern HREF_PATTERN = Pattern.compile("(?i)<[^>]*href[^>]*>(.*?)</[^>]*href>");
    private static final MediaType DEFAULT_BODY_TYPE = MediaType.get("application/json; charset=utf-8");
    private final SettingsStore settingsStore;
    private final OkHttpClient httpClient;

    public WebDavClient(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public Response probe() throws Exception {
        String base = requireConfiguredServerUrl();
        Response response = request(base, "PROPFIND", null, "0");
        requireSuccessfulResponse(response, "连接服务器", false);
        ensureProgressDirectory();
        return response;
    }

    public void ensureProgressDirectory() throws Exception {
        String serverUrl = requireConfiguredServerUrl();
        Exception lastError = null;
        boolean ensuredAny = false;
        for (ProgressLocation location : progressLocations()) {
            try {
                ensureDirectoryTree(serverUrl, location.parentDirectory, "初始化进度父目录");
                requireSuccessfulResponse(request(location.baseUrl + "bookProgress/", "MKCOL", null, null), "初始化进度目录", true);
                ensuredAny = true;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (!ensuredAny && lastError != null) {
            throw lastError;
        }
    }

    public void ensureBackupDirectories() throws Exception {
        ensureBackupRootDirectory();
        ensureBookAssetDirectories();
        ensureAndroidSettingsDirectory();
    }

    public void ensureBackupRootDirectory() throws Exception {
        ensureDirectoryTree(requireConfiguredServerUrl(), settingsStore.getWebDavDir(), "初始化备份目录");
    }

    public void ensureBookAssetDirectories() throws Exception {
        String base = backupBaseUrl();
        requireSuccessfulResponse(request(base + "books/", "MKCOL", null, null), "初始化书籍备份目录", true);
        requireSuccessfulResponse(request(base + "covers/", "MKCOL", null, null), "初始化封面备份目录", true);
    }

    public void ensureChapterTextDirectory() throws Exception {
        String base = backupBaseUrl();
        requireSuccessfulResponse(request(base + "chapter_text/", "MKCOL", null, null), "初始化章节正文备份目录", true);
    }

    public void ensureReadingStatsDirectory() throws Exception {
        String base = backupBaseUrl();
        ensureDirectoryTree(requireConfiguredServerUrl(), settingsStore.getWebDavDir(), "初始化备份目录");
        requireSuccessfulResponse(request(readingStatsBaseUrl(), "MKCOL", null, null), "初始化阅读统计目录", true);
    }

    public String backupBaseUrl() {
        return backupRootBaseUrl();
    }

    /** 增量同步目录 URL，用于 manifest + JSON 格式的增量备份 */
    public String syncBaseUrl() {
        return backupBaseUrl() + "sync/";
    }

    /** 全量快照目录 URL，用于 JSON 格式的全量备份 */
    public String databaseBaseUrl() {
        return backupBaseUrl() + "database/";
    }

    /** 创建任意远程目录（MKCOL），已存在也不报错 */
    public void ensureDirectory(String directoryUrl) throws Exception {
        requireSuccessfulResponse(request(directoryUrl, "MKCOL", null, null), "创建目录", true);
    }

    public String backupRootBaseUrl() {
        return appendDirectory(requireConfiguredServerUrl(), settingsStore.getWebDavDir());
    }

    public String readingStatsBaseUrl() {
        return backupBaseUrl() + "readingStats/";
    }

    public String androidSettingsBaseUrl() {
        return appendDirectory(backupRootBaseUrl(), settingsStore.getWebDavSettingsSubdir());
    }

    public String androidSettingsBackgroundsBaseUrl() {
        return androidSettingsBaseUrl() + "backgrounds/";
    }

    public String androidSettingsSnapshotUrl() {
        return androidSettingsBaseUrl() + "android-settings.json";
    }

    public String settingsSnapshotUrl() {
        return androidSettingsSnapshotUrl();
    }

    public ProgressPayload downloadProgress(BookRecord book) throws Exception {
        ProgressPayload latestPayload = null;
        Exception lastError = null;
        for (String url : progressFileUrls(book)) {
            try {
                Response response = request(url, "GET", null, null);
                if (response.code == 404) {
                    continue;
                }
                requireSuccessfulResponse(response, "下载阅读进度", false);
                if (response.body == null || response.body.isBlank()) {
                    continue;
                }
                JSONObject jsonObject = new JSONObject(response.body);
                ProgressPayload payload = new ProgressPayload();
                payload.author = jsonObject.optString("author", "");
                payload.name = jsonObject.optString("name", "");
                payload.chapterIndex = jsonObject.optInt("durChapterIndex", 0);
                payload.chapterPosition = jsonObject.optInt("durChapterPos", 0);
                payload.chapterTime = jsonObject.optLong("durChapterTime", 0L);
                payload.chapterTitle = jsonObject.optString("durChapterTitle", "");
                if (latestPayload == null || payload.chapterTime > latestPayload.chapterTime) {
                    latestPayload = payload;
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (latestPayload != null) {
            return latestPayload;
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    public void uploadProgress(BookRecord book, ChapterRecord chapter, int charPosition) throws Exception {
        if (!settingsStore.isWebDavEnabled()) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("author", book.author == null || book.author.isBlank() ? "未知" : book.author);
        payload.put("durChapterIndex", chapter.orderIndex);
        payload.put("durChapterPos", Math.max(0, charPosition));
        payload.put("durChapterTime", System.currentTimeMillis());
        payload.put("durChapterTitle", chapter.title);
        payload.put("name", book.title);
        String body = payload.toString(2);
        Exception lastError = null;
        boolean uploadedAny = false;
        for (String url : progressFileUrls(book)) {
            try {
                Response response = request(url, "PUT", body, null);
                requireSuccessfulResponse(response, "上传阅读进度", false);
                uploadedAny = true;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (!uploadedAny && lastError != null) {
            throw lastError;
        }
    }

    private String safeProgressFileName(BookRecord book) {
        String safeTitle = sanitize(book.title == null ? "Unknown" : book.title);
        String safeAuthor = sanitize(book.author == null || book.author.isBlank() ? "未知" : book.author);
        return safeTitle + "_" + safeAuthor + ".json";
    }

    private List<String> progressFileUrls(BookRecord book) throws Exception {
        String fileName = encodePathSegment(safeProgressFileName(book));
        List<String> urls = new ArrayList<>();
        for (ProgressLocation location : progressLocations()) {
            addUnique(urls, location.baseUrl + "bookProgress/" + fileName);
        }
        return urls;
    }

    private List<ProgressLocation> progressLocations() {
        List<ProgressLocation> locations = new ArrayList<>();
        addProgressLocation(locations, requireConfiguredProgressBaseUrl(), settingsStore.getWebDavProgressDir());
        addProgressLocation(locations, backupRootBaseUrl(), settingsStore.getWebDavDir());
        return locations;
    }

    private void addProgressLocation(List<ProgressLocation> locations, String baseUrl, String parentDirectory) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        for (ProgressLocation location : locations) {
            if (location.baseUrl.equals(baseUrl)) {
                return;
            }
        }
        locations.add(new ProgressLocation(baseUrl, parentDirectory));
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private String encodePathSegment(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private String sanitize(String input) {
        return input.replaceAll("[\\\\/:\"*?<>|]", "_");
    }

    public Response head(String url) throws Exception {
        return request(url, "HEAD", null, null);
    }

    public void delete(String remoteUrl) throws Exception {
        Response response = request(remoteUrl, "DELETE", null, null);
        requireSuccessfulResponse(response, "删除云端文件", true);
    }

    public List<String> listFiles(String remoteDirectoryUrl) throws Exception {
        Response response = request(remoteDirectoryUrl, "PROPFIND", null, "1");
        if (response.code == 404) {
            return new ArrayList<>();
        }
        requireSuccessfulResponse(response, "列出云端目录", false);
        List<String> results = new ArrayList<>();
        Matcher matcher = HREF_PATTERN.matcher(response.body == null ? "" : response.body);
        while (matcher.find()) {
            String rawHref = decodeXmlEntities(matcher.group(1));
            if (rawHref == null || rawHref.isBlank()) {
                continue;
            }
            try {
                String absoluteUrl = new URL(new URL(remoteDirectoryUrl), rawHref).toString();
                if (sameDirectory(remoteDirectoryUrl, absoluteUrl)) {
                    continue;
                }
                if (absoluteUrl.startsWith(remoteDirectoryUrl)) {
                    results.add(absoluteUrl);
                    continue;
                }
                String decodedAbsolute = URLDecoder.decode(absoluteUrl, StandardCharsets.UTF_8.name());
                if (decodedAbsolute.startsWith(remoteDirectoryUrl)) {
                    results.add(decodedAbsolute);
                }
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    public void uploadText(String remoteUrl, String content, String contentType) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        Response response = request(remoteUrl, "PUT", content.getBytes(StandardCharsets.UTF_8), null, headers);
        requireSuccessfulResponse(response, "上传文本", false);
    }

    public String downloadText(String remoteUrl) throws Exception {
        Response response = request(remoteUrl, "GET", null, null);
        if (response.code >= 200 && response.code < 300) {
            return response.body;
        }
        throw new IllegalStateException("HTTP " + response.code);
    }

    public void uploadFile(File localFile, String remoteUrl) throws Exception {
        retryNetwork(() -> {
            RequestBody requestBody = RequestBody.create(localFile, MediaType.get("application/octet-stream"));
            Request request = new Request.Builder()
                    .url(remoteUrl)
                    .header("Authorization", authorizationHeader())
                    .put(requestBody)
                    .build();
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                requireSuccessfulResponse(new Response(response.code(), ""), "上传文件", false);
            }
        }, "上传文件");
    }

    public long remoteContentLength(String remoteUrl) throws Exception {
        Request request = new Request.Builder()
                .url(remoteUrl)
                .header("Authorization", authorizationHeader())
                .head()
                .build();
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 404) {
                return -1L;
            }
            requireSuccessfulResponse(new Response(response.code(), ""), "检查云端文件", false);
            String value = response.header("Content-Length");
            if (value == null || value.isBlank()) {
                return -1L;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
    }

    public void downloadFile(String remoteUrl, File destination) throws Exception {
        BinaryResponse response = requestBinary(remoteUrl, "GET");
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException("HTTP " + response.code);
        }
        if (destination.getParentFile() != null && !destination.getParentFile().exists() && !destination.getParentFile().mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + destination.getParent());
        }
        try (FileOutputStream outputStream = new FileOutputStream(destination)) {
            outputStream.write(response.bytes);
        }
    }

    public void downloadBinaryFile(String remoteUrl, File destination) throws Exception {
        retryNetwork(() -> {
            BinaryResponse response = requestBinary(remoteUrl, "GET");
            if (response.code < 200 || response.code >= 300) {
                throw new IllegalStateException("HTTP " + response.code);
            }
            if (destination.getParentFile() != null && !destination.getParentFile().exists() && !destination.getParentFile().mkdirs()) {
                throw new IllegalStateException("无法创建目录: " + destination.getParent());
            }
            try (FileOutputStream outputStream = new FileOutputStream(destination)) {
                outputStream.write(response.bytes);
            }
        }, "下载文件");
    }

    /** 网络重试：仅对 IO 超时等瞬态错误重试，HTTP 4xx/5xx 不重试 */
    private void retryNetwork(RetryAction action, String description) throws Exception {
        int maxAttempts = 3;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.execute();
                return;
            } catch (java.io.IOException e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    Thread.sleep(1000L * attempt);
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException(description + " 失败");
    }

    private interface RetryAction {
        void execute() throws Exception;
    }

    private Response request(String url, String method, String body, String depth) throws Exception {
        return request(url, method, body == null ? null : body.getBytes(StandardCharsets.UTF_8), depth, null);
    }

    private Response request(String url, String method, byte[] body, String depth, Map<String, String> extraHeaders) throws Exception {
        BinaryResponse response = requestBytes(url, method, body, depth, extraHeaders);
        return new Response(response.code, new String(response.bytes, StandardCharsets.UTF_8));
    }

    private BinaryResponse requestBinary(String url, String method) throws Exception {
        return requestBytes(url, method, null, null, null);
    }

    private BinaryResponse requestBytes(String url, String method, byte[] body, String depth, Map<String, String> extraHeaders) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Authorization", authorizationHeader());
        if (depth != null) {
            builder.header("Depth", depth);
        }

        MediaType contentType = DEFAULT_BODY_TYPE;
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(entry.getKey())) {
                    contentType = MediaType.parse(entry.getValue());
                } else {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }

        RequestBody requestBody = body == null ? null : RequestBody.create(body, contentType);
        try (okhttp3.Response response = httpClient.newCall(builder.method(method, requestBody).build()).execute()) {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
            return new BinaryResponse(response.code(), bytes);
        }
    }

    private String authorizationHeader() {
        String raw = settingsStore.getWebDavUser() + ":" + settingsStore.getWebDavPassword();
        return "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public void ensureAndroidSettingsDirectory() throws Exception {
        ensureDirectoryTree(backupRootBaseUrl(), settingsStore.getWebDavSettingsSubdir(), "初始化 Android 设置目录");
        requireSuccessfulResponse(request(androidSettingsBackgroundsBaseUrl(), "MKCOL", null, null), "初始化 Android 背景目录", true);
    }

    private void ensureDirectoryTree(String parentUrl, String directory, String action) throws Exception {
        String normalizedParent = parentUrl.endsWith("/") ? parentUrl : parentUrl + "/";
        String normalizedDirectory = directory == null ? "" : directory.trim();
        while (normalizedDirectory.startsWith("/")) {
            normalizedDirectory = normalizedDirectory.substring(1);
        }
        if (normalizedDirectory.isBlank()) {
            return;
        }
        String currentUrl = normalizedParent;
        for (String segment : normalizedDirectory.split("/")) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            currentUrl += segment + "/";
            requireSuccessfulResponse(request(currentUrl, "MKCOL", null, null), action, true);
        }
    }

    private String appendDirectory(String baseUrl, String directory) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String normalizedDirectory = directory == null ? "" : directory.trim();
        while (normalizedDirectory.startsWith("/")) {
            normalizedDirectory = normalizedDirectory.substring(1);
        }
        if (normalizedDirectory.isBlank()) {
            return normalizedBase;
        }
        if (!normalizedDirectory.endsWith("/")) {
            normalizedDirectory += "/";
        }
        return normalizedBase + normalizedDirectory;
    }

    private boolean sameDirectory(String directoryUrl, String absoluteUrl) {
        String normalizedDirectory = directoryUrl.endsWith("/") ? directoryUrl : directoryUrl + "/";
        String normalizedAbsolute = absoluteUrl.endsWith("/") ? absoluteUrl : absoluteUrl + "/";
        return normalizedDirectory.equals(normalizedAbsolute);
    }

    private String decodeXmlEntities(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String requireConfiguredServerUrl() {
        String base = settingsStore.getWebDavUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("请先填写 WebDAV 服务器地址");
        }
        return base;
    }

    private String requireConfiguredProgressBaseUrl() {
        String base = settingsStore.getWebDavProgressBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("请先填写 WebDAV 服务器地址");
        }
        return base;
    }

    private void requireSuccessfulResponse(Response response, String action, boolean allowExistingCollection) {
        if (response == null) {
            throw new IllegalStateException(action + "失败");
        }
        int code = response.code;
        boolean success = code >= 200 && code < 300;
        boolean existingCollection = allowExistingCollection && (code == 405 || code == 301 || code == 302);
        if (success || existingCollection) {
            return;
        }
        throw new IllegalStateException(action + "失败: HTTP " + code);
    }

    private byte[] readFullyBytes(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    public static class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public static class ProgressPayload {
        public String author;
        public String name;
        public int chapterIndex;
        public int chapterPosition;
        public long chapterTime;
        public String chapterTitle;
    }

    private static class ProgressLocation {
        final String baseUrl;
        final String parentDirectory;

        ProgressLocation(String baseUrl, String parentDirectory) {
            this.baseUrl = baseUrl;
            this.parentDirectory = parentDirectory;
        }
    }

    private static class BinaryResponse {
        final int code;
        final byte[] bytes;

        BinaryResponse(int code, byte[] bytes) {
            this.code = code;
            this.bytes = bytes;
        }
    }
}
