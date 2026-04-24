package com.metahumanz.pacilread.model;

public class BookRecord {
    public long id;
    public String title;
    public String author;
    public String localPath;
    public String coverPath;
    public String bookType;
    public String readingStatsKey;
    public int progressIndex;
    public int progressOffset;
    public long lastReadAt;
    public boolean pinned;
    public String currentChapterTitle;
    public int chapterCount;
}
