package com.metahumanz.pacilread.model;

public class ReadingBookStatRecord {
    public String bookIdentity;
    public String bookTitle;
    public String bookAuthor;
    public int totalDurationSeconds;
    public int totalCharCount;
    public long updatedAt;
    public long localBookId = -1L;
    public String localCoverPath;
}
