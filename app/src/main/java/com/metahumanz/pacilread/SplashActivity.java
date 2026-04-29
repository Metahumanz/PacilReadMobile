package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends ThemedActivity {

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
                    long bookId = ReaderDatabaseHelper.getInstance(SplashActivity.this).getMostRecentBookId();
                    handler.postDelayed(() -> {
                        if (finishing) return;
                        Intent intent = new Intent(SplashActivity.this, BookshelfActivity.class);
                        if (bookId > 0) {
                            intent.putExtra(EXTRA_AUTO_OPEN_BOOK_ID, bookId);
                        }
                        startActivity(intent);
                        finish();
                    }, MIN_DISPLAY_MS);
                } catch (Exception e) {
                    handler.postDelayed(() -> {
                        if (finishing) return;
                        startActivity(new Intent(SplashActivity.this, BookshelfActivity.class));
                        finish();
                    }, MIN_DISPLAY_MS);
                }
            });
        } else {
            handler.postDelayed(() -> {
                if (finishing) return;
                startActivity(new Intent(SplashActivity.this, BookshelfActivity.class));
                finish();
            }, MIN_DISPLAY_MS);
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
