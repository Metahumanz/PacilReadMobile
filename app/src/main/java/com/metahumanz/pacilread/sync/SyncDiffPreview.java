package com.metahumanz.pacilread.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncDiffPreview {
    public static final String MODE_FULL = "full";
    public static final String MODE_INCREMENTAL = "incremental";

    public final String mode;
    public final List<SyncDiffItem> items = new ArrayList<>();
    public final Map<String, JSONArray> remoteEntities = new HashMap<>();
    public JSONObject remoteManifest;

    public SyncDiffPreview(String mode) {
        this.mode = mode;
    }

    public int countStatus(String status) {
        int count = 0;
        for (SyncDiffItem item : items) {
            if (status.equals(item.status)) count++;
        }
        return count;
    }

    public int conflictCount() {
        return countStatus(SyncDiffItem.STATUS_CONFLICT);
    }

    public int remoteCount() {
        return countStatus(SyncDiffItem.STATUS_REMOTE);
    }

    public int localCount() {
        return countStatus(SyncDiffItem.STATUS_LOCAL);
    }

    public int unchangedCount() {
        return countStatus(SyncDiffItem.STATUS_UNCHANGED);
    }
}
