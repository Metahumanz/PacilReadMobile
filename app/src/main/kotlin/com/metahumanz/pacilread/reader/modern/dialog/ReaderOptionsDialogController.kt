package com.metahumanz.pacilread.reader.modern.dialog

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController
import com.metahumanz.pacilread.stats.ReadingStatsUtils

class ReaderOptionsDialogController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val state: ReaderSessionState,
    ui: ReaderUiUtils,
    private val dialogSupport: ReaderDialogSupport,
    private val content: ReaderContentController,
    private val navigation: ReaderNavigationController,
    private val style: ReaderStyleController,
    private val chrome: ReaderChromeController,
) {
    fun showReaderOptionsDialog() {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_reader_options, null, false)
        val refs = OptionsDialogViews.bind(contentView)
        dialogSupport.applyTocStyleFullscreenInsets(contentView, refs.contentContainer)
        refs.titleInput.setText(state.book?.title.orEmpty())
        refs.authorInput.setText(state.book?.author.orEmpty())
        refs.showTitleCheck.isChecked = runtime.settingsStore.isChapterTitleVisible
        refs.persistentActionsCheck.isChecked = runtime.settingsStore.isReaderMenuPersistentActionsEnabled

        refs.flipSpinner.adapter = dialogSupport.buildSpinnerAdapter(arrayOf("覆盖", "平移", "仿真", "滚动", "无动画"))
        refs.flipSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.FLIP_KEYS, runtime.settingsStore.flipMode, 0), false)
        val speedKeys = arrayOf("fast", "medium", "slow")
        refs.flipSpeedSpinner.adapter = dialogSupport.buildSpinnerAdapter(arrayOf("较快", "适中", "较慢"))
        refs.flipSpeedSpinner.setSelection(ReaderOptionCatalog.indexOf(speedKeys, runtime.settingsStore.flipSpeed, 1), false)

        var sliderMode = runtime.settingsStore.readerSliderMode
        var simulationTurnMode = runtime.settingsStore.simulationDoublePageTurnMode
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        dialogSupport.addAlignedCloseButton(contentView, R.id.options_title, refs.contentContainer, dialog)
        chrome.styleThemeButton(refs.sliderBookButton, sliderMode == "book")
        chrome.styleThemeButton(refs.sliderChapterButton, sliderMode == "chapter")
        applySimulationDoublePageTurnModeButtons(refs, simulationTurnMode)
        updateSimulationDoublePageTurnModeVisibility(refs)

        val hudAdapter = dialogSupport.buildSpinnerAdapter(arrayOf(
            "无", "书名", "章节名", "书名 / 章节名", "现在时间", "系统电量", "本章页数进度", "全书进度", "页数及进度", "时间及电量",
        ))
        val hudSpinners = arrayOf(refs.topLeftSpinner, refs.topCenterSpinner, refs.topRightSpinner,
            refs.bottomLeftSpinner, refs.bottomCenterSpinner, refs.bottomRightSpinner)
        val hudValues = arrayOf(runtime.settingsStore.hudTopLeft, runtime.settingsStore.hudTopCenter,
            runtime.settingsStore.hudTopRight, runtime.settingsStore.hudBottomLeft,
            runtime.settingsStore.hudBottomCenter, runtime.settingsStore.hudBottomRight)
        for (i in hudSpinners.indices) {
            hudSpinners[i].adapter = hudAdapter
            hudSpinners[i].setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, hudValues[i], 0), false)
        }
        refs.hudTopMarginSeek.progress = runtime.settingsStore.hudTopMarginDp
        refs.hudBottomMarginSeek.progress = runtime.settingsStore.hudBottomMarginDp
        updateHudMarginLabels(refs)

        val autoApply = Runnable {
            val title = refs.titleInput.text.toString().trim().ifEmpty { "未命名书籍" }
            val author = refs.authorInput.text.toString().trim()
            val anchorOffset = content.currentCharOffset()
            val chapterTitleVisibilityChanged = runtime.settingsStore.isChapterTitleVisible != refs.showTitleCheck.isChecked
            val previousFlipMode = runtime.settingsStore.flipMode
            val previousFlipSpeed = runtime.settingsStore.flipSpeed
            val previousSimulationTurnMode = runtime.settingsStore.simulationDoublePageTurnMode
            val nextFlipMode = ReaderOptionCatalog.FLIP_KEYS[refs.flipSpinner.selectedItemPosition]
            val nextFlipSpeed = speedKeys[refs.flipSpeedSpinner.selectedItemPosition]
            val flipSettingsChanged = previousFlipMode != nextFlipMode || previousFlipSpeed != nextFlipSpeed ||
                previousSimulationTurnMode != simulationTurnMode
            state.book?.apply {
                this.title = title
                this.author = author
                readingStatsKey = ReadingStatsUtils.buildBookIdentity(title, author)
            }
            runtime.settingsStore.flipMode = nextFlipMode
            runtime.settingsStore.flipSpeed = nextFlipSpeed
            runtime.settingsStore.simulationDoublePageTurnMode = simulationTurnMode
            runtime.settingsStore.readerSliderMode = sliderMode
            runtime.settingsStore.isChapterTitleVisible = refs.showTitleCheck.isChecked
            runtime.settingsStore.isReaderMenuPersistentActionsEnabled = refs.persistentActionsCheck.isChecked
            runtime.settingsStore.hudTopLeft = ReaderOptionCatalog.HUD_KEYS[refs.topLeftSpinner.selectedItemPosition]
            runtime.settingsStore.hudTopCenter = ReaderOptionCatalog.HUD_KEYS[refs.topCenterSpinner.selectedItemPosition]
            runtime.settingsStore.hudTopRight = ReaderOptionCatalog.HUD_KEYS[refs.topRightSpinner.selectedItemPosition]
            runtime.settingsStore.hudBottomLeft = ReaderOptionCatalog.HUD_KEYS[refs.bottomLeftSpinner.selectedItemPosition]
            runtime.settingsStore.hudBottomCenter = ReaderOptionCatalog.HUD_KEYS[refs.bottomCenterSpinner.selectedItemPosition]
            runtime.settingsStore.hudBottomRight = ReaderOptionCatalog.HUD_KEYS[refs.bottomRightSpinner.selectedItemPosition]
            runtime.settingsStore.hudTopMarginDp = refs.hudTopMarginSeek.progress
            runtime.settingsStore.hudBottomMarginDp = refs.hudBottomMarginSeek.progress
            when {
                chapterTitleVisibilityChanged -> content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset)
                flipSettingsChanged -> {
                    style.applyReaderSettings()
                    chrome.applyMenuLayoutMode()
                    navigation.refreshPagingPresentationAfterSettingsChange()
                }
                else -> {
                    chrome.updateReaderLayoutInsets()
                    chrome.applyMenuLayoutMode()
                    chrome.updateUiAfterPageChange()
                }
            }
            runtime.safeExecute(Runnable { runtime.databaseHelper.updateBookInfo(state.bookId, title, author) }, "update reader book info")
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = autoApply.run()
        }
        refs.titleInput.addTextChangedListener(textWatcher)
        refs.authorInput.addTextChangedListener(textWatcher)
        refs.showTitleCheck.setOnCheckedChangeListener { _, _ -> autoApply.run() }
        refs.persistentActionsCheck.setOnCheckedChangeListener { _, _ -> autoApply.run() }
        val hudMarginSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateHudMarginLabels(refs)
                if (fromUser) autoApply.run()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        refs.hudTopMarginSeek.setOnSeekBarChangeListener(hudMarginSeekListener)
        refs.hudBottomMarginSeek.setOnSeekBarChangeListener(hudMarginSeekListener)

        val flipListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSimulationDoublePageTurnModeVisibility(refs)
                autoApply.run()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refs.flipSpinner.onItemSelectedListener = flipListener
        refs.flipSpeedSpinner.onItemSelectedListener = flipListener

        var isAdjustingHudSpinners = false
        val hudListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && !isAdjustingHudSpinners) {
                    isAdjustingHudSpinners = true
                    for (spinner in hudSpinners) if (spinner !== parent && spinner.selectedItemPosition == position) {
                        spinner.setSelection(0, false)
                    }
                    isAdjustingHudSpinners = false
                }
                autoApply.run()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        for (spinner in hudSpinners) spinner.onItemSelectedListener = hudListener

        refs.sliderBookButton.setOnClickListener {
            sliderMode = "book"
            chrome.styleThemeButton(refs.sliderBookButton, true)
            chrome.styleThemeButton(refs.sliderChapterButton, false)
            autoApply.run()
        }
        refs.sliderChapterButton.setOnClickListener {
            sliderMode = "chapter"
            chrome.styleThemeButton(refs.sliderBookButton, false)
            chrome.styleThemeButton(refs.sliderChapterButton, true)
            autoApply.run()
        }
        refs.simulationOuterPageButton.setOnClickListener {
            simulationTurnMode = "outerPage"
            applySimulationDoublePageTurnModeButtons(refs, simulationTurnMode)
            autoApply.run()
        }
        refs.simulationSpreadButton.setOnClickListener {
            simulationTurnMode = "spread"
            applySimulationDoublePageTurnModeButtons(refs, simulationTurnMode)
            autoApply.run()
        }
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible)
        contentView.requestApplyInsets()
    }

    private fun updateSimulationDoublePageTurnModeVisibility(refs: OptionsDialogViews) {
        val visible = content.isDoublePageActive() &&
            ReaderOptionCatalog.FLIP_KEYS[refs.flipSpinner.selectedItemPosition] == "simulation"
        val visibility = if (visible) View.VISIBLE else View.GONE
        refs.simulationTurnModeLabel.visibility = visibility
        refs.simulationTurnModeLayout.visibility = visibility
    }

    private fun applySimulationDoublePageTurnModeButtons(refs: OptionsDialogViews, mode: String?) {
        val spread = mode == "spread"
        refs.simulationOuterPageButton.isSelected = !spread
        refs.simulationSpreadButton.isSelected = spread
        chrome.styleThemeButton(refs.simulationOuterPageButton, !spread)
        chrome.styleThemeButton(refs.simulationSpreadButton, spread)
    }

    private fun updateHudMarginLabels(refs: OptionsDialogViews) {
        refs.hudTopMarginValue.text = "${refs.hudTopMarginSeek.progress} dp"
        refs.hudBottomMarginValue.text = "${refs.hudBottomMarginSeek.progress} dp"
    }

    private class OptionsDialogViews private constructor(root: View) {
        val contentContainer: View = root.findViewById(R.id.options_content)
        val titleInput: EditText = root.findViewById(R.id.options_input_title)
        val authorInput: EditText = root.findViewById(R.id.options_input_author)
        val showTitleCheck: CheckBox = root.findViewById(R.id.options_check_show_title)
        val persistentActionsCheck: CheckBox = root.findViewById(R.id.options_check_persistent_actions)
        val flipSpinner: Spinner = root.findViewById(R.id.options_spinner_flip_mode)
        val flipSpeedSpinner: Spinner = root.findViewById(R.id.options_spinner_flip_speed)
        val simulationTurnModeLabel: TextView = root.findViewById(R.id.options_text_simulation_double_page_turn_mode_label)
        val simulationTurnModeLayout: LinearLayout = root.findViewById(R.id.options_layout_simulation_double_page_turn_mode)
        val simulationOuterPageButton: Button = root.findViewById(R.id.options_button_simulation_outer_page)
        val simulationSpreadButton: Button = root.findViewById(R.id.options_button_simulation_spread)
        val sliderBookButton: Button = root.findViewById(R.id.options_button_slider_book)
        val sliderChapterButton: Button = root.findViewById(R.id.options_button_slider_chapter)
        val hudTopMarginSeek: SeekBar = root.findViewById(R.id.options_seek_hud_top_margin)
        val hudBottomMarginSeek: SeekBar = root.findViewById(R.id.options_seek_hud_bottom_margin)
        val hudTopMarginValue: TextView = root.findViewById(R.id.options_text_hud_top_margin_value)
        val hudBottomMarginValue: TextView = root.findViewById(R.id.options_text_hud_bottom_margin_value)
        val topLeftSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_tl)
        val topCenterSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_tc)
        val topRightSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_tr)
        val bottomLeftSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_bl)
        val bottomCenterSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_bc)
        val bottomRightSpinner: Spinner = root.findViewById(R.id.options_spinner_hud_br)

        companion object { fun bind(root: View) = OptionsDialogViews(root) }
    }
}
