package com.metahumanz.pacilread.reader.modern;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.SimulationPageTurnView;

public final class ReaderViewRefs {
    public final View readerRoot;
    public final View hudTopContainer;
    public final View hudBottomContainer;
    public final View menuTopPanel;
    public final View menuInfoPanel;
    public final View menuBottomPanel;
    public final View pageStage;
    public final View pageCurrent;
    public final View pageIncoming;
    public final ImageView pageSnapshotCurrent;
    public final ImageView pageSnapshotIncoming;
    public final SimulationPageTurnView simulationPageTurnView;
    public final View pageShadow;
    public final View pageFoldShadow;
    public final View pageFoldHighlight;
    public final ImageView readerBackgroundImage;
    public final TextView hudTopLeft;
    public final TextView hudTopCenter;
    public final TextView hudTopRight;
    public final TextView hudBottomLeft;
    public final TextView hudBottomCenter;
    public final TextView hudBottomRight;
    public final TextView readerTitle;
    public final TextView readerProgress;
    public final TextView chapterMeta;
    public final TextView pageMeta;
    public final TextView pageTitleCurrent;
    public final JustifiedPageTextView pageBodyCurrent;
    public final TextView pageTitleIncoming;
    public final JustifiedPageTextView pageBodyIncoming;
    public final SeekBar progressSeekBar;
    public final Button ttsButton;
    public final Button autoPageButton;
    public final Button themeToggleButton;

    private ReaderViewRefs(Activity activity) {
        readerRoot = activity.findViewById(R.id.reader_root);
        menuTopPanel = activity.findViewById(R.id.menu_top_panel);
        menuInfoPanel = activity.findViewById(R.id.menu_info_panel);
        menuBottomPanel = activity.findViewById(R.id.menu_bottom_panel);
        pageStage = activity.findViewById(R.id.page_stage);
        pageCurrent = activity.findViewById(R.id.page_current);
        pageIncoming = activity.findViewById(R.id.page_incoming);
        pageSnapshotCurrent = activity.findViewById(R.id.page_snapshot_current);
        pageSnapshotIncoming = activity.findViewById(R.id.page_snapshot_incoming);
        simulationPageTurnView = activity.findViewById(R.id.page_simulation_turn);
        pageShadow = activity.findViewById(R.id.view_page_shadow);
        pageFoldShadow = activity.findViewById(R.id.view_page_fold_shadow);
        pageFoldHighlight = activity.findViewById(R.id.view_page_fold_highlight);
        readerBackgroundImage = activity.findViewById(R.id.reader_background_image);
        hudTopContainer = activity.findViewById(R.id.hud_container_top);
        hudBottomContainer = activity.findViewById(R.id.hud_container_bottom);
        hudTopLeft = activity.findViewById(R.id.text_hud_top_left);
        hudTopCenter = activity.findViewById(R.id.text_hud_top_center);
        hudTopRight = activity.findViewById(R.id.text_hud_top_right);
        hudBottomLeft = activity.findViewById(R.id.text_hud_bottom_left);
        hudBottomCenter = activity.findViewById(R.id.text_hud_bottom_center);
        hudBottomRight = activity.findViewById(R.id.text_hud_bottom_right);
        readerTitle = activity.findViewById(R.id.text_reader_title);
        readerProgress = activity.findViewById(R.id.text_progress);
        chapterMeta = activity.findViewById(R.id.text_chapter_meta);
        pageMeta = activity.findViewById(R.id.text_page_meta);
        pageTitleCurrent = activity.findViewById(R.id.text_page_title_current);
        pageBodyCurrent = activity.findViewById(R.id.text_page_body_current);
        pageTitleIncoming = activity.findViewById(R.id.text_page_title_incoming);
        pageBodyIncoming = activity.findViewById(R.id.text_page_body_incoming);
        progressSeekBar = activity.findViewById(R.id.seek_reader_progress);
        ttsButton = activity.findViewById(R.id.button_tts);
        autoPageButton = activity.findViewById(R.id.button_auto_page);
        themeToggleButton = activity.findViewById(R.id.button_theme_toggle);
    }

    public static ReaderViewRefs bind(Activity activity) {
        return new ReaderViewRefs(activity);
    }
}
