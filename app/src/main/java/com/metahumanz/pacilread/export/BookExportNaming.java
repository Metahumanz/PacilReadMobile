package com.metahumanz.pacilread.export;

import com.metahumanz.pacilread.model.BookRecord;

import java.io.File;
import java.util.Locale;
import java.util.Set;

public final class BookExportNaming {
    private BookExportNaming() {
    }

    public static String uniqueFileName(BookRecord book, Set<String> usedNames) {
        String preferred = sanitize(book == null ? "" : book.sourceDisplayName);
        String extension = extensionFor(book);
        if (preferred.isEmpty()) {
            String title = sanitize(book == null ? "" : book.title);
            String author = sanitize(book == null ? "" : book.author);
            if (title.isEmpty()) title = "未命名书籍";
            preferred = author.isEmpty() ? title + extension : title + " - " + author + extension;
        } else if (!preferred.toLowerCase(Locale.ROOT).endsWith(extension)) {
            preferred += extension;
        }
        String base = stripExtension(preferred);
        String suffix = preferred.substring(base.length());
        String candidate = preferred;
        int index = 2;
        while (containsIgnoreCase(usedNames, candidate)) {
            candidate = base + " (" + index++ + ")" + suffix;
        }
        if (usedNames != null) usedNames.add(candidate);
        return candidate;
    }

    private static String extensionFor(BookRecord book) {
        if (book != null && book.localPath != null) {
            String name = new File(book.localPath).getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) return name.substring(dot).toLowerCase(Locale.ROOT);
        }
        if (book != null && "epub".equalsIgnoreCase(book.bookType)) return ".epub";
        if (book != null && "pdf".equalsIgnoreCase(book.bookType)) return ".pdf";
        return ".txt";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean containsIgnoreCase(Set<String> values, String target) {
        if (values == null) return false;
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) return true;
        }
        return false;
    }
}
