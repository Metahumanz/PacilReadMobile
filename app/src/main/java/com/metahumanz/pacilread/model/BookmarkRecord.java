package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class BookmarkRecord {
    public long id;
    public String uuid;
    public long bookId;
    public String bookIdentity;
    public String bookTitle;
    public String bookAuthor;
    public int chapterOrderIndex;
    public String chapterTitle;
    public int chapterOffset;
    public float progressPercent;
    public String summary;
    public long createdAt;
    public long updatedAt;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("uuid", uuid != null ? uuid : "");
            json.put("bookId", bookId);
            json.put("bookIdentity", bookIdentity != null ? bookIdentity : "");
            json.put("bookTitle", bookTitle != null ? bookTitle : "");
            json.put("bookAuthor", bookAuthor != null ? bookAuthor : "");
            json.put("chapterOrderIndex", chapterOrderIndex);
            json.put("chapterTitle", chapterTitle != null ? chapterTitle : "");
            json.put("chapterOffset", chapterOffset);
            json.put("progressPercent", (double) progressPercent);
            json.put("summary", summary != null ? summary : "");
            json.put("createdAt", createdAt);
            json.put("updatedAt", updatedAt);
        } catch (Exception ignore) {}
        return json;
    }

    public static BookmarkRecord fromJson(JSONObject json) {
        BookmarkRecord bookmark = new BookmarkRecord();
        bookmark.id = json.optLong("id", 0);
        bookmark.uuid = json.optString("uuid", "");
        bookmark.bookId = json.optLong("bookId", -1);
        bookmark.bookIdentity = json.optString("bookIdentity", "");
        bookmark.bookTitle = json.optString("bookTitle", "");
        bookmark.bookAuthor = json.optString("bookAuthor", "");
        bookmark.chapterOrderIndex = json.optInt("chapterOrderIndex", 0);
        bookmark.chapterTitle = json.optString("chapterTitle", "");
        bookmark.chapterOffset = json.optInt("chapterOffset", 0);
        bookmark.progressPercent = (float) json.optDouble("progressPercent", 0);
        bookmark.summary = json.optString("summary", "");
        bookmark.createdAt = json.optLong("createdAt", 0);
        bookmark.updatedAt = json.optLong("updatedAt", 0);
        return bookmark;
    }
}
