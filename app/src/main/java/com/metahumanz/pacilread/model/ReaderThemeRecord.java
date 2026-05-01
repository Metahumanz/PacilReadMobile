package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class ReaderThemeRecord {
    public long id;
    public String name;
    public String configJson;
    public long updatedAt;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name != null ? name : "");
            json.put("configJson", configJson != null ? configJson : "{}");
            json.put("updatedAt", updatedAt);
        } catch (Exception ignore) {}
        return json;
    }

    public static ReaderThemeRecord fromJson(JSONObject json) {
        ReaderThemeRecord theme = new ReaderThemeRecord();
        theme.id = json.optLong("id", 0);
        theme.name = json.optString("name", "");
        theme.configJson = json.optString("configJson", "{}");
        theme.updatedAt = json.optLong("updatedAt", 0);
        return theme;
    }
}
