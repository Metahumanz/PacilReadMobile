package com.metahumanz.pacilread.reader.search;

import java.util.ArrayList;
import java.util.List;

public final class SearchTextMatcher {
    private SearchTextMatcher() {
    }

    public static List<Integer> findAll(String text, String query) {
        List<Integer> offsets = new ArrayList<>();
        String normalizedText = normalize(text);
        String normalizedQuery = normalize(query == null ? "" : query.trim());
        if (normalizedQuery.isEmpty()) return offsets;
        int from = 0;
        while (from <= normalizedText.length() - normalizedQuery.length()) {
            int match = normalizedText.indexOf(normalizedQuery, from);
            if (match < 0) break;
            offsets.add(match);
            from = match + 1;
        }
        return offsets;
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) result.append(Character.toLowerCase(value.charAt(i)));
        return result.toString();
    }
}
