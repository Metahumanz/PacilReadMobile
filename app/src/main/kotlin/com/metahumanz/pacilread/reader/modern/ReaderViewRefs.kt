package com.metahumanz.pacilread.reader.modern

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.JustifiedPageTextView
import com.metahumanz.pacilread.reader.SimulationPageTurnView

class ReaderViewRefs private constructor(activity: Activity) {
    @JvmField val readerRoot: View = activity.findViewById(R.id.reader_root)
    @JvmField val menuTopPanel: View = activity.findViewById(R.id.menu_top_panel)
    @JvmField val menuInfoPanel: View = activity.findViewById(R.id.menu_info_panel)
    @JvmField val menuBottomPanel: View = activity.findViewById(R.id.menu_bottom_panel)
    @JvmField val pageStage: View = activity.findViewById(R.id.page_stage)
    @JvmField val pageCurrent: View = activity.findViewById(R.id.page_current)
    @JvmField val pageIncoming: View = activity.findViewById(R.id.page_incoming)
    @JvmField val pageCurrentLeftPane: View = activity.findViewById(R.id.page_current_left_pane)
    @JvmField val pageCurrentRightPane: View = activity.findViewById(R.id.page_current_right_pane)
    @JvmField val pageCurrentGutter: View = activity.findViewById(R.id.page_current_gutter)
    @JvmField val pageBookSpineOverlay: View = activity.findViewById(R.id.page_book_spine_overlay)
    @JvmField val pageIncomingLeftPane: View = activity.findViewById(R.id.page_incoming_left_pane)
    @JvmField val pageIncomingRightPane: View = activity.findViewById(R.id.page_incoming_right_pane)
    @JvmField val pageIncomingGutter: View = activity.findViewById(R.id.page_incoming_gutter)
    @JvmField val pageSnapshotCurrent: ImageView = activity.findViewById(R.id.page_snapshot_current)
    @JvmField val pageSnapshotIncoming: ImageView = activity.findViewById(R.id.page_snapshot_incoming)
    @JvmField val simulationPageTurnView: SimulationPageTurnView = activity.findViewById(R.id.page_simulation_turn)
    @JvmField val pageShadow: View = activity.findViewById(R.id.view_page_shadow)
    @JvmField val pageFoldShadow: View = activity.findViewById(R.id.view_page_fold_shadow)
    @JvmField val pageFoldHighlight: View = activity.findViewById(R.id.view_page_fold_highlight)
    @JvmField val readerBackgroundImage: ImageView = activity.findViewById(R.id.reader_background_image)
    @JvmField val remoteProgressBanner: View = activity.findViewById(R.id.remote_progress_banner)
    @JvmField val remoteProgressTitle: TextView = activity.findViewById(R.id.text_remote_progress_title)
    @JvmField val remoteProgressDetail: TextView = activity.findViewById(R.id.text_remote_progress_detail)
    @JvmField val keepLocalProgressButton: Button = activity.findViewById(R.id.button_keep_local_progress)
    @JvmField val jumpRemoteProgressButton: Button = activity.findViewById(R.id.button_jump_remote_progress)
    @JvmField val hudTopContainer: View = activity.findViewById(R.id.hud_container_top)
    @JvmField val hudBottomContainer: View = activity.findViewById(R.id.hud_container_bottom)
    @JvmField val hudTopLeft: TextView = activity.findViewById(R.id.text_hud_top_left)
    @JvmField val hudTopCenter: TextView = activity.findViewById(R.id.text_hud_top_center)
    @JvmField val hudTopRight: TextView = activity.findViewById(R.id.text_hud_top_right)
    @JvmField val hudBottomLeft: TextView = activity.findViewById(R.id.text_hud_bottom_left)
    @JvmField val hudBottomCenter: TextView = activity.findViewById(R.id.text_hud_bottom_center)
    @JvmField val hudBottomRight: TextView = activity.findViewById(R.id.text_hud_bottom_right)
    @JvmField val readerTitle: TextView = activity.findViewById(R.id.text_reader_title)
    @JvmField val moreButton: Button = activity.findViewById(R.id.button_more)
    @JvmField val chapterMeta: TextView = activity.findViewById(R.id.text_chapter_meta)
    @JvmField val pageMeta: TextView = activity.findViewById(R.id.text_page_meta)
    @JvmField val pageTitleCurrent: TextView = activity.findViewById(R.id.text_page_title_current)
    @JvmField val pageBodyCurrent: JustifiedPageTextView = activity.findViewById(R.id.text_page_body_current)
    @JvmField val pageTitleCurrentRight: TextView = activity.findViewById(R.id.text_page_title_current_right)
    @JvmField val pageBodyCurrentRight: JustifiedPageTextView = activity.findViewById(R.id.text_page_body_current_right)
    @JvmField val pageTitleIncoming: TextView = activity.findViewById(R.id.text_page_title_incoming)
    @JvmField val pageBodyIncoming: JustifiedPageTextView = activity.findViewById(R.id.text_page_body_incoming)
    @JvmField val pageTitleIncomingRight: TextView = activity.findViewById(R.id.text_page_title_incoming_right)
    @JvmField val pageBodyIncomingRight: JustifiedPageTextView = activity.findViewById(R.id.text_page_body_incoming_right)
    @JvmField val progressSeekBar: SeekBar = activity.findViewById(R.id.seek_reader_progress)
    @JvmField val ttsButton: Button = activity.findViewById(R.id.button_tts)
    @JvmField val autoPageButton: Button = activity.findViewById(R.id.button_auto_page)
    @JvmField val themeToggleButton: Button = activity.findViewById(R.id.button_theme_toggle)
    @JvmField val menuTopActions: View = activity.findViewById(R.id.menu_top_actions)

    companion object {
        @JvmStatic
        fun bind(activity: Activity): ReaderViewRefs = ReaderViewRefs(activity)
    }
}
