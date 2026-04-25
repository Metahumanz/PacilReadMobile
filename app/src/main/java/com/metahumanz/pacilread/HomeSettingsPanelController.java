package com.metahumanz.pacilread;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.CheckBox;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class HomeSettingsPanelController {
    public interface Callback {
        void onReadingTimeTrackingChanged();
        void onNavigationSettingsChanged();
    }

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final Callback callback;
    private final CheckBox readingTimeTrackingCheck;
    private final Button bottomNavIconsButton;
    private final Button bottomNavTextButton;
    private final Button openFullSettingsButton;
    private boolean bindingValues = false;

    public HomeSettingsPanelController(Activity activity, SettingsStore settingsStore, Callback callback) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.callback = callback;
        this.readingTimeTrackingCheck = activity.findViewById(R.id.check_home_reading_time_tracking);
        this.bottomNavIconsButton = activity.findViewById(R.id.button_home_nav_icons);
        this.bottomNavTextButton = activity.findViewById(R.id.button_home_nav_text);
        this.openFullSettingsButton = activity.findViewById(R.id.button_open_full_settings);
        setupControls();
    }

    public void bindValues() {
        bindingValues = true;
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setChecked(settingsStore.isReadingTimeTrackingEnabled());
        }
        updateBottomNavStyleButtons();
        bindingValues = false;
    }

    private void setupControls() {
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (bindingValues) {
                    return;
                }
                settingsStore.setReadingTimeTrackingEnabled(isChecked);
                callback.onReadingTimeTrackingChanged();
            });
        }
        if (bottomNavIconsButton != null) {
            bottomNavIconsButton.setOnClickListener(v -> selectBottomNavStyle("icons"));
        }
        if (bottomNavTextButton != null) {
            bottomNavTextButton.setOnClickListener(v -> selectBottomNavStyle("text"));
        }
        if (openFullSettingsButton != null) {
            openFullSettingsButton.setOnClickListener(v -> activity.startActivity(new Intent(activity, SettingsActivity.class)));
        }
        bindValues();
    }

    private void selectBottomNavStyle(String style) {
        settingsStore.setHomeBottomNavStyle(style);
        updateBottomNavStyleButtons();
        callback.onNavigationSettingsChanged();
    }

    private void updateBottomNavStyleButtons() {
        styleToggleButton(bottomNavIconsButton, "icons".equals(settingsStore.getHomeBottomNavStyle()));
        styleToggleButton(bottomNavTextButton, "text".equals(settingsStore.getHomeBottomNavStyle()));
    }

    private void styleToggleButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(
                activity,
                selected ? R.color.app_button_primary_text : R.color.app_button_outline_text
        ));
    }
}
