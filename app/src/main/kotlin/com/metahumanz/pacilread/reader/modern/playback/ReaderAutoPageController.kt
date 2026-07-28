package com.metahumanz.pacilread.reader.modern.playback

import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import java.util.Locale

class ReaderAutoPageController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    @Suppress("UNUSED_PARAMETER") ui: ReaderUiUtils,
    private val dialogSupport: ReaderDialogSupport,
) {
    private val autoPageRunnable = Runnable { onAutoPageTick() }
    private var navigation: ReaderNavigationController? = null
    private var chrome: ReaderChromeController? = null

    fun attachControllers(navigation: ReaderNavigationController?, chrome: ReaderChromeController?) {
        this.navigation = navigation
        this.chrome = chrome
    }

    fun isActive(): Boolean = state.autoPageActive

    fun stopAutoPage() {
        state.autoPageActive = false
        runtime.mainHandler.removeCallbacks(autoPageRunnable)
        chrome!!.styleReaderMenuButton(views.autoPageButton, false)
    }

    fun startAutoPage() {
        state.autoPageActive = true
        chrome!!.styleReaderMenuButton(views.autoPageButton, true)
        scheduleNextAutoPageTick()
    }

    fun scheduleNextAutoPageTick() {
        runtime.mainHandler.removeCallbacks(autoPageRunnable)
        if (!state.autoPageActive) return
        runtime.mainHandler.postDelayed(autoPageRunnable, runtime.settingsStore.autoPageSeconds * 1000L)
    }

    fun showAutoPageDialog() {
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_auto_page, null, false)
        val seekBar = content.findViewById<SeekBar>(R.id.auto_page_seek)
        val valueText = content.findViewById<TextView>(R.id.auto_page_value)
        val toggleButton = content.findViewById<Button>(R.id.auto_page_button_toggle)
        seekBar.progress = runtime.settingsStore.autoPageSeconds - 1
        valueText.text = String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", runtime.settingsStore.autoPageSeconds)
        seekBar.setOnSeekBarChangeListener(ReaderDialogSupport.SimpleSeekListener(Runnable {
            val seconds = seekBar.progress + 1
            valueText.text = String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seconds)
            runtime.settingsStore.autoPageSeconds = seconds
            if (state.autoPageActive) scheduleNextAutoPageTick()
        }))
        toggleButton.text = if (state.autoPageActive) "停止自动翻页" else "开始自动翻页"
        val dialog = AlertDialog.Builder(activity).setView(content).create()
        toggleButton.setOnClickListener {
            if (state.autoPageActive) stopAutoPage() else startAutoPage()
            dialog.dismiss()
        }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun onAutoPageTick() {
        if (!state.autoPageActive) return
        if (!state.controlsVisible && !state.controlsTransitionActive && !state.isAnimating && !state.interactivePaging) {
            navigation!!.pageDown()
        }
        scheduleNextAutoPageTick()
    }
}
