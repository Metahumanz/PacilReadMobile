package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class BookRecord {
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

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
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
}
