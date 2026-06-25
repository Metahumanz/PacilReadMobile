package com.metahumanz.pacilread.ui

import android.os.Build
import com.metahumanz.pacilread.storage.SettingsStore

/** 统一处理转场动效模式的平台能力判断，避免各页面重复写 SDK 版本检查。 */
object TransitionMotionModeHelper {
    const val MODE_FLUID = "fluid"
    const val MODE_SIMPLE = "simple"

    /** 流动动效依赖 Android 14+ (API 34) 的返回手势进度支持。 */
    @JvmStatic
    fun isFluidAvailable(): Boolean = Build.VERSION.SDK_INT >= 34

    /** 根据设备能力和用户偏好解析当前实际模式。Android < 14 时始终返回 [MODE_SIMPLE]。 */
    @JvmStatic
    fun resolveMode(store: SettingsStore?): String {
        if (!isFluidAvailable()) return MODE_SIMPLE
        val mode = store?.transitionMotionMode ?: MODE_FLUID
        return if (mode == MODE_SIMPLE) MODE_SIMPLE else MODE_FLUID
    }

    /** 当前是否为流动模式（来源贴合 + 预测返回动效）。 */
    @JvmStatic
    fun isFluidMode(store: SettingsStore?): Boolean = resolveMode(store) == MODE_FLUID
}
