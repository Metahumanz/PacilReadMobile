package com.metahumanz.pacilread.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import com.metahumanz.pacilread.R

object GlassUiHelper {
    private val GLASS_DRAWABLES = intArrayOf(
        R.drawable.bg_card,
        R.drawable.bg_soft_button,
        R.drawable.bg_outline_button,
        R.drawable.bg_outline_button_light,
        R.drawable.bg_app_card,
        R.drawable.bg_app_soft_button,
        R.drawable.bg_app_outline_button,
        R.drawable.bg_app_outline_button_light,
        R.drawable.bg_input,
        R.drawable.bg_app_input,
        R.drawable.bg_menu_panel,
        R.drawable.bg_sidebar_panel,
        R.drawable.bg_nav_item_idle,
        R.drawable.bg_reader_hud_pill,
        R.drawable.bg_reader_menu_button,
    )

    @JvmStatic
    fun applyToHierarchy(context: Context, root: View?, opacityPercent: Int) {
        if (root == null) return
        applyRecursive(root, resolveGlassStates(context), clampPercent(opacityPercent))
    }

    @JvmStatic
    fun applyToView(context: Context, view: View?, opacityPercent: Int) {
        if (view == null) return
        applyBackground(view, resolveGlassStates(context), clampPercent(opacityPercent))
    }

    @JvmStatic
    fun clampPercent(percent: Int): Int = percent.coerceIn(20, 100)

    @JvmStatic
    fun alphaFromPercent(percent: Int): Int = Math.round(clampPercent(percent) * 255f / 100f)

    private fun applyRecursive(view: View, states: List<Drawable.ConstantState>, opacityPercent: Int) {
        applyBackground(view, states, opacityPercent)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) applyRecursive(view.getChildAt(index), states, opacityPercent)
    }

    private fun applyBackground(view: View, states: List<Drawable.ConstantState>, opacityPercent: Int) {
        val background = view.background ?: return
        val tagged = view.getTag(R.id.tag_glass_background) == true
        val state = background.constantState
        if (!tagged && (state == null || !matches(states, state))) return
        view.setTag(R.id.tag_glass_background, true)
        val mutated = background.mutate()
        mutated.alpha = alphaFromPercent(opacityPercent)
        view.background = mutated
    }

    private fun matches(states: List<Drawable.ConstantState>, candidate: Drawable.ConstantState): Boolean {
        for (state in states) if (state == candidate) return true
        return false
    }

    private fun resolveGlassStates(context: Context): List<Drawable.ConstantState> {
        val states = ArrayList<Drawable.ConstantState>(GLASS_DRAWABLES.size)
        for (drawableRes in GLASS_DRAWABLES) {
            val state = AppCompatResources.getDrawable(context, drawableRes)?.constantState
            if (state != null) states.add(state)
        }
        return states
    }
}
