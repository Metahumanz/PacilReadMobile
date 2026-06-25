package com.metahumanz.pacilread

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.metahumanz.pacilread.theme.ThemeModeHelper

object AppUiUtils {
    @JvmStatic
    fun dp(context: Context, value: Int): Int = Math.round(context.resources.displayMetrics.density * value)

    @JvmStatic
    fun showToast(context: Context, text: String?) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    @JvmStatic
    fun isMotionEventInsideView(view: View?, event: MotionEvent?): Boolean {
        if (view == null || event == null || view.visibility != View.VISIBLE) return false
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.contains(Math.round(event.rawX), Math.round(event.rawY))
    }

    @JvmStatic
    fun styleSelectionButton(context: Context, button: Button?, selected: Boolean) {
        if (button == null) return
        button.setBackgroundResource(if (selected) R.drawable.bg_app_primary_button else R.drawable.bg_app_outline_button)
        button.setTextColor(
            ThemeModeHelper.resolveColor(
                context,
                if (selected) R.color.app_button_primary_text else R.color.app_button_outline_text,
            ),
        )
    }

    @JvmStatic
    fun styleToggleButton(context: Context, button: Button?, selected: Boolean) {
        if (button == null) return
        button.isSelected = selected
        styleSelectionButton(context, button, selected)
    }
}
