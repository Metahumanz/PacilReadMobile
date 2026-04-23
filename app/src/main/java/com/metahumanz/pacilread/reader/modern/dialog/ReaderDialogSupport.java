package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.WindowCompat;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.GlassUiHelper;

import java.util.List;

public final class ReaderDialogSupport {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderUiUtils ui;

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

    public void showStyledDialog(AlertDialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        GlassUiHelper.applyToHierarchy(activity, dialog.findViewById(android.R.id.content), runtime.settingsStore.getGlassOpacityPercent());
    }

    public void showFullscreenDialog(AlertDialog dialog) {
        showStyledDialog(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
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
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                leftInset = insets.left;
                topInset = insets.top;
                rightInset = insets.right;
                bottomInset = insets.bottom;
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

    public void showImmersiveFullscreenDialog(AlertDialog dialog, boolean restoreShowSystemBars) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            configureEdgeToEdgeWindow(window);
            applySystemBarsVisibility(window, false);
        }
        dialog.setOnDismissListener(unused -> applySystemBarsVisibility(activity.getWindow(), restoreShowSystemBars));
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
