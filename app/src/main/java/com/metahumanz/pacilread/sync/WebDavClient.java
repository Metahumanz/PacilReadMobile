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
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebDavClient {
    private static final Pattern HREF_PATTERN = Pattern.compile("(?i)<[^>]*href[^>]*>(.*?)</[^>]*href>");
    private final SettingsStore settingsStore;

    public WebDavClient(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    public Response probe() throws Exception {
        String base = requireConfiguredServerUrl();
        Response response = request(base, "PROPFIND", null, "0");
        requireSuccessfulResponse(response, "连接服务器", false);
        ensureProgressDirectory();
        return response;
    }

    public void ensureProgressDirectory() throws Exception {
        String base = requireConfiguredProgressBaseUrl();
        if (!settingsStore.getWebDavDir().isBlank()) {
            requireSuccessfulResponse(request(base, "MKCOL", null, null), "初始化同步目录", true);
        }
        requireSuccessfulResponse(request(base + "bookProgress/", "MKCOL", null, null), "初始化进度目录", true);
    }

    public void ensureBackupDirectories() throws Exception {
        String base = backupBaseUrl();
        requireSuccessfulResponse(request(base, "MKCOL", null, null), "初始化备份目录", true);
        requireSuccessfulResponse(request(base + "books/", "MKCOL", null, null), "初始化书籍备份目录", true);
        requireSuccessfulResponse(request(base + "covers/", "MKCOL", null, null), "初始化封面备份目录", true);
        requireSuccessfulResponse(request(base + "backgrounds/", "MKCOL", null, null), "初始化背景备份目录", true);
    }

    public void ensureReadingStatsDirectory() throws Exception {
        String base = backupBaseUrl();
        requireSuccessfulResponse(request(base, "MKCOL", null, null), "初始化备份目录", true);
        requireSuccessfulResponse(request(readingStatsBaseUrl(), "MKCOL", null, null), "初始化阅读统计目录", true);
    }

    public String backupBaseUrl() {
        String base = requireConfiguredProgressBaseUrl();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + "PacilRead/";
    }

    public String readingStatsBaseUrl() {
        return backupBaseUrl() + "readingStats/";
    }

    public ProgressPayload downloadProgress(BookRecord book) throws Exception {
        String fileName = safeProgressFileName(book);
        Response response = request(settingsStore.getWebDavProgressBaseUrl() + "bookProgress/" + fileName, "GET", null, null);
        if (response.code != 200 || response.body == null || response.body.isBlank()) {
            return null;
        }
        JSONObject jsonObject = new JSONObject(response.body);
        ProgressPayload payload = new ProgressPayload();
        payload.author = jsonObject.optString("author", "");
        payload.name = jsonObject.optString("name", "");
        payload.chapterIndex = jsonObject.optInt("durChapterIndex", 0);
        payload.chapterPosition = jsonObject.optInt("durChapterPos", 0);
        payload.chapterTime = jsonObject.optLong("durChapterTime", 0L);
        payload.chapterTitle = jsonObject.optString("durChapterTitle", "");
        return payload;
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
        request(
                settingsStore.getWebDavProgressBaseUrl() + "bookProgress/" + safeProgressFileName(book),
                "PUT",
                payload.toString(2),
                null
        );
    }

    private String safeProgressFileName(BookRecord book) {
        String safeTitle = sanitize(book.title == null ? "Unknown" : book.title);
        String safeAuthor = sanitize(book.author == null || book.author.isBlank() ? "未知" : book.author);
        return safeTitle + "_" + safeAuthor + ".json";
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
        request(remoteUrl, "PUT", content.getBytes(StandardCharsets.UTF_8), null, headers);
    }

    public String downloadText(String remoteUrl) throws Exception {
        Response response = request(remoteUrl, "GET", null, null);
        if (response.code >= 200 && response.code < 300) {
            return response.body;
        }
        throw new IllegalStateException("HTTP " + response.code);
    }

    public void uploadFile(File localFile, String remoteUrl) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(localFile)) {
            byte[] bytes = readFullyBytes(inputStream);
            request(remoteUrl, "PUT", bytes, null, null);
        }
    }

    public void downloadFile(String remoteUrl, File destination) throws Exception {
        Response response = request(remoteUrl, "GET", null, null);
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException("HTTP " + response.code);
        }
        if (destination.getParentFile() != null && !destination.getParentFile().exists() && !destination.getParentFile().mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + destination.getParent());
        }
        try (FileOutputStream outputStream = new FileOutputStream(destination)) {
            outputStream.write(response.body.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    public void downloadBinaryFile(String remoteUrl, File destination) throws Exception {
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

    private Response request(String url, String method, String body, String depth) throws Exception {
        return request(url, method, body == null ? null : body.getBytes(StandardCharsets.UTF_8), depth, null);
    }

    private Response request(String url, String method, byte[] body, String depth, Map<String, String> extraHeaders) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        setMethod(connection, method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", authorizationHeader());
        if (depth != null) {
            connection.setRequestProperty("Depth", depth);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            if (extraHeaders == null || !extraHeaders.containsKey("Content-Type")) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }
        }

        int responseCode = connection.getResponseCode();
        InputStream inputStream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] bytes = new byte[0];
        if (inputStream != null) {
            try (InputStream stream = inputStream) {
                bytes = readFullyBytes(stream);
            }
        }
        return new Response(responseCode, new String(bytes, StandardCharsets.UTF_8));
    }

    private BinaryResponse requestBinary(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        setMethod(connection, method);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", authorizationHeader());
        int responseCode = connection.getResponseCode();
        InputStream inputStream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] bytes = new byte[0];
        if (inputStream != null) {
            try (InputStream stream = inputStream) {
                bytes = readFullyBytes(stream);
            }
        }
        return new BinaryResponse(responseCode, bytes);
    }

    private String authorizationHeader() {
        String raw = settingsStore.getWebDavUser() + ":" + settingsStore.getWebDavPassword();
        return "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
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

    private void setMethod(HttpURLConnection connection, String method) throws Exception {
        try {
            connection.setRequestMethod(method);
            return;
        } catch (Exception ignored) {
        }
        Field methodField = HttpURLConnection.class.getDeclaredField("method");
        methodField.setAccessible(true);
        methodField.set(connection, method);
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

    private static class BinaryResponse {
        final int code;
        final byte[] bytes;

        BinaryResponse(int code, byte[] bytes) {
            this.code = code;
            this.bytes = bytes;
        }
    }
}
