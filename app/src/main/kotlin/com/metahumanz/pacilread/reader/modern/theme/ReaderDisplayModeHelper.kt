package com.metahumanz.pacilread.reader.modern.theme

import android.content.Context
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemeModeHelper

object ReaderDisplayModeHelper {
    const val DOUBLE_PAGE_LANDSCAPE = "landscape"
    const val DOUBLE_PAGE_ALWAYS = "always"
    const val DOUBLE_PAGE_LANDSCAPE_OR_TABLET = "landscape_or_tablet"
    const val AUTO_NIGHT_POLICY_ASK = "ask"
    const val AUTO_NIGHT_POLICY_OVERRIDE = "override"
    const val AUTO_NIGHT_POLICY_PRESERVE = "preserve"
    private const val MIN_DOUBLE_PAGE_VIEWPORT_WIDTH_DP = 720
    private const val MIN_DOUBLE_PAGE_COLUMN_WIDTH_DP = 260
    private const val DEFAULT_PAGE_HORIZONTAL_PADDING_DP = 18
    private const val DEFAULT_DOUBLE_PAGE_GUTTER_DP = 22

    @JvmStatic
    fun isDoublePageActive(context: Context?, settingsStore: SettingsStore?, widthPx: Int, heightPx: Int): Boolean {
        if (settingsStore == null || !settingsStore.isReaderDoublePageEnabled) return false
        val viewportWidthPx = resolveViewportWidthPx(context, widthPx)
        val viewportHeightPx = resolveViewportHeightPx(context, heightPx)
        val landscape = viewportWidthPx > 0 && viewportHeightPx > 0 && viewportWidthPx > viewportHeightPx
        val requested = when (settingsStore.readerDoublePageMode) {
            DOUBLE_PAGE_ALWAYS -> true
            DOUBLE_PAGE_LANDSCAPE_OR_TABLET -> landscape || isTablet(context)
            else -> landscape
        }
        return requested && !isCompactReaderViewport(context, settingsStore, viewportWidthPx, viewportHeightPx)
    }

    @JvmStatic
    fun isCompactReaderViewport(context: Context?, settingsStore: SettingsStore?, widthPx: Int, heightPx: Int): Boolean {
        val viewportWidthPx = resolveViewportWidthPx(context, widthPx)
        if (viewportWidthPx <= 0) return false
        if (viewportWidthPx < dpToPx(context, MIN_DOUBLE_PAGE_VIEWPORT_WIDTH_DP)) return true
        val columnWidthPx = estimateDoublePageColumnWidthPx(context, settingsStore, viewportWidthPx)
        return columnWidthPx > 0 && columnWidthPx < dpToPx(context, MIN_DOUBLE_PAGE_COLUMN_WIDTH_DP)
    }

    @JvmStatic
    fun isPhoneViewport(context: Context?, widthPx: Int, heightPx: Int): Boolean =
        resolveSmallestViewportWidthDp(context, widthPx, heightPx).let { it > 0 && it < 600 }

    @JvmStatic
    fun isTabletViewport(context: Context?, widthPx: Int, heightPx: Int): Boolean =
        resolveSmallestViewportWidthDp(context, widthPx, heightPx) >= 600

    private fun estimateDoublePageColumnWidthPx(context: Context?, settingsStore: SettingsStore?, viewportWidthPx: Int): Int {
        if (viewportWidthPx <= 0) return 0
        val leftPaddingDp = settingsStore?.leftPaddingDp ?: DEFAULT_PAGE_HORIZONTAL_PADDING_DP
        val rightPaddingDp = settingsStore?.rightPaddingDp ?: DEFAULT_PAGE_HORIZONTAL_PADDING_DP
        val contentWidthPx = viewportWidthPx - dpToPx(context, Math.max(0, leftPaddingDp)) -
            dpToPx(context, Math.max(0, rightPaddingDp))
        return Math.max(0, (contentWidthPx - dpToPx(context, DEFAULT_DOUBLE_PAGE_GUTTER_DP)) / 2)
    }

    private fun resolveSmallestViewportWidthDp(context: Context?, widthPx: Int, heightPx: Int): Int {
        val viewportWidthPx = resolveViewportWidthPx(context, widthPx)
        val viewportHeightPx = resolveViewportHeightPx(context, heightPx)
        if (viewportWidthPx > 0 && viewportHeightPx > 0) {
            return Math.round(Math.min(viewportWidthPx, viewportHeightPx) / density(context))
        }
        return context?.resources?.configuration?.smallestScreenWidthDp ?: 0
    }

    private fun resolveViewportWidthPx(context: Context?, widthPx: Int): Int {
        if (widthPx > 0 || context == null) return widthPx
        val valueDp = context.resources.configuration.screenWidthDp
        return if (valueDp > 0) dpToPx(context, valueDp) else 0
    }

    private fun resolveViewportHeightPx(context: Context?, heightPx: Int): Int {
        if (heightPx > 0 || context == null) return heightPx
        val valueDp = context.resources.configuration.screenHeightDp
        return if (valueDp > 0) dpToPx(context, valueDp) else 0
    }

    private fun dpToPx(context: Context?, valueDp: Int): Int = Math.round(valueDp * density(context))
    private fun density(context: Context?): Float = Math.max(context?.resources?.displayMetrics?.density ?: 1f, 1f)

    @JvmStatic
    fun pagesPerScreen(context: Context?, settingsStore: SettingsStore?, widthPx: Int, heightPx: Int): Int =
        if (isDoublePageActive(context, settingsStore, widthPx, heightPx)) 2 else 1

    @JvmStatic
    fun isTablet(context: Context?): Boolean = context?.resources?.configuration?.smallestScreenWidthDp?.let { it >= 600 } ?: false

    @JvmStatic
    fun isReaderDark(context: Context?): Boolean = ThemeModeHelper.MODE_DARK == ThemeModeHelper.getResolvedReaderBucket(context)

    @JvmStatic
    fun isAutoNightActive(context: Context?, settingsStore: SettingsStore?): Boolean = settingsStore != null && isReaderDark(context)

    @JvmStatic
    fun hasCustomReaderVisuals(settingsStore: SettingsStore?): Boolean {
        if (settingsStore == null) return false
        return !settingsStore.readerBackgroundPath.isNullOrBlank() || settingsStore.readerTextColor != "theme_default"
    }

    @JvmStatic
    fun shouldOverrideCustomVisuals(context: Context?, settingsStore: SettingsStore?, sessionPolicy: String?): Boolean =
        isAutoNightActive(context, settingsStore)

    @JvmStatic
    fun resolveReaderThemeKey(context: Context?, settingsStore: SettingsStore?): String =
        if (isAutoNightActive(context, settingsStore)) "night" else settingsStore?.readerTheme ?: "paper"

    @JvmStatic
    fun resolvePalette(context: Context?, settingsStore: SettingsStore?): ReaderThemePalette =
        ReaderThemePalette.from(resolveReaderThemeKey(context, settingsStore))
}
