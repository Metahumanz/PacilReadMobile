package com.metahumanz.pacilread

import android.app.Activity
import android.view.View
import android.widget.Button
import com.metahumanz.pacilread.storage.SettingsStore

class SettingsHomeNavigationController(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
    private val callback: Callback,
) {
    interface Callback {
        fun onSettingsChanged()
    }

    private val homeNavPresetAutoButton: Button? = activity.findViewById(R.id.button_home_nav_preset_auto)
    private val homeNavPresetBottomButton: Button? = activity.findViewById(R.id.button_home_nav_preset_bottom)
    private val homeNavPresetSidebarButton: Button? = activity.findViewById(R.id.button_home_nav_preset_sidebar)
    private val homeNavPresetCustomButton: Button? = activity.findViewById(R.id.button_home_nav_preset_custom)
    private val homeNavCustomLayout: View? = activity.findViewById(R.id.layout_home_nav_custom)
    private val homeNavPortraitAutoButton: Button? = activity.findViewById(R.id.button_home_nav_portrait_auto)
    private val homeNavPortraitBottomButton: Button? = activity.findViewById(R.id.button_home_nav_portrait_bottom)
    private val homeNavPortraitSidebarButton: Button? = activity.findViewById(R.id.button_home_nav_portrait_sidebar)
    private val homeNavLandscapeAutoButton: Button? = activity.findViewById(R.id.button_home_nav_landscape_auto)
    private val homeNavLandscapeBottomButton: Button? = activity.findViewById(R.id.button_home_nav_landscape_bottom)
    private val homeNavLandscapeSidebarButton: Button? = activity.findViewById(R.id.button_home_nav_landscape_sidebar)
    private val homeSidebarSlideButton: Button? = activity.findViewById(R.id.button_home_sidebar_slide)
    private val homeSidebarFixedButton: Button? = activity.findViewById(R.id.button_home_sidebar_fixed)
    private val homeFixedSidebarFullButton: Button? = activity.findViewById(R.id.button_home_fixed_sidebar_full)
    private val homeFixedSidebarIconsButton: Button? = activity.findViewById(R.id.button_home_fixed_sidebar_icons)
    private val homeNavIconsButton: Button? = activity.findViewById(R.id.button_home_nav_icons)
    private val homeNavTextButton: Button? = activity.findViewById(R.id.button_home_nav_text)
    private var homeNavCustomExpanded = false
    private var selectedHomeBottomNavStyle = "icons"
    private var selectedPortraitHomeNavigationMode = "auto"
    private var selectedLandscapeHomeNavigationMode = "auto"
    private var selectedHomeSidebarPresentation = "slide"
    private var selectedHomeFixedSidebarStyle = "full"

    init {
        setupControls()
    }

    fun bindValues() {
        selectedHomeBottomNavStyle = settingsStore.homeBottomNavStyle
        selectedPortraitHomeNavigationMode = settingsStore.portraitHomeNavigationMode
        selectedLandscapeHomeNavigationMode = settingsStore.landscapeHomeNavigationMode
        selectedHomeSidebarPresentation = settingsStore.homeSidebarPresentation
        selectedHomeFixedSidebarStyle = settingsStore.homeFixedSidebarStyle
        homeNavCustomExpanded = selectedPortraitHomeNavigationMode != selectedLandscapeHomeNavigationMode
        updateHomeNavigationModeButtons()
        updateHomeNavStyleButtons()
    }

    fun saveValues() {
        settingsStore.homeBottomNavStyle = selectedHomeBottomNavStyle
        settingsStore.portraitHomeNavigationMode = selectedPortraitHomeNavigationMode
        settingsStore.landscapeHomeNavigationMode = selectedLandscapeHomeNavigationMode
        settingsStore.homeSidebarPresentation = selectedHomeSidebarPresentation
        settingsStore.homeFixedSidebarStyle = selectedHomeFixedSidebarStyle
    }

    private fun setupControls() {
        homeNavPresetAutoButton?.setOnClickListener { selectHomeNavPreset("auto") }
        homeNavPresetBottomButton?.setOnClickListener { selectHomeNavPreset("bottom") }
        homeNavPresetSidebarButton?.setOnClickListener { selectHomeNavPreset("sidebar") }
        homeNavPresetCustomButton?.setOnClickListener {
            homeNavCustomExpanded = true
            updateHomeNavigationModeButtons()
        }
        homeNavPortraitAutoButton?.setOnClickListener { selectHomeNavigationMode(true, "auto") }
        homeNavPortraitBottomButton?.setOnClickListener { selectHomeNavigationMode(true, "bottom") }
        homeNavPortraitSidebarButton?.setOnClickListener { selectHomeNavigationMode(true, "sidebar") }
        homeNavLandscapeAutoButton?.setOnClickListener { selectHomeNavigationMode(false, "auto") }
        homeNavLandscapeBottomButton?.setOnClickListener { selectHomeNavigationMode(false, "bottom") }
        homeNavLandscapeSidebarButton?.setOnClickListener { selectHomeNavigationMode(false, "sidebar") }
        homeSidebarSlideButton?.setOnClickListener { selectHomeSidebarPresentation("slide") }
        homeSidebarFixedButton?.setOnClickListener { selectHomeSidebarPresentation("fixed_wide") }
        homeFixedSidebarFullButton?.setOnClickListener { selectHomeFixedSidebarStyle("full") }
        homeFixedSidebarIconsButton?.setOnClickListener { selectHomeFixedSidebarStyle("icons") }
        homeNavIconsButton?.setOnClickListener { selectHomeBottomNavStyle("icons") }
        homeNavTextButton?.setOnClickListener { selectHomeBottomNavStyle("text") }
    }

    private fun selectHomeBottomNavStyle(style: String) {
        val normalized = SettingsStore.normalizeHomeBottomNavStyle(style)
        if (normalized == selectedHomeBottomNavStyle) return
        selectedHomeBottomNavStyle = normalized
        updateHomeNavStyleButtons()
        callback.onSettingsChanged()
    }

    private fun selectHomeNavPreset(mode: String) {
        val normalized = SettingsStore.normalizeHomeNavigationMode(mode)
        selectedPortraitHomeNavigationMode = normalized
        selectedLandscapeHomeNavigationMode = normalized
        homeNavCustomExpanded = false
        updateHomeNavigationModeButtons()
        callback.onSettingsChanged()
    }

    private fun selectHomeNavigationMode(portrait: Boolean, mode: String) {
        val normalized = SettingsStore.normalizeHomeNavigationMode(mode)
        if (portrait) selectedPortraitHomeNavigationMode = normalized else selectedLandscapeHomeNavigationMode = normalized
        homeNavCustomExpanded = true
        updateHomeNavigationModeButtons()
        callback.onSettingsChanged()
    }

    private fun selectHomeSidebarPresentation(presentation: String) {
        val normalized = SettingsStore.normalizeHomeSidebarPresentation(presentation)
        if (normalized == selectedHomeSidebarPresentation) return
        selectedHomeSidebarPresentation = normalized
        updateHomeNavigationModeButtons()
        callback.onSettingsChanged()
    }

    private fun selectHomeFixedSidebarStyle(style: String) {
        val normalized = SettingsStore.normalizeHomeFixedSidebarStyle(style)
        if (normalized == selectedHomeFixedSidebarStyle) return
        selectedHomeFixedSidebarStyle = normalized
        updateHomeNavigationModeButtons()
        callback.onSettingsChanged()
    }

    private fun updateHomeNavStyleButtons() {
        AppUiUtils.styleToggleButton(activity, homeNavIconsButton, selectedHomeBottomNavStyle == "icons")
        AppUiUtils.styleToggleButton(activity, homeNavTextButton, selectedHomeBottomNavStyle == "text")
    }

    private fun updateHomeNavigationModeButtons() {
        val allAuto = selectedPortraitHomeNavigationMode == "auto" && selectedLandscapeHomeNavigationMode == "auto"
        val allBottom = selectedPortraitHomeNavigationMode == "bottom" && selectedLandscapeHomeNavigationMode == "bottom"
        val allSidebar = selectedPortraitHomeNavigationMode == "sidebar" && selectedLandscapeHomeNavigationMode == "sidebar"
        val custom = homeNavCustomExpanded || !allAuto && !allBottom && !allSidebar
        AppUiUtils.styleToggleButton(activity, homeNavPresetAutoButton, allAuto && !custom)
        AppUiUtils.styleToggleButton(activity, homeNavPresetBottomButton, allBottom && !custom)
        AppUiUtils.styleToggleButton(activity, homeNavPresetSidebarButton, allSidebar && !custom)
        AppUiUtils.styleToggleButton(activity, homeNavPresetCustomButton, custom)
        homeNavCustomLayout?.visibility = if (custom) View.VISIBLE else View.GONE
        AppUiUtils.styleToggleButton(activity, homeNavPortraitAutoButton, selectedPortraitHomeNavigationMode == "auto")
        AppUiUtils.styleToggleButton(activity, homeNavPortraitBottomButton, selectedPortraitHomeNavigationMode == "bottom")
        AppUiUtils.styleToggleButton(activity, homeNavPortraitSidebarButton, selectedPortraitHomeNavigationMode == "sidebar")
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeAutoButton, selectedLandscapeHomeNavigationMode == "auto")
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeBottomButton, selectedLandscapeHomeNavigationMode == "bottom")
        AppUiUtils.styleToggleButton(activity, homeNavLandscapeSidebarButton, selectedLandscapeHomeNavigationMode == "sidebar")
        AppUiUtils.styleToggleButton(activity, homeSidebarSlideButton, selectedHomeSidebarPresentation == "slide")
        AppUiUtils.styleToggleButton(activity, homeSidebarFixedButton, selectedHomeSidebarPresentation == "fixed_wide")
        AppUiUtils.styleToggleButton(activity, homeFixedSidebarFullButton, selectedHomeFixedSidebarStyle == "full")
        AppUiUtils.styleToggleButton(activity, homeFixedSidebarIconsButton, selectedHomeFixedSidebarStyle == "icons")
    }
}
