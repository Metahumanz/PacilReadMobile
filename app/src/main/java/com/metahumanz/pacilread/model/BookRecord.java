package com.metahumanz.pacilread.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class BookRecord {
    public static final String STATUS_UNREAD = "unread";
    public static final String STATUS_READING = "reading";
    public static final String STATUS_FINISHED = "finished";

    public long id;
    public String title;
    public String author;
    public String localPath;
    public String coverPath;
    public String bookType;
    public String readingStatsKey;
    public int progressIndex;
    public int progressOffset;
    public long lastReadAt;
    public boolean pinned;
    public String currentChapterTitle;
    public int chapterCount;
    public long createdAt;
    public long updatedAt;
    public List<String> tags = new ArrayList<>();
    public String series = "";
    public Double seriesIndex;
    public String readingStatus = STATUS_UNREAD;
    public JSONObject extraJson = new JSONObject();

    public JSONObject toJson() {
        JSONObject json = cloneJson(extraJson);
        try {
            json.put("id", id);
            json.put("title", title != null ? title : "");
            json.put("author", author != null ? author : "");
            json.put("bookType", bookType != null ? bookType : "text");
            json.put("readingStatsKey", readingStatsKey != null ? readingStatsKey : "");
            json.put("progressIndex", progressIndex);
            json.put("progressOffset", progressOffset);
            json.put("lastReadAt", lastReadAt);
            json.put("pinned", pinned);
            json.put("chapterCount", chapterCount);
            json.put("currentChapterTitle", currentChapterTitle != null ? currentChapterTitle : "");
            json.put("createdAt", createdAt);
            json.put("updatedAt", updatedAt);
            json.put("tags", tagsToJson(tags));
            json.put("series", series != null ? series : "");
            Double safeSeriesIndex = sanitizeSeriesIndex(seriesIndex);
            if (safeSeriesIndex != null) {
                json.put("seriesIndex", safeSeriesIndex);
            } else {
                json.remove("seriesIndex");
            }
            json.put("readingStatus", normalizeReadingStatus(readingStatus,
                    hasReadingProgress(progressIndex, progressOffset, lastReadAt)));
            // 仅存文件名，不存绝对路径
            String coverFile = "";
            if (coverPath != null && !coverPath.isEmpty()) {
                coverFile = new java.io.File(coverPath).getName();
            }
            json.put("coverFile", coverFile);
            String sourceFile = "";
            if (localPath != null && !localPath.isEmpty()) {
                sourceFile = new java.io.File(localPath).getName();
            }
            json.put("sourceFile", sourceFile);
        } catch (Exception ignore) {}
        return json;
    }

    public static BookRecord fromJson(JSONObject json) {
        BookRecord book = new BookRecord();
        book.extraJson = collectExtraJson(json);
        book.id = json.optLong("id", 0);
        book.title = json.optString("title", "");
        book.author = json.optString("author", "");
        book.bookType = json.optString("bookType", "text");
        book.readingStatsKey = json.optString("readingStatsKey", "");
        book.progressIndex = json.optInt("progressIndex", 0);
        book.progressOffset = json.optInt("progressOffset", 0);
        book.lastReadAt = json.optLong("lastReadAt", 0);
        book.pinned = json.optBoolean("pinned", false);
        book.chapterCount = json.optInt("chapterCount", 0);
        book.currentChapterTitle = json.optString("currentChapterTitle", "");
        book.createdAt = json.optLong("createdAt", 0);
        book.updatedAt = json.optLong("updatedAt", 0);
        book.tags = parseTags(json.optJSONArray("tags"));
        book.series = json.optString("series", "");
        book.seriesIndex = parseSeriesIndex(json);
        book.readingStatus = normalizeReadingStatus(
                json.optString("readingStatus", ""),
                hasReadingProgress(book.progressIndex, book.progressOffset, book.lastReadAt)
        );
        // coverFile/sourceFile 在 rebaseLocalAssetPaths 时拼接为绝对路径
        String coverFile = json.optString("coverFile", "");
        if (!coverFile.isEmpty()) {
            book.coverPath = coverFile;
        }
        String sourceFile = json.optString("sourceFile", "");
        if (!sourceFile.isEmpty()) {
            book.localPath = sourceFile;
        }
        return book;
    }

    public void copyExtendedFieldsFrom(BookRecord source) {
        if (source == null) return;
        tags = source.tags == null ? new ArrayList<>() : new ArrayList<>(source.tags);
        series = source.series != null ? source.series : "";
        seriesIndex = sanitizeSeriesIndex(source.seriesIndex);
        readingStatus = normalizeReadingStatus(
                source.readingStatus,
                hasReadingProgress(progressIndex, progressOffset, lastReadAt)
        );
        extraJson = cloneJson(source.extraJson);
    }

    public static String normalizeReadingStatus(String value, boolean hasProgress) {
        if (STATUS_READING.equals(value) || STATUS_FINISHED.equals(value) || STATUS_UNREAD.equals(value)) {
            return value;
        }
        return hasProgress ? STATUS_READING : STATUS_UNREAD;
    }

    public static boolean hasReadingProgress(int progressIndex, int progressOffset, long lastReadAt) {
        return progressIndex > 0 || progressOffset > 0 || lastReadAt > 0;
    }

    private static List<String> parseTags(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (value.isEmpty() || !seen.add(value)) continue;
            result.add(value);
        }
        return result;
    }

    private static JSONArray tagsToJson(List<String> values) {
        JSONArray array = new JSONArray();
        if (values == null) return array;
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String safeValue = value == null ? "" : value.trim();
            if (safeValue.isEmpty() || !seen.add(safeValue)) continue;
            array.put(safeValue);
        }
        return array;
    }

    private static Double parseSeriesIndex(JSONObject json) {
        if (json == null || !json.has("seriesIndex") || json.isNull("seriesIndex")) {
            return null;
        }
        double value = json.optDouble("seriesIndex", Double.NaN);
        return sanitizeSeriesIndex(value);
    }

    private static Double sanitizeSeriesIndex(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return value;
    }

    private static JSONObject collectExtraJson(JSONObject source) {
        JSONObject extra = new JSONObject();
        if (source == null) return extra;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isKnownField(key)) continue;
            try {
                extra.put(key, source.opt(key));
            } catch (Exception ignore) {
            }
        }
        return extra;
    }

    private static JSONObject cloneJson(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static boolean isKnownField(String key) {
        return "id".equals(key)
                || "title".equals(key)
                || "author".equals(key)
                || "localPath".equals(key)
                || "coverPath".equals(key)
                || "bookType".equals(key)
                || "readingStatsKey".equals(key)
                || "progressIndex".equals(key)
                || "progressOffset".equals(key)
                || "lastReadAt".equals(key)
                || "pinned".equals(key)
                || "chapterCount".equals(key)
                || "currentChapterTitle".equals(key)
                || "createdAt".equals(key)
                || "updatedAt".equals(key)
                || "coverFile".equals(key)
                || "sourceFile".equals(key)
                || "tags".equals(key)
                || "series".equals(key)
                || "seriesIndex".equals(key)
                || "readingStatus".equals(key);
    }
}
