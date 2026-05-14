package com.metahumanz.pacilread.reader.modern;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.tts.SystemTtsClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class ReaderRuntime {
    private static final String TAG = "PacilReadReader";

    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final ExecutorService paginationExecutor = Executors.newSingleThreadExecutor();
    public final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    public final ExecutorService synthesisExecutor = Executors.newSingleThreadExecutor();
    public final Handler mainHandler = new Handler(Looper.getMainLooper());
    public final JsonDatabase databaseHelper;
    public final SettingsStore settingsStore;
    public final WebDavClient webDavClient;
    public final WebDavProgressSyncCoordinator progressSyncCoordinator;
    public final ReadingStatsSyncManager readingStatsSyncManager;
    public final MimoTtsClient mimoTtsClient;
    public final SystemTtsClient systemTtsClient;
    private volatile boolean shutdown;

    public ReaderRuntime(Context context) {
        databaseHelper = JsonDatabase.getInstance(context);
        settingsStore = new SettingsStore(context);
        webDavClient = new WebDavClient(settingsStore);
        progressSyncCoordinator = new WebDavProgressSyncCoordinator(databaseHelper, settingsStore, webDavClient);
        readingStatsSyncManager = new ReadingStatsSyncManager(context, databaseHelper, settingsStore, webDavClient);
        mimoTtsClient = new MimoTtsClient();
        systemTtsClient = new SystemTtsClient(context, settingsStore.getTtsSystemEnginePackage());
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public boolean safeExecute(Runnable task, String label) {
        return safeExecute(executor, task, label);
    }

    public boolean safeExecutePagination(Runnable task, String label) {
        return safeExecute(paginationExecutor, task, label);
    }

    public boolean safeExecuteTts(Runnable task, String label) {
        return safeExecute(ttsExecutor, task, label);
    }

    public boolean safeExecuteSynthesis(Runnable task, String label) {
        return safeExecute(synthesisExecutor, task, label);
    }

    private boolean safeExecute(ExecutorService targetExecutor, Runnable task, String label) {
        if (task == null || targetExecutor == null || shutdown || targetExecutor.isShutdown()) {
            return false;
        }
        try {
            targetExecutor.execute(() -> {
                try {
                    if (!shutdown) {
                        task.run();
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Reader background task failed: " + safeLabel(label), error);
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            Log.d(TAG, "Reader background task rejected after shutdown: " + safeLabel(label), error);
            return false;
        }
    }

    private String safeLabel(String label) {
        return label == null || label.isBlank() ? "unnamed" : label;
    }

    public void shutdown() {
        shutdown = true;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            systemTtsClient.shutdown();
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to shutdown system TTS", error);
        }
        executor.shutdownNow();
        paginationExecutor.shutdownNow();
        ttsExecutor.shutdownNow();
        synthesisExecutor.shutdownNow();
        try {
            mimoTtsClient.cancel();
        } catch (RuntimeException error) {
            Log.w(TAG, "Failed to cancel MiMo TTS", error);
        }
    }
}
