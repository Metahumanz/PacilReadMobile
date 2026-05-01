package com.metahumanz.pacilread.sync;

import android.content.Context;

import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ReadingStatsSyncManager {
    private static final int SCHEMA_VERSION = 1;

    private final Context context;
    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final WebDavClient webDavClient;

    public ReadingStatsSyncManager(
            Context context,
            JsonDatabase databaseHelper,
            SettingsStore settingsStore,
            WebDavClient webDavClient
    ) {
        this.context = context.getApplicationContext();
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.webDavClient = webDavClient;
    }

    public boolean canAutoSync() {
        return settingsStore.isReadingTimeTrackingEnabled()
                && settingsStore.isWebDavEnabled()
                && settingsStore.isWebDavSyncReadingStatsEnabled();
    }

    public void uploadLocalReadingStatsSnapshot() throws Exception {
        if (!settingsStore.isWebDavEnabled() || !settingsStore.isWebDavSyncReadingStatsEnabled()) {
            return;
        }
        webDavClient.ensureReadingStatsDirectory();
        String deviceId = settingsStore.getReadingStatsDeviceId();
        List<ReadingTimeEntryRecord> rows = databaseHelper.getReadingStatsRowsForSync(deviceId);
        JSONObject payload = new JSONObject();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("deviceId", deviceId);
        payload.put("generatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (ReadingTimeEntryRecord row : rows) {
            JSONObject object = new JSONObject();
            object.put("date", row.date);
            object.put("sourceDeviceId", row.sourceDeviceId);
            object.put("bookIdentity", row.bookIdentity);
            object.put("bookTitle", row.bookTitle);
            object.put("bookAuthor", row.bookAuthor);
            object.put("durationSeconds", row.durationSeconds);
            object.put("charCount", row.charCount);
            object.put("updatedAt", row.updatedAt);
            items.put(object);
        }
        payload.put("rows", items);
        webDavClient.uploadText(
                currentDeviceRemotePath(),
                payload.toString(2),
                "application/json; charset=utf-8"
        );
    }

    public void downloadAndMergeReadingStats() throws Exception {
        if (!settingsStore.isWebDavEnabled() || !settingsStore.isWebDavSyncReadingStatsEnabled()) {
            return;
        }
        webDavClient.ensureReadingStatsDirectory();
        List<String> files = webDavClient.listFiles(webDavClient.readingStatsBaseUrl());
        List<ReadingTimeEntryRecord> mergedRows = new ArrayList<>();
        for (String remoteFile : files) {
            if (!remoteFile.endsWith(".json")) {
                continue;
            }
            String json = webDavClient.downloadText(remoteFile);
            JSONObject payload = new JSONObject(json);
            JSONArray rows = payload.optJSONArray("rows");
            if (rows == null) {
                continue;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject rowObject = rows.optJSONObject(i);
                if (rowObject == null) {
                    continue;
                }
                ReadingTimeEntryRecord record = new ReadingTimeEntryRecord();
                record.date = rowObject.optString("date", "");
                record.sourceDeviceId = rowObject.optString("sourceDeviceId", payload.optString("deviceId", ""));
                record.bookIdentity = rowObject.optString("bookIdentity", ReadingStatsUtils.LEGACY_BOOK_IDENTITY);
                record.bookTitle = rowObject.optString("bookTitle", ReadingStatsUtils.LEGACY_BOOK_TITLE);
                record.bookAuthor = rowObject.optString("bookAuthor", "");
                record.durationSeconds = rowObject.optInt("durationSeconds", 0);
                record.charCount = rowObject.optInt("charCount", 0);
                record.updatedAt = rowObject.optLong("updatedAt", 0L);
                if (record.date.isBlank() || record.bookIdentity.isBlank()) {
                    continue;
                }
                mergedRows.add(record);
            }
        }
        databaseHelper.mergeReadingStatsRows(mergedRows);
    }

    public void clearRemoteReadingStats() throws Exception {
        if (!settingsStore.isWebDavEnabled() || settingsStore.getWebDavUrl().isBlank()) {
            return;
        }
        List<String> files = webDavClient.listFiles(webDavClient.readingStatsBaseUrl());
        for (String file : files) {
            if (file.endsWith(".json")) {
                webDavClient.delete(file);
            }
        }
    }

    public String currentDeviceRemotePath() {
        return webDavClient.readingStatsBaseUrl() + "device-" + sanitize(settingsStore.getReadingStatsDeviceId()) + ".json";
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown-device";
        }
        return value.replaceAll("[\\\\/:\"*?<>|]", "_");
    }
}
