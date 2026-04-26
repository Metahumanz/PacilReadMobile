package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;

import com.metahumanz.pacilread.theme.ThemedActivity;

public class SettingsActivity extends ThemedActivity {
    public static final String EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION =
            "com.metahumanz.pacilread.EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION";

    private SettingsScreenController settingsController;
    private boolean homeBottomNavigationTransition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        homeBottomNavigationTransition = getIntent().getBooleanExtra(EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION, false);
        if (homeBottomNavigationTransition) {
            overridePendingTransition(R.anim.activity_home_settings_enter, R.anim.activity_home_settings_under_exit);
        } else {
            overridePendingTransition(R.anim.activity_slide_forward, R.anim.activity_recede);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsController = new SettingsScreenController(this, new SettingsScreenController.Host() {
            @Override
            public void openBookPicker(Intent intent, int requestCode) {
                startActivityForResult(intent, requestCode);
            }

            @Override
            public void openReader(long bookId) {
                Intent intent = new Intent(SettingsActivity.this, ReaderActivity.class);
                intent.putExtra("book_id", bookId);
                startActivity(intent);
            }

            @Override
            public void onSettingsSaved() {
            }

            @Override
            public void onThemeChanged() {
                recreate();
            }
        });

        ImageButton backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> onBackPressed());
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsController != null) {
            settingsController.saveSettings();
        }
        super.onBackPressed();
        if (homeBottomNavigationTransition) {
            overridePendingTransition(R.anim.activity_home_settings_under_enter, R.anim.activity_home_settings_exit);
        } else {
            overridePendingTransition(R.anim.activity_return_from_recede, R.anim.activity_slide_backward);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settingsController != null) {
            settingsController.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (settingsController != null) {
            settingsController.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (settingsController != null) {
            settingsController.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SettingsScreenController.REQUEST_PICK_BOOK
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null
                || settingsController == null) {
            return;
        }
        settingsController.onBookPicked(data.getData());
    }
}
