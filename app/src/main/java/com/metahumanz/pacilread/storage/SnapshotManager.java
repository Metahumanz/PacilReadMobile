package com.metahumanz.pacilread.storage;

import android.content.Context;

import com.metahumanz.pacilread.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class SnapshotManager {
    private static final String SNAPSHOT_DIR = "snapshots";
    private static final String BUNDLE_FILE = "bundle.zip";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final String ENTRY_ANDROID_SETTINGS = "android-settings.json";
    private static final String DATABASE_PREFIX = "database/";
    private static final String[] DATABASE_FILES = {
            "books.json", "chapters.json", "rules.json", "themes.json", "bookmarks.json", "reading_stats.json"
    };
    private static final Set<String> RESTORABLE_ENTRIES = new HashSet<>(Arrays.asList(
            "database/books.json",
            "database/chapters.json",
            "database/rules.json",
            "database/themes.json",
            "database/bookmarks.json",
            "database/reading_stats.json",
            ENTRY_ANDROID_SETTINGS
    ));

    private final Context context;
    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final File snapshotsDir;

    public SnapshotManager(Context context, JsonDatabase databaseHelper, SettingsStore settingsStore) {
        this.context = context.getApplicationContext();
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.snapshotsDir = new File(this.context.getFilesDir(), SNAPSHOT_DIR);
    }

    public Snapshot createSnapshot(String reason) throws Exception {
        databaseHelper.ensureLoaded();
        databaseHelper.flush();
        if (!snapshotsDir.exists() && !snapshotsDir.mkdirs()) {
            throw new IllegalStateException("无法创建恢复点目录");
        }
        long now = System.currentTimeMillis();
        String id = String.format(Locale.ROOT, "snapshot-%d", now);
        File snapshotDir = new File(snapshotsDir, id);
        if (!snapshotDir.exists() && !snapshotDir.mkdirs()) {
            throw new IllegalStateException("无法创建恢复点");
        }

        File bundle = new File(snapshotDir, BUNDLE_FILE);
        JSONObject counts = new JSONObject();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(bundle))) {
            File dataDir = databaseHelper.getDataDir();
            for (String fileName : DATABASE_FILES) {
                File file = new File(dataDir, fileName);
                if (!file.exists() || !file.isFile()) continue;
                counts.put(entityName(fileName), countJsonArray(file));
                writeFileEntry(output, DATABASE_PREFIX + fileName, file);
            }
            JSONObject settingsJson = settingsStore.exportAndroidPrivateSettingsJson();
            writeTextEntry(output, ENTRY_ANDROID_SETTINGS, settingsJson.toString(2));
        }

        JSONObject manifest = new JSONObject();
        manifest.put("id", id);
        manifest.put("createdAt", now);
        manifest.put("reason", reason == null || reason.isBlank() ? "manual" : reason);
        manifest.put("versionName", BuildConfig.VERSION_NAME);
        manifest.put("versionCode", BuildConfig.VERSION_CODE);
        manifest.put("bundleSize", bundle.length());
        manifest.put("counts", counts);
        File manifestFile = new File(snapshotDir, MANIFEST_FILE);
        try (FileWriter writer = new FileWriter(manifestFile)) {
            writer.write(manifest.toString(2));
        }
        return Snapshot.fromManifest(manifest, bundle.length());
    }

    public List<Snapshot> listSnapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        File[] dirs = snapshotsDir.listFiles();
        if (dirs == null) return snapshots;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File manifest = new File(dir, MANIFEST_FILE);
            File bundle = new File(dir, BUNDLE_FILE);
            if (!manifest.exists() || !bundle.exists()) continue;
            try {
                JSONObject object = new JSONObject(readFileString(manifest));
                snapshots.add(Snapshot.fromManifest(object, bundle.length()));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(snapshots, (left, right) -> Long.compare(right.createdAt, left.createdAt));
        return snapshots;
    }

    public void restoreSnapshot(String id) throws Exception {
        File snapshotDir = safeSnapshotDir(id);
        File bundle = new File(snapshotDir, BUNDLE_FILE);
        if (!bundle.exists()) {
            throw new IllegalStateException("恢复点文件不存在");
        }
        File tempDir = new File(context.getCacheDir(), "snapshot_restore_" + System.currentTimeMillis());
        deleteRecursively(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IllegalStateException("无法创建恢复临时目录");
        }
        File dataDir = databaseHelper.getDataDir();
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IllegalStateException("无法创建数据库目录");
        }
        JSONObject settingsJson = null;
        try {
            try (ZipInputStream input = new ZipInputStream(new FileInputStream(bundle))) {
                ZipEntry entry;
                byte[] buffer = new byte[32768];
                while ((entry = input.getNextEntry()) != null) {
                    String name = sanitizeEntryName(entry.getName());
                    if (entry.isDirectory() || name == null || !RESTORABLE_ENTRIES.contains(name)) {
                        input.closeEntry();
                        continue;
                    }
                    if (ENTRY_ANDROID_SETTINGS.equals(name)) {
                        settingsJson = new JSONObject(readEntryString(input, buffer));
                    } else if (name.startsWith(DATABASE_PREFIX)) {
                        String fileName = name.substring(DATABASE_PREFIX.length());
                        File target = new File(tempDir, fileName);
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    input.closeEntry();
                }
            }
            for (String fileName : DATABASE_FILES) {
                File source = new File(tempDir, fileName);
                if (source.exists() && source.isFile()) {
                    replaceFile(source, new File(dataDir, fileName));
                }
            }
            if (settingsJson != null) {
                settingsStore.importAndroidPrivateSettingsJson(settingsJson, null);
            }
            databaseHelper.reloadFromDisk();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void deleteSnapshot(String id) throws Exception {
        deleteRecursively(safeSnapshotDir(id));
    }

    private File safeSnapshotDir(String id) throws Exception {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")) {
            throw new IllegalArgumentException("恢复点 ID 无效");
        }
        File dir = new File(snapshotsDir, id);
        String base = snapshotsDir.getCanonicalPath() + File.separator;
        String path = dir.getCanonicalPath();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("恢复点路径无效");
        }
        return dir;
    }

    private void writeFileEntry(ZipOutputStream output, String entryName, File file) throws Exception {
        output.putNextEntry(new ZipEntry(entryName));
        byte[] buffer = new byte[32768];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }

    private void writeTextEntry(ZipOutputStream output, String entryName, String text) throws Exception {
        output.putNextEntry(new ZipEntry(entryName));
        output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private int countJsonArray(File file) {
        try {
            String content = readFileString(file);
            if (content == null || content.trim().isEmpty()) return 0;
            return new JSONArray(content).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String entityName(String fileName) {
        if ("reading_stats.json".equals(fileName)) return "readingStats";
        int index = fileName.indexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private String readFileString(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return output.toString("UTF-8");
    }

    private void replaceFile(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建恢复目标目录");
        }
        File temp = new File(parent, target.getName() + ".restore.tmp");
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readEntryString(ZipInputStream input, byte[] buffer) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }

    private String sanitizeEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            return null;
        }
        for (String segment : name.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return name;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public static final class Snapshot {
        public final String id;
        public final long createdAt;
        public final String reason;
        public final long bundleSize;
        public final JSONObject counts;

        private Snapshot(String id, long createdAt, String reason, long bundleSize, JSONObject counts) {
            this.id = id;
            this.createdAt = createdAt;
            this.reason = reason;
            this.bundleSize = bundleSize;
            this.counts = counts == null ? new JSONObject() : counts;
        }

        static Snapshot fromManifest(JSONObject manifest, long bundleSize) {
            return new Snapshot(
                    manifest.optString("id", ""),
                    manifest.optLong("createdAt", 0L),
                    manifest.optString("reason", ""),
                    manifest.optLong("bundleSize", bundleSize),
                    manifest.optJSONObject("counts")
            );
        }
    }
}
