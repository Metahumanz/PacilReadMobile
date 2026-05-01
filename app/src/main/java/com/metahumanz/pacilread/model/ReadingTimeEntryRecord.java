package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class ReadingTimeEntryRecord {
    public long id;
    public String date;
    public String sourceDeviceId;
    public String bookIdentity;
    public String bookTitle;
    public String bookAuthor;
    public int durationSeconds;
    public int charCount;
    public long updatedAt;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("date", date != null ? date : "");
            json.put("sourceDeviceId", sourceDeviceId != null ? sourceDeviceId : "");
            json.put("bookIdentity", bookIdentity != null ? bookIdentity : "");
            json.put("bookTitle", bookTitle != null ? bookTitle : "");
            json.put("bookAuthor", bookAuthor != null ? bookAuthor : "");
            json.put("durationSeconds", durationSeconds);
            json.put("charCount", charCount);
            json.put("updatedAt", updatedAt);
        } catch (Exception ignore) {}
        return json;
    }

    public static ReadingTimeEntryRecord fromJson(JSONObject json) {
        ReadingTimeEntryRecord entry = new ReadingTimeEntryRecord();
        entry.id = json.optLong("id", 0);
        entry.date = json.optString("date", "");
        entry.sourceDeviceId = json.optString("sourceDeviceId", "");
        entry.bookIdentity = json.optString("bookIdentity", "");
        entry.bookTitle = json.optString("bookTitle", "");
        entry.bookAuthor = json.optString("bookAuthor", "");
        entry.durationSeconds = json.optInt("durationSeconds", 0);
        entry.charCount = json.optInt("charCount", 0);
        entry.updatedAt = json.optLong("updatedAt", 0);
        return entry;
    }
}
