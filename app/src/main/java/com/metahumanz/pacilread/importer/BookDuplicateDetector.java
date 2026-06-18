package com.metahumanz.pacilread.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BookDuplicateDetector {
    public enum MatchType { EXACT_CONTENT, SAME_TITLE_AUTHOR }

    public static final class Candidate {
        public final String key;
        public final String title;
        public final String author;
        public final String sha256;

        public Candidate(String key, String title, String author, String sha256) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        }
    }

    private BookDuplicateDetector() {
    }

    public static Map<String, MatchType> detect(List<Candidate> existing, List<Candidate> incoming) {
        Map<String, MatchType> result = new HashMap<>();
        List<Candidate> seen = new ArrayList<>();
        if (existing != null) seen.addAll(existing);
        if (incoming == null) return result;
        for (Candidate candidate : incoming) {
            MatchType match = findMatch(seen, candidate);
            if (match != null) result.put(candidate.key, match);
            seen.add(candidate);
        }
        return result;
    }

    private static MatchType findMatch(List<Candidate> candidates, Candidate target) {
        if (target == null) return null;
        if (!target.sha256.isEmpty()) {
            for (Candidate candidate : candidates) {
                if (target.sha256.equals(candidate.sha256)) return MatchType.EXACT_CONTENT;
            }
        }
        String targetIdentity = identity(target.title, target.author);
        if (targetIdentity.isEmpty()) return null;
        for (Candidate candidate : candidates) {
            if (targetIdentity.equals(identity(candidate.title, candidate.author))) {
                return MatchType.SAME_TITLE_AUTHOR;
            }
        }
        return null;
    }

    public static String identity(String title, String author) {
        String safeTitle = normalize(title);
        String safeAuthor = normalize(author);
        if (safeTitle.isEmpty() && safeAuthor.isEmpty()) return "";
        return safeTitle + "\u0000" + safeAuthor;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
