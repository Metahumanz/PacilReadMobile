package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class ChapterRecord {
    public long id;
    public long bookId;
    public String title;
    public String bodyHtml;
    public String bodyText;
    public int orderIndex;
    public String bodyTextPath;
    public String bodyTextStorage = "db";
    public long bodyTextSize;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("bookId", bookId);
            json.put("title", title != null ? title : "");
            json.put("orderIndex", orderIndex);
            json.put("bodyTextPath", bodyTextPath != null ? bodyTextPath : "");
            json.put("bodyTextStorage", bodyTextStorage != null ? bodyTextStorage : "db");
            json.put("bodyTextSize", bodyTextSize);
        } catch (Exception ignore) {}
        return json;
    }

    public static ChapterRecord fromJson(JSONObject json) {
        ChapterRecord chapter = new ChapterRecord();
        chapter.id = json.optLong("id", 0);
        chapter.bookId = json.optLong("bookId", 0);
        chapter.title = json.optString("title", "");
        chapter.orderIndex = json.optInt("orderIndex", 0);
        chapter.bodyTextPath = json.optString("bodyTextPath", "");
        chapter.bodyTextStorage = json.optString("bodyTextStorage", "db");
        chapter.bodyTextSize = json.optLong("bodyTextSize", 0);
        chapter.bodyHtml = "";
        chapter.bodyText = "";
        return chapter;
    }
}
