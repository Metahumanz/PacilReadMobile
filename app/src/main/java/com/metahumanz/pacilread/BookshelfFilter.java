package com.metahumanz.pacilread;

import com.metahumanz.pacilread.model.BookRecord;

import java.util.Locale;

public final class BookshelfFilter {
    private BookshelfFilter() {
    }

    public static boolean matches(BookRecord book, String query, String tag, String series, String status) {
        if (book == null) return false;
        String normalizedQuery = normalize(query);
        boolean queryMatch = normalizedQuery.isEmpty()
                || normalize(book.title).contains(normalizedQuery)
                || normalize(book.author).contains(normalizedQuery);
        boolean tagMatch = isEmpty(tag) || (book.tags != null && book.tags.contains(tag));
        boolean seriesMatch = isEmpty(series) || safe(book.series).equals(series);
        boolean statusMatch = isEmpty(status) || safe(book.readingStatus).equals(status);
        return queryMatch && tagMatch && seriesMatch && statusMatch;
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
