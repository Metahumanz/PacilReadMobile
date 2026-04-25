package com.metahumanz.pacilread;

import android.app.Activity;
import android.view.View;
import android.widget.Button;

import com.metahumanz.pacilread.storage.SettingsStore;

final class SettingsHomeNavigationController {
    interface Callback {
        void onSettingsChanged();
    }

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final Callback callback;
    private final Button homeNavPresetAutoButton;
    private final Button homeNavPresetBottomButton;
    private final Button homeNavPresetSidebarButton;
    private final Button homeNavPresetCustomButton;
    private final View homeNavCustomLayout;
    private final Button homeNavPortraitAutoButton;
    private final Button homeNavPortraitBottomButton;
    private final Button homeNavPortraitSidebarButton;
    private final Button homeNavLandscapeAutoButton;
    private final Button homeNavLandscapeBottomButton;
    private final Button homeNavLandscapeSidebarButton;
    private final Button homeSidebarSlideButton;
    private final Button homeSidebarFixedButton;
    private final Button homeFixedSidebarFullButton;
    private final Button homeFixedSidebarIconsButton;
    private final Button homeNavIconsButton;
    private final Button homeNavTextButton;

    private boolean homeNavCustomExpanded = false;
    private String selectedHomeBottomNavStyle = "icons";
    private String selectedPortraitHomeNavigationMode = "auto";
    private String selectedLandscapeHomeNavigationMode = "auto";
    private String selectedHomeSidebarPresentation = "slide";
    private String selectedHomeFixedSidebarStyle = "full";

    SettingsHomeNavigationController(Activity activity, SettingsStore settingsStore, Callback callback) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.callback = callback;
        this.homeNavPresetAutoButton = activity.findViewById(R.id.button_home_nav_preset_auto);
        this.homeNavPresetBottomButton = activity.findViewById(R.id.button_home_nav_preset_bottom);
        this.homeNavPresetSidebarButton = activity.findViewById(R.id.button_home_nav_preset_sidebar);
        this.homeNavPresetCustomButton = activity.findViewById(R.id.button_home_nav_preset_custom);
        this.homeNavCustomLayout = activity.findViewById(R.id.layout_home_nav_custom);
        this.homeNavPortraitAutoButton = activity.findViewById(R.id.button_home_nav_portrait_auto);
        this.homeNavPortraitBottomButton = activity.findViewById(R.id.button_home_nav_portrait_bottom);
        this.homeNavPortraitSidebarButton = activity.findViewById(R.id.button_home_nav_portrait_sidebar);
        this.homeNavLandscapeAutoButton = activity.findViewById(R.id.button_home_nav_landscape_auto);
        this.homeNavLandscapeBottomButton = activity.findViewById(R.id.button_home_nav_landscape_bottom);
        this.homeNavLandscapeSidebarButton = activity.findViewById(R.id.button_home_nav_landscape_sidebar);
        this.homeSidebarSlideButton = activity.findViewById(R.id.button_home_sidebar_slide);
        this.homeSidebarFixedButton = activity.findViewById(R.id.button_home_sidebar_fixed);
        this.homeFixedSidebarFullButton = activity.findViewById(R.id.button_home_fixed_sidebar_full);
        this.homeFixedSidebarIconsButton = activity.findViewById(R.id.button_home_fixed_sidebar_icons);
        this.homeNavIconsButton = activity.findViewById(R.id.button_home_nav_icons);
        this.homeNavTextButton = activity.findViewById(R.id.button_home_nav_text);
        setupControls();
    }

    void bindValues() {
        selectedHomeBottomNavStyle = settingsStore.getHomeBottomNavStyle();
        selectedPortraitHomeNavigationMode = settingsStore.getPortraitHomeNavigationMode();
        selectedLandscapeHomeNavigationMode = settingsStore.getLandscapeHomeNavigationMode();
        selectedHomeSidebarPresentation = settingsStore.getHomeSidebarPresentation();
        selectedHomeFixedSidebarStyle = settingsStore.getHomeFixedSidebarStyle();
        homeNavCustomExpanded = !selectedPortraitHomeNavigationMode.equals(selectedLandscapeHomeNavigationMode);
        updateHomeNavigationModeButtons();
        updateHomeNavStyleButtons();
    }

    void saveValues() {
        settingsStore.setHomeBottomNavStyle(selectedHomeBottomNavStyle);
        settingsStore.setPortraitHomeNavigationMode(selectedPortraitHomeNavigationMode);
        settingsStore.setLandscapeHomeNavigationMode(selectedLandscapeHomeNavigationMode);
        settingsStore.setHomeSidebarPresentation(selectedHomeSidebarPresentation);
        settingsStore.setHomeFixedSidebarStyle(selectedHomeFixedSidebarStyle);
    }

    private void setupControls() {
        if (homeNavPresetAutoButton != null) {
            homeNavPresetAutoButton.setOnClickListener(v -> selectHomeNavPreset("auto"));
        }
        if (homeNavPresetBottomButton != null) {
            homeNavPresetBottomButton.setOnClickListener(v -> selectHomeNavPreset("bottom"));
        }
        if (homeNavPresetSidebarButton != null) {
            homeNavPresetSidebarButton.setOnClickListener(v -> selectHomeNavPreset("sidebar"));
        }
        if (homeNavPresetCustomButton != null) {
            homeNavPresetCustomButton.setOnClickListener(v -> {
                homeNavCustomExpanded = true;
                updateHomeNavigationModeButtons();
            });
        }
        if (homeNavPortraitAutoButton != null) {
            homeNavPortraitAutoButton.setOnClickListener(v -> selectHomeNavigationMode(true, "auto"));
        }
        if (homeNavPortraitBottomButton != null) {
            homeNavPortraitBottomButton.setOnClickListener(v -> selectHomeNavigationMode(true, "bottom"));
        }
        if (homeNavPortraitSidebarButton != null) {
            homeNavPortraitSidebarButton.setOnClickListener(v -> selectHomeNavigationMode(true, "sidebar"));
        }
        if (homeNavLandscapeAutoButton != null) {
            homeNavLandscapeAutoButton.setOnClickListener(v -> selectHomeNavigationMode(false, "auto"));
        }
        if (homeNavLandscapeBottomButton != null) {
            homeNavLandscapeBottomButton.setOnClickListener(v -> selectHomeNavigationMode(false, "bottom"));
        }
        if (homeNavLandscapeSidebarButton != null) {
            homeNavLandscapeSidebarButton.setOnClickListener(v -> selectHomeNavigationMode(false, "sidebar"));
        }
        if (homeSidebarSlideButton != null) {
            homeSidebarSlideButton.setOnClickListener(v -> selectHomeSidebarPresentation("slide"));
        }
        if (homeSidebarFixedButton != null) {
            homeSidebarFixedButton.setOnClickListener(v -> selectHomeSidebarPresentation("fixed_wide"));
        }
        if (homeFixedSidebarFullButton != null) {
            homeFixedSidebarFullButton.setOnClickListener(v -> selectHomeFixedSidebarStyle("full"));
        }
        if (homeFixedSidebarIconsButton != null) {
            homeFixedSidebarIconsButton.setOnClickListener(v -> selectHomeFixedSidebarStyle("icons"));
        }
        if (homeNavIconsButton != null) {
            homeNavIconsButton.setOnClickListener(v -> selectHomeBottomNavStyle("icons"));
        }
        if (homeNavTextButton != null) {
            homeNavTextButton.setOnClickListener(v -> selectHomeBottomNavStyle("text"));
        }
    }

    private void selectHomeBottomNavStyle(String style) {
        String normalized = SettingsStore.normalizeHomeBottomNavStyle(style);
        if (normalized.equals(selectedHomeBottomNavStyle)) {
            return;
        }
        selectedHomeBottomNavStyle = normalized;
        updateHomeNavStyleButtons();
        callback.onSettingsChanged();
    }

    private void selectHomeNavPreset(String mode) {
        String normalized = SettingsStore.normalizeHomeNavigationMode(mode);
        selectedPortraitHomeNavigationMode = normalized;
        selectedLandscapeHomeNavigationMode = normalized;
        homeNavCustomExpanded = false;
        updateHomeNavigationModeButtons();
        callback.onSettingsChanged();
    }

    private void selectHomeNavigationMode(boolean portrait, String mode) {
        String normalized = SettingsStore.normalizeHomeNavigationMode(mode);
        if (portrait) {
            selectedPortraitHomeNavigationMode = normalized;
        } else {
            selectedLandscapeHomeNavigationMode = normalized;
        }
        homeNavCustomExpanded = true;
        updateHomeNavigationModeButtons();
        callback.onSettingsChanged();
    }

    private void selectHomeSidebarPresentation(String presentation) {
        String normalized = SettingsStore.normalizeHomeSidebarPresentation(presentation);
        if (normalized.equals(selectedHomeSidebarPresentation)) {
            return;
        }
        selectedHomeSidebarPresentation = normalized;
        updateHomeNavigationModeButtons();
        callback.onSettingsChanged();
    }

    private void selectHomeFixedSidebarStyle(String style) {
        String normalized = SettingsStore.normalizeHomeFixedSidebarStyle(style);
        if (normalized.equals(selectedHomeFixedSidebarStyle)) {
            return;
        }
        selectedHomeFixedSidebarStyle = normalized;
        updateHomeNavigationModeButtons();
        callback.onSettingsChanged();
    }

    private void updateHomeNavStyleButtons() {
        AppUiUtils.styleToggleButton(activity, homeNavIconsButton, "icons".equals(selectedHomeBottomNavStyle));
        AppUiUtils.styleToggleButton(activity, homeNavTextButton, "text".equals(selectedHomeBottomNavStyle));
    }

    private void updateHomeNavigationModeButtons() {
        boolean allAuto = "auto".equals(selectedPortraitHomeNavigationMode)
                && "auto".equals(selectedLandscapeHomeNavigationMode);
        boolean allBottom = "bottom".equals(selectedPortraitHomeNavigationMode)
                && "bottom".equals(selectedLandscapeHomeNavigationMode);
        boolean allSidebar = "sidebar".equals(selectedPortraitHomeNavigationMode)
                && "sidebar".equals(selectedLandscapeHomeNavigationMode);
        boolean custom = homeNavCustomExpanded || (!allAuto && !allBottom && !allSidebar);
        AppUiUtils.styleToggleButton(activity, homeNavPresetAutoButton, allAuto && !custom);
        AppUiUtils.styleToggleButton(activity, homeNavPresetBottomButton, allBottom && !custom);
        AppUiUtils.styleToggleButton(activity, homeNavPresetSidebarButton, allSidebar && !custom);
        AppUiUtils.styleToggleButton(activity, homeNavPresetCustomButton, custom);
        if (homeNavCustomLayout != null) {
            homeNavCustomLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        }
        AppUiUtils.styleToggleButton(activity, homeNavPortraitAutoButton, "auto".equals(selectedPortraitHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeNavPortraitBottomButton, "bottom".equals(selectedPortraitHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeNavPortraitSidebarButton, "sidebar".equals(selectedPortraitHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeAutoButton, "auto".equals(selectedLandscapeHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeBottomButton, "bottom".equals(selectedLandscapeHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeSidebarButton, "sidebar".equals(selectedLandscapeHomeNavigationMode));
        AppUiUtils.styleToggleButton(activity, homeSidebarSlideButton, "slide".equals(selectedHomeSidebarPresentation));
        AppUiUtils.styleToggleButton(activity, homeSidebarFixedButton, "fixed_wide".equals(selectedHomeSidebarPresentation));
        AppUiUtils.styleToggleButton(activity, homeFixedSidebarFullButton, "full".equals(selectedHomeFixedSidebarStyle));
        AppUiUtils.styleToggleButton(activity, homeFixedSidebarIconsButton, "icons".equals(selectedHomeFixedSidebarStyle));
    }
}
