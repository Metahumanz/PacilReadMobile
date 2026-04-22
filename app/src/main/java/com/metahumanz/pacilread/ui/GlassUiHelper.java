package com.metahumanz.pacilread.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.content.res.AppCompatResources;

import com.metahumanz.pacilread.R;

import java.util.ArrayList;
import java.util.List;

public final class GlassUiHelper {
    private static final int[] GLASS_DRAWABLES = new int[]{
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
            R.drawable.bg_reader_menu_button
    };

    private GlassUiHelper() {
    }

    public static void applyToHierarchy(Context context, View root, int opacityPercent) {
        if (root == null) {
            return;
        }
        List<Drawable.ConstantState> states = resolveGlassStates(context);
        applyRecursive(root, states, clampPercent(opacityPercent));
    }

    public static void applyToView(Context context, View view, int opacityPercent) {
        if (view == null) {
            return;
        }
        applyBackground(view, resolveGlassStates(context), clampPercent(opacityPercent));
    }

    public static int clampPercent(int percent) {
        return Math.max(20, Math.min(100, percent));
    }

    public static int alphaFromPercent(int percent) {
        return Math.round(clampPercent(percent) * 255f / 100f);
    }

    private static void applyRecursive(View view, List<Drawable.ConstantState> states, int opacityPercent) {
        applyBackground(view, states, opacityPercent);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applyRecursive(group.getChildAt(index), states, opacityPercent);
        }
    }

    private static void applyBackground(View view, List<Drawable.ConstantState> states, int opacityPercent) {
        Drawable background = view.getBackground();
        if (background == null) {
            return;
        }
        boolean tagged = Boolean.TRUE.equals(view.getTag(R.id.tag_glass_background));
        Drawable.ConstantState state = background.getConstantState();
        if (!tagged && (state == null || !matches(states, state))) {
            return;
        }
        view.setTag(R.id.tag_glass_background, Boolean.TRUE);
        Drawable mutated = background.mutate();
        mutated.setAlpha(alphaFromPercent(opacityPercent));
        view.setBackground(mutated);
    }

    private static boolean matches(List<Drawable.ConstantState> states, Drawable.ConstantState candidate) {
        for (Drawable.ConstantState state : states) {
            if (state != null && state.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<Drawable.ConstantState> resolveGlassStates(Context context) {
        List<Drawable.ConstantState> states = new ArrayList<>(GLASS_DRAWABLES.length);
        for (int drawableRes : GLASS_DRAWABLES) {
            Drawable drawable = AppCompatResources.getDrawable(context, drawableRes);
            if (drawable != null && drawable.getConstantState() != null) {
                states.add(drawable.getConstantState());
            }
        }
        return states;
    }
}
