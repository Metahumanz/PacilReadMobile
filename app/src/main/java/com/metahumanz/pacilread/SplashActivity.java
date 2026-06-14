package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.storage.SnapshotManager;
import com.metahumanz.pacilread.theme.ThemedActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends ThemedActivity {
    private static final String TAG = "SplashActivity";

    public static final String EXTRA_AUTO_OPEN_BOOK_ID =
            "com.metahumanz.pacilread.EXTRA_AUTO_OPEN_BOOK_ID";

    private static final long MIN_DISPLAY_MS = 600L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService splashExecutor = Executors.newSingleThreadExecutor();
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
            finish();
            return;
        }

        SettingsStore settingsStore = new SettingsStore(this);
        if (settingsStore.isAutoOpenLastBook()) {
            splashExecutor.execute(() -> {
                try {
                    JsonDatabase databaseHelper = JsonDatabase.getInstance(SplashActivity.this);
                    try {
                        new SnapshotManager(SplashActivity.this, databaseHelper, settingsStore)
                                .createDailyStartupSnapshotIfNeeded();
                    } catch (Exception error) {
                        Log.w(TAG, "Daily startup snapshot failed", error);
                    }
                    long bookId = -1L;
                    if (databaseHelper.isDatabaseHealthyForStartup()) {
                        bookId = databaseHelper.getMostRecentBookId();
                    } else {
                        Log.w(TAG, "Database health check failed, opening bookshelf instead of reader");
                    }
                    long launchBookId = bookId;
                    handler.postDelayed(() -> {
                        if (finishing) return;
                        Intent intent = new Intent(SplashActivity.this, BookshelfActivity.class);
                        if (launchBookId > 0) {
                            intent.putExtra(EXTRA_AUTO_OPEN_BOOK_ID, launchBookId);
                        }
                        startActivity(intent);
                        finish();
                    }, MIN_DISPLAY_MS);
                } catch (Exception error) {
                    Log.w(TAG, "Auto-open startup failed, opening bookshelf", error);
                    handler.postDelayed(() -> {
                        if (finishing) return;
                        startActivity(new Intent(SplashActivity.this, BookshelfActivity.class));
                        finish();
                    }, MIN_DISPLAY_MS);
                }
            });
        } else {
            splashExecutor.execute(() -> {
                try {
                    JsonDatabase databaseHelper = JsonDatabase.getInstance(SplashActivity.this);
                    new SnapshotManager(SplashActivity.this, databaseHelper, settingsStore)
                            .createDailyStartupSnapshotIfNeeded();
                } catch (Exception error) {
                    Log.w(TAG, "Daily startup snapshot failed", error);
                }
                handler.postDelayed(() -> {
                    if (finishing) return;
                    startActivity(new Intent(SplashActivity.this, BookshelfActivity.class));
                    finish();
                }, MIN_DISPLAY_MS);
            });
        }
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        splashExecutor.shutdownNow();
    }
}
