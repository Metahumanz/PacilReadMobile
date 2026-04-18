package com.metahumanz.pacilreadmobile.importer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BookFileNameParser {
    private static final Pattern TITLE_PATTERN = Pattern.compile("《([^》]+)》");
    private static final Pattern AUTHOR_MARKER = Pattern.compile("(?:作者|Author)\\s*[:： ]\\s*([^\\s_()（）\\[\\]【】-]{1,24})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_AUTHOR = Pattern.compile("^[\\[【]([^\\]】]{1,24})[\\]】]\\s*(.+)$");
    private static final Pattern DASH_AUTHOR = Pattern.compile("^(.+?)\\s*[-_]\\s*([^-_]{1,24})$");

    private BookFileNameParser() {
    }

    public static ParsedName parse(String rawName) {
        String stem = rawName == null ? "" : rawName.trim();
        int dotIndex = stem.lastIndexOf('.');
        if (dotIndex > 0) {
            stem = stem.substring(0, dotIndex);
        }

        String title = stem;
        String author = null;

        Matcher titleMatcher = TITLE_PATTERN.matcher(stem);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        Matcher authorMatcher = AUTHOR_MARKER.matcher(stem);
        if (authorMatcher.find()) {
            author = cleanAuthor(authorMatcher.group(1));
            title = stem.replace(authorMatcher.group(0), " ");
        }

        if (author == null) {
            Matcher bracketMatcher = BRACKET_AUTHOR.matcher(stem);
            if (bracketMatcher.matches()) {
                author = cleanAuthor(bracketMatcher.group(1));
                title = bracketMatcher.group(2);
            }
        }

        if (author == null) {
            Matcher dashMatcher = DASH_AUTHOR.matcher(stem);
            if (dashMatcher.matches()) {
                title = dashMatcher.group(1);
                author = cleanAuthor(dashMatcher.group(2));
            }
        }

        title = title.replaceAll("[（(][^）)]*(?:精校|校对|全本|番外|完结|修改)[^）)]*[）)]", " ");
        title = title.replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ").trim();
        title = title.replace("《", "").replace("》", "").trim();

        if ("未知".equals(author) || author != null && author.isBlank()) {
            author = null;
        }
        if (title.isBlank()) {
            title = stem;
        }

        return new ParsedName(title, author);
    }

    private static String cleanAuthor(String value) {
        return value == null ? null : value.replaceAll("[_\\-]+", " ").trim();
    }

    public static final class ParsedName {
        public final String title;
        public final String author;

        public ParsedName(String title, String author) {
            this.title = title;
            this.author = author;
        }
    }
}
