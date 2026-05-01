package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.ui.ActivityTransitionCompat;
import com.metahumanz.pacilread.ui.PredictiveBackScaleController;
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper;

public class SettingsActivity extends ThemedActivity {
    public static final String EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION =
            "com.metahumanz.pacilread.EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION";

    private SettingsScreenController settingsController;
    private boolean homeBottomNavigationTransition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        homeBottomNavigationTransition = getIntent().getBooleanExtra(EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION, false);
        if (homeBottomNavigationTransition) {
            ActivityTransitionCompat.overrideOpen(this, R.anim.activity_home_settings_enter, R.anim.activity_home_settings_under_exit);
        } else {
            ActivityTransitionCompat.overrideOpen(this, R.anim.activity_slide_forward, R.anim.activity_recede);
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
            public void onLibraryDataRestored() {
            }

            @Override
            public void onThemeChanged() {
                recreate();
            }
        });

        ImageButton backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
        installPredictiveBack();
    }

    private void installPredictiveBack() {
        if (!TransitionMotionModeHelper.isFluidMode(new SettingsStore(this))) {
            return;
        }
        View root = findViewById(R.id.settings_root);
        if (root == null) {
            return;
        }
        PredictiveBackScaleController.install(this, root, PredictiveBackScaleController.Profile.standard(),
                new PredictiveBackScaleController.Delegate() {
                    @Override
                    public boolean shouldAnimateBack() {
                        return true;
                    }

                    @Override
                    public boolean consumeBack() {
                        return false;
                    }

                    @Override
                    public void commitBack() {
                        finishWithSettingsTransition();
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (!TransitionMotionModeHelper.isFluidMode(new SettingsStore(this))) {
            finishWithSettingsTransition();
            return;
        }
        super.onBackPressed();
    }

    private void finishWithSettingsTransition() {
        if (settingsController != null) {
            settingsController.saveSettings();
        }
        finish();
        if (homeBottomNavigationTransition) {
            ActivityTransitionCompat.overrideClose(this, R.anim.activity_home_settings_under_enter, R.anim.activity_home_settings_exit);
        } else {
            ActivityTransitionCompat.overrideClose(this, R.anim.activity_return_from_recede, R.anim.activity_slide_backward);
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
