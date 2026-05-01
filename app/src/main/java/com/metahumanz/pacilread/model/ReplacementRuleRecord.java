package com.metahumanz.pacilread.model;

import org.json.JSONObject;

public class ReplacementRuleRecord {
    public long id;
    public String pattern;
    public String replacement;
    public String scope;
    public Long bookId;
    public boolean regex;
    public boolean active;
    public long updatedAt;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("pattern", pattern != null ? pattern : "");
            json.put("replacement", replacement != null ? replacement : "");
            json.put("scope", scope != null ? scope : "global");
            if (bookId != null) {
                json.put("bookId", bookId);
            }
            json.put("regex", regex);
            json.put("active", active);
            json.put("updatedAt", updatedAt);
        } catch (Exception ignore) {}
        return json;
    }

    public static ReplacementRuleRecord fromJson(JSONObject json) {
        ReplacementRuleRecord rule = new ReplacementRuleRecord();
        rule.id = json.optLong("id", 0);
        rule.pattern = json.optString("pattern", "");
        rule.replacement = json.optString("replacement", "");
        rule.scope = json.optString("scope", "global");
        if (json.has("bookId") && !json.isNull("bookId")) {
            rule.bookId = json.optLong("bookId", -1);
        }
        rule.regex = json.optBoolean("regex", false);
        rule.active = json.optBoolean("active", true);
        rule.updatedAt = json.optLong("updatedAt", 0);
        return rule;
    }
}
