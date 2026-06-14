package com.metahumanz.pacilread.sync;

public class SyncDiffItem {
    public static final String STATUS_LOCAL = "local";
    public static final String STATUS_REMOTE = "remote";
    public static final String STATUS_CONFLICT = "conflict";
    public static final String STATUS_UNCHANGED = "unchanged";

    public String entityType;
    public String key;
    public String title;
    public String status;
    public String summary;
    public long localUpdatedAt;
    public long remoteUpdatedAt;
}
