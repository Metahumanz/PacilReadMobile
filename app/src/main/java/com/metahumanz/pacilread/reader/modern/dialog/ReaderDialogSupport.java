package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
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
