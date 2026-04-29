package com.metahumanz.pacilread.reader.modern.dialog;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.core.view.WindowCompat;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.GlassUiHelper;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.ui.PredictiveBackScaleController;
import com.metahumanz.pacilread.ui.ScreenCornerClipper;

import java.util.List;

public final class ReaderDialogSupport {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderUiUtils ui;
    private LaunchSourceTransition.Source nextDismissSource;

    public ReaderDialogSupport(ModernReaderActivity activity, ReaderRuntime runtime, ReaderUiUtils ui) {
        this.activity = activity;
        this.runtime = runtime;
        this.ui = ui;
    }

    public ArrayAdapter<String> buildSpinnerAdapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.item_spinner_selected, items);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        return adapter;
    }

    public ArrayAdapter<String> buildDialogListAdapter(List<String> items) {
        return new ArrayAdapter<String>(activity, R.layout.item_dialog_list_row, android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(ui.themeColor(R.color.on_surface));
                return view;
            }
        };
    }

    public void setNextDismissSource(View sourceView) {
        nextDismissSource = LaunchSourceTransition.captureSource(sourceView);
    }

    public void setNextDismissSource(Rect sourceBounds) {
        nextDismissSource = sourceBounds == null ? null : LaunchSourceTransition.sourceFromBounds(sourceBounds);
    }

    public void setNextDismissSource(LaunchSourceTransition.Source source) {
        nextDismissSource = source;
    }

    public void showStyledDialog(AlertDialog dialog) {
        Window window = showDialogWithAnimation(dialog, R.style.ReaderPopDialogAnimation);
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        OnBackInvokedCallback backCallback = installPredictiveDismiss(dialog, window, consumeNextDismissSource());
        dialog.setOnDismissListener(unused -> unregisterPredictiveDismiss(dialog, backCallback));
        GlassUiHelper.applyToHierarchy(activity, dialog.findViewById(android.R.id.content), runtime.settingsStore.getGlassOpacityPercent());
    }

    public void showFullscreenDialog(AlertDialog dialog) {
        Window window = showDialogWithAnimation(dialog, R.style.ReaderFullscreenDialogAnimation);
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        OnBackInvokedCallback backCallback = installPredictiveDismiss(dialog, window, consumeNextDismissSource());
        dialog.setOnDismissListener(unused -> unregisterPredictiveDismiss(dialog, backCallback));
        GlassUiHelper.applyToHierarchy(activity, dialog.findViewById(android.R.id.content), runtime.settingsStore.getGlassOpacityPercent());
    }

    public void applyTocStyleFullscreenInsets(View root, View contentContainer) {
        if (root == null || contentContainer == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int leftInset;
            int topInset;
            int rightInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout());
                boolean landscape = view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                leftInset = landscape ? systemBars.left : Math.max(systemBars.left, cutout.left);
                topInset = Math.max(systemBars.top, cutout.top);
                rightInset = landscape ? systemBars.right : Math.max(systemBars.right, cutout.right);
                bottomInset = Math.max(systemBars.bottom, cutout.bottom);
            } else {
                leftInset = windowInsets.getSystemWindowInsetLeft();
                topInset = windowInsets.getSystemWindowInsetTop();
                rightInset = windowInsets.getSystemWindowInsetRight();
                bottomInset = windowInsets.getSystemWindowInsetBottom();
            }
            contentContainer.setPadding(
                    ui.dp(20) + leftInset,
                    ui.dp(18) + topInset,
                    ui.dp(16) + rightInset,
                    ui.dp(16) + bottomInset
            );
            return windowInsets;
        });
    }

    public void addAlignedCloseButton(
            View contentView,
            int titleViewId,
            View contentContainer,
            AlertDialog dialog
    ) {
        if (!(contentView instanceof FrameLayout) || contentContainer == null || dialog == null) {
            return;
        }
        View titleView = contentView.findViewById(titleViewId);
        if (titleView == null) {
            return;
        }
        FrameLayout root = (FrameLayout) contentView;
        TextView closeButton = new TextView(activity);
        closeButton.setText("×");
        closeButton.setTextSize(20f);
        closeButton.setTextColor(ui.themeColor(R.color.on_surface));
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        closeButton.setContentDescription("关闭");
        closeButton.setOnClickListener(v -> dialog.dismiss());

        int size = ui.dp(48);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.TOP | Gravity.START;
        root.addView(closeButton, params);

        Runnable position = () -> positionAlignedCloseButton(root, titleView, contentContainer, closeButton, size);
        root.post(position);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> position.run());
        titleView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> position.run());
        contentContainer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> position.run());
    }

    private void positionAlignedCloseButton(
            FrameLayout root,
            View titleView,
            View contentContainer,
            View closeButton,
            int size
    ) {
        if (root.getWidth() <= 0 || titleView.getWidth() <= 0 || contentContainer.getWidth() <= 0) {
            return;
        }
        int[] rootLocation = new int[2];
        int[] titleLocation = new int[2];
        int[] containerLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        titleView.getLocationOnScreen(titleLocation);
        contentContainer.getLocationOnScreen(containerLocation);

        int titleCenterY = titleLocation[1] - rootLocation[1] + titleView.getHeight() / 2;
        int contentRight = containerLocation[0] - rootLocation[0]
                + contentContainer.getWidth()
                - contentContainer.getPaddingRight();
        int left = Math.max(0, contentRight - size);
        int top = Math.max(0, titleCenterY - size / 2);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) closeButton.getLayoutParams();
        if (params.leftMargin != left || params.topMargin != top) {
            params.leftMargin = left;
            params.topMargin = top;
            closeButton.setLayoutParams(params);
        }
    }

    public void showImmersiveFullscreenDialog(AlertDialog dialog, boolean restoreShowSystemBars) {
        Window window = showDialogWithAnimation(dialog, R.style.ReaderFullscreenDialogAnimation);
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            configureEdgeToEdgeWindow(window);
            applySystemBarsVisibility(window, false);
        }
        OnBackInvokedCallback backCallback = installPredictiveDismiss(dialog, window, consumeNextDismissSource());
        dialog.setOnDismissListener(unused -> {
            unregisterPredictiveDismiss(dialog, backCallback);
            applySystemBarsVisibility(activity.getWindow(), restoreShowSystemBars);
        });
    }

    private Window showDialogWithAnimation(AlertDialog dialog, int animationStyleResId) {
        applyWindowAnimation(dialog, animationStyleResId);
        dialog.show();
        applyWindowAnimation(dialog, animationStyleResId);
        return dialog.getWindow();
    }

    private void applyWindowAnimation(AlertDialog dialog, int animationStyleResId) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(animationStyleResId);
        }
    }

    private LaunchSourceTransition.Source consumeNextDismissSource() {
        LaunchSourceTransition.Source source = nextDismissSource;
        nextDismissSource = null;
        return source;
    }

    @SuppressLint("NewApi")
    private OnBackInvokedCallback installPredictiveDismiss(AlertDialog dialog, Window window, LaunchSourceTransition.Source dismissSource) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialog == null || window == null) {
            return null;
        }
        if (!com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(runtime.settingsStore)) {
            return null;
        }
        View target = window.getDecorView();
        ScreenCornerClipper.apply(target);
        target.post(() -> {
            target.setPivotX(target.getWidth() / 2f);
            target.setPivotY(target.getHeight() / 2f);
        });
        OnBackAnimationCallback callback = new OnBackAnimationCallback() {
            private boolean dismissing;

            @Override
            public void onBackStarted(BackEvent backEvent) {
                if (dismissing) {
                    return;
                }
                target.animate().cancel();
                applyBackProgress(target, backEvent.getProgress());
            }

            @Override
            public void onBackProgressed(BackEvent backEvent) {
                if (dismissing) {
                    return;
                }
                applyBackProgress(target, backEvent.getProgress());
            }

            @Override
            public void onBackCancelled() {
                if (dismissing) {
                    return;
                }
                target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }

            @Override
            public void onBackInvoked() {
                if (dismissing) {
                    return;
                }
                dismissing = true;
                target.animate().cancel();
                if (LaunchSourceTransition.animateExitToSource(
                        target,
                        dismissSource,
                        230L,
                        () -> dismissIfShowing(dialog)
                )) {
                    return;
                }
                target.animate()
                        .scaleX(PredictiveBackScaleController.READER_MIN_SCALE)
                        .scaleY(PredictiveBackScaleController.READER_MIN_SCALE)
                        .alpha(0f)
                        .setDuration(130L)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> dismissIfShowing(dialog))
                        .start();
            }
        };
        dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
        );
        return callback;
    }

    private void dismissIfShowing(AlertDialog dialog) {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    @SuppressLint("NewApi")
    private void unregisterPredictiveDismiss(AlertDialog dialog, OnBackInvokedCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialog == null || callback == null) {
            return;
        }
        try {
            dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void applyBackProgress(View target, float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        float eased = 1f - ((1f - safeProgress) * (1f - safeProgress));
        float scale = 1f + (PredictiveBackScaleController.READER_MIN_SCALE - 1f) * eased;
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setAlpha(1f - (0.04f * eased));
    }

    private void configureEdgeToEdgeWindow(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    private void applySystemBarsVisibility(Window window, boolean showSystemBars) {
        View decorView = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!ThemeModeHelper.isDark(activity.getResources())) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (!showSystemBars) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decorView.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (showSystemBars) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                } else {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        }
    }

    public static final class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final Runnable callback;

        public SimpleSeekListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            callback.run();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
