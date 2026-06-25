package com.metahumanz.pacilread.reader.modern

import android.widget.Toast
import androidx.annotation.ColorRes
import com.metahumanz.pacilread.theme.ThemeModeHelper
import kotlin.math.roundToInt

class ReaderUiUtils(private val activity: ModernReaderActivity) {
    fun themeColor(@ColorRes resId: Int): Int = ThemeModeHelper.resolveColor(activity, resId)

    fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value).roundToInt()

    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    fun showToast(text: String?) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
