package com.metahumanz.pacilread.reader.modern;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.tts.MimoTtsClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderRuntime {
    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    public final Handler mainHandler = new Handler(Looper.getMainLooper());
    public final ReaderDatabaseHelper databaseHelper;
    public final SettingsStore settingsStore;
    public final WebDavClient webDavClient;
    public final MimoTtsClient mimoTtsClient;

    public ReaderRuntime(Context context) {
        databaseHelper = ReaderDatabaseHelper.getInstance(context);
        settingsStore = new SettingsStore(context);
        webDavClient = new WebDavClient(settingsStore);
        mimoTtsClient = new MimoTtsClient();
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        ttsExecutor.shutdownNow();
        mimoTtsClient.cancel();
    }
}
