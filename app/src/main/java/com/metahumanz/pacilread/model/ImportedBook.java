package com.metahumanz.pacilread.model;

import java.util.ArrayList;
import java.util.List;

public class ImportedBook {
    public String title;
    public String author;
    public String sourceDisplayName;
    public String storedPath;
    public String bookType = "text";
    public final List<ChapterSeed> chapters = new ArrayList<>();

    public static class ChapterSeed {
        public final String title;
        public final String bodyHtml;
        public final String bodyText;
        public final int orderIndex;

        public ChapterSeed(String title, String bodyHtml, String bodyText, int orderIndex) {
            this.title = title;
            this.bodyHtml = bodyHtml;
            this.bodyText = bodyText;
            this.orderIndex = orderIndex;
        }
    }
}
