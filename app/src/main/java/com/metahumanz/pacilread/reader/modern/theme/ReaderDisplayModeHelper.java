package com.metahumanz.pacilread.reader.modern.theme;

import android.content.Context;
import android.content.res.Configuration;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

public final class ReaderDisplayModeHelper {
    public static final String DOUBLE_PAGE_LANDSCAPE = "landscape";
    public static final String DOUBLE_PAGE_ALWAYS = "always";
    public static final String DOUBLE_PAGE_LANDSCAPE_OR_TABLET = "landscape_or_tablet";
    private static final int MIN_DOUBLE_PAGE_VIEWPORT_WIDTH_DP = 720;
    private static final int MIN_DOUBLE_PAGE_COLUMN_WIDTH_DP = 260;
    private static final int DEFAULT_PAGE_HORIZONTAL_PADDING_DP = 18;
    private static final int DEFAULT_DOUBLE_PAGE_GUTTER_DP = 22;

    public static final String AUTO_NIGHT_POLICY_ASK = "ask";
    public static final String AUTO_NIGHT_POLICY_OVERRIDE = "override";
    public static final String AUTO_NIGHT_POLICY_PRESERVE = "preserve";

    private ReaderDisplayModeHelper() {
    }

    public static boolean isDoublePageActive(Context context, SettingsStore settingsStore, int widthPx, int heightPx) {
        if (settingsStore == null || !settingsStore.isReaderDoublePageEnabled()) {
            return false;
        }
        int viewportWidthPx = resolveViewportWidthPx(context, widthPx);
        int viewportHeightPx = resolveViewportHeightPx(context, heightPx);
        String mode = settingsStore.getReaderDoublePageMode();
        boolean landscape = viewportWidthPx > 0 && viewportHeightPx > 0 && viewportWidthPx > viewportHeightPx;
        boolean requested;
        if (DOUBLE_PAGE_ALWAYS.equals(mode)) {
            requested = true;
        } else if (DOUBLE_PAGE_LANDSCAPE_OR_TABLET.equals(mode)) {
            requested = landscape || isTablet(context);
        } else {
            requested = landscape;
        }
        if (!requested) {
            return false;
        }
        return !isCompactReaderViewport(context, settingsStore, viewportWidthPx, viewportHeightPx);
    }

    public static boolean isCompactReaderViewport(
            Context context,
            SettingsStore settingsStore,
            int widthPx,
            int heightPx
    ) {
        int viewportWidthPx = resolveViewportWidthPx(context, widthPx);
        if (viewportWidthPx <= 0) {
            return false;
        }
        if (viewportWidthPx < dpToPx(context, MIN_DOUBLE_PAGE_VIEWPORT_WIDTH_DP)) {
            return true;
        }
        int columnWidthPx = estimateDoublePageColumnWidthPx(context, settingsStore, viewportWidthPx);
        return columnWidthPx > 0 && columnWidthPx < dpToPx(context, MIN_DOUBLE_PAGE_COLUMN_WIDTH_DP);
    }

    public static boolean isPhoneViewport(Context context, int widthPx, int heightPx) {
        int smallestWidthDp = resolveSmallestViewportWidthDp(context, widthPx, heightPx);
        return smallestWidthDp > 0 && smallestWidthDp < 600;
    }

    public static boolean isTabletViewport(Context context, int widthPx, int heightPx) {
        return resolveSmallestViewportWidthDp(context, widthPx, heightPx) >= 600;
    }

    private static int estimateDoublePageColumnWidthPx(
            Context context,
            SettingsStore settingsStore,
            int viewportWidthPx
    ) {
        if (viewportWidthPx <= 0) {
            return 0;
        }
        int leftPaddingDp = settingsStore == null
                ? DEFAULT_PAGE_HORIZONTAL_PADDING_DP
                : settingsStore.getLeftPaddingDp();
        int rightPaddingDp = settingsStore == null
                ? DEFAULT_PAGE_HORIZONTAL_PADDING_DP
                : settingsStore.getRightPaddingDp();
        int contentWidthPx = viewportWidthPx
                - dpToPx(context, Math.max(0, leftPaddingDp))
                - dpToPx(context, Math.max(0, rightPaddingDp));
        return Math.max(0, (contentWidthPx - dpToPx(context, DEFAULT_DOUBLE_PAGE_GUTTER_DP)) / 2);
    }

    private static int resolveSmallestViewportWidthDp(Context context, int widthPx, int heightPx) {
        int viewportWidthPx = resolveViewportWidthPx(context, widthPx);
        int viewportHeightPx = resolveViewportHeightPx(context, heightPx);
        if (viewportWidthPx > 0 && viewportHeightPx > 0) {
            return Math.round(Math.min(viewportWidthPx, viewportHeightPx) / density(context));
        }
        if (context == null) {
            return 0;
        }
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.smallestScreenWidthDp;
    }

    private static int resolveViewportWidthPx(Context context, int widthPx) {
        if (widthPx > 0 || context == null) {
            return widthPx;
        }
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.screenWidthDp > 0 ? dpToPx(context, configuration.screenWidthDp) : 0;
    }

    private static int resolveViewportHeightPx(Context context, int heightPx) {
        if (heightPx > 0 || context == null) {
            return heightPx;
        }
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.screenHeightDp > 0 ? dpToPx(context, configuration.screenHeightDp) : 0;
    }

    private static int dpToPx(Context context, int valueDp) {
        return Math.round(valueDp * density(context));
    }

    private static float density(Context context) {
        if (context == null) {
            return 1f;
        }
        return Math.max(context.getResources().getDisplayMetrics().density, 1f);
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
        return isAutoNightActive(context, settingsStore);
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
