package com.metahumanz.pacilread.reader.modern.theme;

import android.content.Context;
import android.content.res.Configuration;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class ReaderDisplayModeHelper {
    public static final String DOUBLE_PAGE_LANDSCAPE = "landscape";
    public static final String DOUBLE_PAGE_ALWAYS = "always";
    public static final String DOUBLE_PAGE_LANDSCAPE_OR_TABLET = "landscape_or_tablet";

    public static final String AUTO_NIGHT_POLICY_ASK = "ask";
    public static final String AUTO_NIGHT_POLICY_OVERRIDE = "override";
    public static final String AUTO_NIGHT_POLICY_PRESERVE = "preserve";

    private ReaderDisplayModeHelper() {
    }

    public static boolean isDoublePageActive(Context context, SettingsStore settingsStore, int widthPx, int heightPx) {
        if (settingsStore == null || !settingsStore.isReaderDoublePageEnabled()) {
            return false;
        }
        String mode = settingsStore.getReaderDoublePageMode();
        if (DOUBLE_PAGE_ALWAYS.equals(mode)) {
            return true;
        }
        boolean landscape = widthPx > 0 && heightPx > 0 && widthPx > heightPx;
        if (DOUBLE_PAGE_LANDSCAPE_OR_TABLET.equals(mode)) {
            return landscape || isTablet(context);
        }
        return landscape;
    }

    public static int pagesPerScreen(Context context, SettingsStore settingsStore, int widthPx, int heightPx) {
        return isDoublePageActive(context, settingsStore, widthPx, heightPx) ? 2 : 1;
    }

    public static boolean isTablet(Context context) {
        if (context == null) {
            return false;
        }
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.smallestScreenWidthDp >= 600;
    }

    public static boolean isReaderDark(Context context) {
        return ThemeModeHelper.MODE_DARK.equals(ThemeModeHelper.getResolvedReaderBucket(context));
    }

    public static boolean isAutoNightActive(Context context, SettingsStore settingsStore) {
        return settingsStore != null
                && settingsStore.isReaderAutoNightEnabled()
                && isReaderDark(context);
    }

    public static boolean hasCustomReaderVisuals(SettingsStore settingsStore) {
        if (settingsStore == null) {
            return false;
        }
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        return (backgroundPath != null && !backgroundPath.isBlank())
                || !"theme_default".equals(settingsStore.getReaderTextColor());
    }

    public static boolean shouldOverrideCustomVisuals(
            Context context,
            SettingsStore settingsStore,
            String sessionPolicy
    ) {
        if (!isAutoNightActive(context, settingsStore)) {
            return false;
        }
        if (!hasCustomReaderVisuals(settingsStore)) {
            return true;
        }
        String policy = sessionPolicy == null || sessionPolicy.isBlank()
                ? settingsStore.getReaderAutoNightCustomPolicy()
                : SettingsStore.normalizeReaderAutoNightCustomPolicy(sessionPolicy);
        return !AUTO_NIGHT_POLICY_PRESERVE.equals(policy);
    }

    public static String resolveReaderThemeKey(Context context, SettingsStore settingsStore) {
        if (isAutoNightActive(context, settingsStore)) {
            return "night";
        }
        return settingsStore == null ? "paper" : settingsStore.getReaderTheme();
    }

    public static ReaderThemePalette resolvePalette(Context context, SettingsStore settingsStore) {
        return ReaderThemePalette.from(resolveReaderThemeKey(context, settingsStore));
    }
}
