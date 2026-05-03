package com.metahumanz.pacilread.reader.modern;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.tts.SystemTtsClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderRuntime {
    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final ExecutorService paginationExecutor = Executors.newSingleThreadExecutor();
    public final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    public final ExecutorService synthesisExecutor = Executors.newSingleThreadExecutor();
    public final Handler mainHandler = new Handler(Looper.getMainLooper());
    public final JsonDatabase databaseHelper;
    public final SettingsStore settingsStore;
    public final WebDavClient webDavClient;
    public final ReadingStatsSyncManager readingStatsSyncManager;
    public final MimoTtsClient mimoTtsClient;
    public final SystemTtsClient systemTtsClient;

    public ReaderRuntime(Context context) {
        databaseHelper = JsonDatabase.getInstance(context);
        settingsStore = new SettingsStore(context);
        webDavClient = new WebDavClient(settingsStore);
        readingStatsSyncManager = new ReadingStatsSyncManager(context, databaseHelper, settingsStore, webDavClient);
        mimoTtsClient = new MimoTtsClient();
        systemTtsClient = new SystemTtsClient(context, settingsStore.getTtsSystemEnginePackage());
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        systemTtsClient.shutdown();
        executor.shutdownNow();
        paginationExecutor.shutdownNow();
        ttsExecutor.shutdownNow();
        synthesisExecutor.shutdownNow();
        mimoTtsClient.cancel();
    }
}
