package com.metahumanz.pacilread.ui;

import android.os.Build;

import com.metahumanz.pacilread.storage.SettingsStore;

/**
 * 统一处理转场动效模式的平台能力判断，避免各页面重复写 SDK 版本检查。
 */
public final class TransitionMotionModeHelper {

    public static final String MODE_FLUID = "fluid";
    public static final String MODE_SIMPLE = "simple";

    private TransitionMotionModeHelper() {
    }

    /** 流动动效依赖 Android 14+ (API 34) 的返回手势进度支持。 */
    public static boolean isFluidAvailable() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /** 根据设备能力和用户偏好解析当前实际模式。Android < 14 时始终返回 {@value #MODE_SIMPLE}。 */
    public static String resolveMode(SettingsStore store) {
        if (!isFluidAvailable()) {
            return MODE_SIMPLE;
        }
        String mode = store != null ? store.getTransitionMotionMode() : MODE_FLUID;
        return MODE_SIMPLE.equals(mode) ? MODE_SIMPLE : MODE_FLUID;
    }

    /** 当前是否为流动模式（来源贴合 + 预测返回动效）。 */
    public static boolean isFluidMode(SettingsStore store) {
        return MODE_FLUID.equals(resolveMode(store));
    }
}
