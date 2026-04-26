package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.metahumanz.pacilread.theme.ThemedActivity;

public class SplashActivity extends ThemedActivity {

    private static final long MIN_DISPLAY_MS = 600L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
            finish();
            return;
        }

        handler.postDelayed(() -> {
            startActivity(new Intent(this, BookshelfActivity.class));
            finish();
        }, MIN_DISPLAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
