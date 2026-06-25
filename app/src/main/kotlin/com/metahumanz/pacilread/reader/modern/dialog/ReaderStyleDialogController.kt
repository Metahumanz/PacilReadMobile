package com.metahumanz.pacilread.reader.modern.dialog

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.ReaderThemeConfig
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.GlassUiHelper
import com.metahumanz.pacilread.ui.HsvColorPlaneView
import com.metahumanz.pacilread.util.FileAssetHelper

class ReaderStyleDialogController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
    private val dialogSupport: ReaderDialogSupport,
    private val content: ReaderContentController,
    private val navigation: ReaderNavigationController,
    private val style: ReaderStyleController,
    private val chrome: ReaderChromeController,
) {
    fun showStyleDialog(backgroundPickerRequestCode: Int) {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_reader_style, null, false)
        val refs = StyleDialogViews(contentView)
        dialogSupport.applyTocStyleFullscreenInsets(contentView, refs.contentContainer)
        refs.uiThemeSpinner.adapter = dialogSupport.buildSpinnerAdapter(arrayOf("跟随应用", "跟随系统", "浅色", "深色"))
        refs.doublePageModeSpinner.adapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.DOUBLE_PAGE_MODE_LABELS)
        refs.fontFamilySpinner.adapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.READER_FONT_FAMILY_LABELS)
        refs.textColorSpinner.adapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.READER_TEXT_COLOR_LABELS)
        refs.fontFamilySpinner.setSelection(
            ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_FONT_FAMILY_KEYS, runtime.settingsStore.readerFontFamily, 0),
            false,
        )
        refs.textColorSpinner.setSelection(
            ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, runtime.settingsStore.readerTextColor, 0),
            false,
        )
        refs.fontSeek.progress = Math.round(runtime.settingsStore.fontSizeSp) - 12
        refs.fontWeightSeek.progress = ReaderOptionCatalog.fontWeightProgress(runtime.settingsStore.readerFontWeight)
        refs.lineSeek.progress = Math.round(runtime.settingsStore.lineSpacingExtraSp)
        refs.leftSeek.progress = runtime.settingsStore.leftPaddingDp
        refs.rightSeek.progress = runtime.settingsStore.rightPaddingDp
        refs.topSeek.progress = runtime.settingsStore.topPaddingDp
        refs.bottomSeek.progress = runtime.settingsStore.bottomPaddingDp
        refs.letterSpacingSeek.progress = Math.round(runtime.settingsStore.letterSpacing / LETTER_SPACING_STEP)
        refs.firstLineIndentSeek.progress = runtime.settingsStore.firstLineIndentDp
        refs.paragraphSpacingSeek.progress = runtime.settingsStore.paragraphSpacingDp
        refs.backgroundBlurSeek.progress = runtime.settingsStore.backgroundBlurPercent
        refs.keepScreenOn.isChecked = runtime.settingsStore.isKeepScreenOn
        refs.showTitleCheck.isChecked = runtime.settingsStore.isChapterTitleVisible
        refs.doublePageCheck.isChecked = runtime.settingsStore.isReaderDoublePageEnabled
        refs.doublePageModeSpinner.setSelection(
            ReaderOptionCatalog.indexOf(
                ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS,
                runtime.settingsStore.readerDoublePageMode,
                0,
            ),
            false,
        )
        applyDoublePageTurnStepButtons(refs, runtime.settingsStore.readerDoublePageTurnStep)
        updateDoublePageModeAvailability(refs)
        refs.backgroundText.text = style.currentBackgroundLabel()
        refs.uiThemeSpinner.setSelection(
            ReaderOptionCatalog.indexOf(ReaderOptionCatalog.UI_THEME_KEYS, runtime.settingsStore.readerUiThemeMode, 0),
            false,
        )
        val selectedReaderTheme = arrayOf(runtime.settingsStore.readerTheme)
        chrome.updateReaderThemeButtons(
            refs.paperThemeButton,
            refs.forestThemeButton,
            refs.nightThemeButton,
            effectiveReaderThemeForDialog(selectedReaderTheme[0]),
        )
        val chapterTitleAlignment = runtime.settingsStore.chapterTitleAlignment
        chrome.styleThemeButton(refs.titleLeftButton, chapterTitleAlignment == "left")
        chrome.styleThemeButton(refs.titleCenterButton, chapterTitleAlignment == "center")
        chrome.styleThemeButton(refs.bodyJustifyButton, runtime.settingsStore.isBodyTextJustified)
        chrome.styleThemeButton(refs.bodyLeftButton, !runtime.settingsStore.isBodyTextJustified)
        style.updateLetterSpacingLabel(refs.letterSpacingValue, refs.letterSpacingSeek)
        style.updateFirstLineIndentLabel(refs.firstLineIndentValue, refs.firstLineIndentSeek)
        style.updateParagraphSpacingLabel(refs.paragraphSpacingValue, refs.paragraphSpacingSeek)
        style.updateBackgroundBlurLabel(refs.backgroundBlurValue, refs.backgroundBlurSeek)

        val refreshTextColorPreview = Runnable {
            style.updateTextColorPreview(
                refs.textColorValue,
                ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.selectedItemPosition],
                ReaderThemePalette.from(effectiveReaderThemeForDialog(selectedReaderTheme[0])),
            )
        }
        refreshTextColorPreview.run()
        val autoApply = buildAutoApply(refs, selectedReaderTheme, refreshTextColorPreview)

        refs.paperThemeButton.setOnClickListener {
            selectBuiltInTheme("paper", refs, selectedReaderTheme, refreshTextColorPreview, autoApply)
        }
        refs.forestThemeButton.setOnClickListener {
            selectBuiltInTheme("forest", refs, selectedReaderTheme, refreshTextColorPreview, autoApply)
        }
        refs.nightThemeButton.setOnClickListener {
            selectBuiltInTheme("night", refs, selectedReaderTheme, refreshTextColorPreview, autoApply)
        }

        updateStyleLabels(refs)
        val styleSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateStyleLabels(refs)
                if (fromUser) autoApply.run()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = autoApply.run()
        }
        refs.fontWeightSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.fontSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.lineSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.leftSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.rightSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.topSeek.setOnSeekBarChangeListener(styleSeekListener)
        refs.bottomSeek.setOnSeekBarChangeListener(styleSeekListener)

        val simpleSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                when (seekBar) {
                    refs.letterSpacingSeek -> style.updateLetterSpacingLabel(refs.letterSpacingValue, refs.letterSpacingSeek)
                    refs.firstLineIndentSeek -> style.updateFirstLineIndentLabel(refs.firstLineIndentValue, refs.firstLineIndentSeek)
                    refs.paragraphSpacingSeek -> style.updateParagraphSpacingLabel(refs.paragraphSpacingValue, refs.paragraphSpacingSeek)
                    refs.backgroundBlurSeek -> style.updateBackgroundBlurLabel(refs.backgroundBlurValue, refs.backgroundBlurSeek)
                }
                if (fromUser) autoApply.run()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = autoApply.run()
        }
        refs.letterSpacingSeek.setOnSeekBarChangeListener(simpleSeekListener)
        refs.firstLineIndentSeek.setOnSeekBarChangeListener(simpleSeekListener)
        refs.paragraphSpacingSeek.setOnSeekBarChangeListener(simpleSeekListener)
        refs.backgroundBlurSeek.setOnSeekBarChangeListener(simpleSeekListener)

        refs.keepScreenOn.setOnCheckedChangeListener { _, _ -> autoApply.run() }
        refs.showTitleCheck.setOnCheckedChangeListener { _, _ -> autoApply.run() }
        refs.doublePageCheck.setOnCheckedChangeListener { _, _ ->
            updateDoublePageModeAvailability(refs)
            autoApply.run()
        }
        refs.fontFamilySpinner.onItemSelectedListener = ItemSelectedListener { autoApply.run() }
        refs.textColorSpinner.onItemSelectedListener = ItemSelectedListener {
            refreshTextColorPreview.run()
            autoApply.run()
        }
        refs.uiThemeSpinner.onItemSelectedListener = ItemSelectedListener { autoApply.run() }
        refs.doublePageModeSpinner.onItemSelectedListener = ItemSelectedListener { autoApply.run() }
        refs.doublePageTurnOneButton.setOnClickListener {
            applyDoublePageTurnStepButtons(refs, "one")
            autoApply.run()
        }
        refs.doublePageTurnTwoButton.setOnClickListener {
            applyDoublePageTurnStepButtons(refs, "two")
            autoApply.run()
        }
        refs.titleLeftButton.setOnClickListener {
            runtime.settingsStore.chapterTitleAlignment = "left"
            chrome.styleThemeButton(refs.titleLeftButton, true)
            chrome.styleThemeButton(refs.titleCenterButton, false)
            autoApply.run()
        }
        refs.titleCenterButton.setOnClickListener {
            runtime.settingsStore.chapterTitleAlignment = "center"
            chrome.styleThemeButton(refs.titleLeftButton, false)
            chrome.styleThemeButton(refs.titleCenterButton, true)
            autoApply.run()
        }
        refs.bodyJustifyButton.setOnClickListener {
            runtime.settingsStore.isBodyTextJustified = true
            chrome.styleThemeButton(refs.bodyJustifyButton, true)
            chrome.styleThemeButton(refs.bodyLeftButton, false)
            autoApply.run()
        }
        refs.bodyLeftButton.setOnClickListener {
            runtime.settingsStore.isBodyTextJustified = false
            chrome.styleThemeButton(refs.bodyJustifyButton, false)
            chrome.styleThemeButton(refs.bodyLeftButton, true)
            autoApply.run()
        }
        refs.customColorButton.setOnClickListener {
            showCustomColorPickerDialog(Runnable {
                refs.textColorSpinner.setSelection(
                    ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, "custom", 0),
                    false,
                )
                refreshTextColorPreview.run()
                autoApply.run()
            })
        }

        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        dialogSupport.addAlignedCloseButton(contentView, R.id.style_title, refs.contentContainer, dialog)
        contentView.findViewById<View>(R.id.style_button_pick_background).setOnClickListener {
            style.openBackgroundPicker(backgroundPickerRequestCode)
        }
        contentView.findViewById<View>(R.id.style_button_clear_background).setOnClickListener {
            FileAssetHelper.deleteIfExists(runtime.settingsStore.readerBackgroundPath)
            runtime.settingsStore.readerBackgroundPath = ""
            refs.backgroundText.text = style.currentBackgroundLabel()
            style.applyReaderSettings()
        }
        contentView.findViewById<View>(R.id.style_button_save_theme).setOnClickListener {
            promptSaveTheme(Runnable { renderThemeRows(refs.customThemeList, dialog, refs, selectedReaderTheme) })
        }
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible)
        contentView.requestApplyInsets()
        renderThemeRows(refs.customThemeList, dialog, refs, selectedReaderTheme)
    }

    private fun selectBuiltInTheme(
        theme: String,
        refs: StyleDialogViews,
        selectedReaderTheme: Array<String>,
        refreshTextColorPreview: Runnable,
        autoApply: Runnable,
    ) {
        selectedReaderTheme[0] = theme
        chrome.updateReaderThemeButtons(
            refs.paperThemeButton,
            refs.forestThemeButton,
            refs.nightThemeButton,
            effectiveReaderThemeForDialog(theme),
        )
        refreshTextColorPreview.run()
        autoApply.run()
    }

    private fun buildAutoApply(
        refs: StyleDialogViews,
        selectedReaderTheme: Array<String>,
        refreshTextColorPreview: Runnable,
    ): Runnable = Runnable {
        val anchorOffset = content.currentCharOffset()
        val previousResolvedAppearance = ThemeModeHelper.getResolvedReaderAppearanceLabel(activity)
        runtime.settingsStore.readerFontFamily = ReaderOptionCatalog.READER_FONT_FAMILY_KEYS[refs.fontFamilySpinner.selectedItemPosition]
        runtime.settingsStore.readerTextColor = ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.selectedItemPosition]
        runtime.settingsStore.fontSizeSp = (refs.fontSeek.progress + 12).toFloat()
        runtime.settingsStore.readerFontWeight = ReaderOptionCatalog.fontWeightValueForProgress(refs.fontWeightSeek.progress)
        runtime.settingsStore.lineSpacingExtraSp = refs.lineSeek.progress.toFloat()
        runtime.settingsStore.leftPaddingDp = refs.leftSeek.progress
        runtime.settingsStore.rightPaddingDp = refs.rightSeek.progress
        runtime.settingsStore.topPaddingDp = refs.topSeek.progress
        runtime.settingsStore.bottomPaddingDp = refs.bottomSeek.progress
        runtime.settingsStore.letterSpacing = refs.letterSpacingSeek.progress * LETTER_SPACING_STEP
        runtime.settingsStore.firstLineIndentDp = refs.firstLineIndentSeek.progress
        runtime.settingsStore.paragraphSpacingDp = refs.paragraphSpacingSeek.progress
        runtime.settingsStore.backgroundBlurPercent = refs.backgroundBlurSeek.progress
        runtime.settingsStore.isKeepScreenOn = refs.keepScreenOn.isChecked
        runtime.settingsStore.isChapterTitleVisible = refs.showTitleCheck.isChecked
        runtime.settingsStore.isReaderDoublePageEnabled = refs.doublePageCheck.isChecked
        runtime.settingsStore.readerDoublePageMode =
            ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS[refs.doublePageModeSpinner.selectedItemPosition]
        runtime.settingsStore.readerDoublePageTurnStep = if (refs.doublePageTurnOneButton.isSelected) "one" else "two"
        runtime.settingsStore.readerTheme = selectedReaderTheme[0]
        runtime.settingsStore.readerUiThemeMode = ReaderOptionCatalog.UI_THEME_KEYS[refs.uiThemeSpinner.selectedItemPosition]
        refreshTextColorPreview.run()
        val nextResolvedAppearance = ThemeModeHelper.getResolvedReaderAppearanceLabel(activity)
        if (previousResolvedAppearance != nextResolvedAppearance) {
            activity.applyReaderUiThemeWithoutRecreate()
            content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset)
            return@Runnable
        }
        content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset)
    }

    private fun updateDoublePageModeAvailability(refs: StyleDialogViews) {
        val enabled = refs.doublePageCheck.isChecked
        refs.doublePageModeSpinner.isEnabled = enabled
        refs.doublePageModeSpinner.alpha = if (enabled) 1f else 0.45f
        setEnabledWithAlpha(refs.doublePageTurnStepLayout, enabled)
        setEnabledWithAlpha(refs.doublePageTurnOneButton, enabled)
        setEnabledWithAlpha(refs.doublePageTurnTwoButton, enabled)
    }

    private fun applyDoublePageTurnStepButtons(refs: StyleDialogViews, step: String?) {
        val onePage = step == "one"
        refs.doublePageTurnOneButton.isSelected = onePage
        refs.doublePageTurnTwoButton.isSelected = !onePage
        chrome.styleThemeButton(refs.doublePageTurnOneButton, onePage)
        chrome.styleThemeButton(refs.doublePageTurnTwoButton, !onePage)
    }

    private fun setEnabledWithAlpha(view: View?, enabled: Boolean) {
        view ?: return
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
    }

    private fun renderThemeRows(
        container: LinearLayout,
        dialog: AlertDialog,
        refs: StyleDialogViews,
        selectedReaderTheme: Array<String>,
    ) {
        container.removeAllViews()
        runtime.safeExecute(Runnable {
            val themes = runtime.databaseHelper.customThemes
            activity.runOnReaderUiThread {
                if (!dialog.isShowing) return@runOnReaderUiThread
                container.removeAllViews()
                for (theme in themes) {
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    val applyButton = Button(activity).apply {
                        text = theme.name
                        setBackgroundResource(R.drawable.bg_outline_button)
                        setTextColor(ThemeModeHelper.resolveColor(activity, R.color.primary))
                    }
                    GlassUiHelper.applyToView(activity, applyButton, runtime.settingsStore.glassOpacityPercent)
                    val deleteButton = Button(activity).apply {
                        text = "删除"
                        setBackgroundResource(R.drawable.bg_danger_button)
                        setTextColor(0xFFFFFFFF.toInt())
                    }
                    row.addView(applyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(deleteButton, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = ui.dp(8) })
                    val refreshTextColorPreview = Runnable {
                        style.updateTextColorPreview(
                            refs.textColorValue,
                            ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.selectedItemPosition],
                            ReaderThemePalette.from(effectiveReaderThemeForDialog(selectedReaderTheme[0])),
                        )
                    }
                    val autoApply = buildAutoApply(refs, selectedReaderTheme, refreshTextColorPreview)
                    applyButton.setOnClickListener { autoApply.run() }
                    deleteButton.setOnClickListener {
                        runtime.safeExecute(Runnable {
                            runtime.databaseHelper.deleteCustomTheme(theme.id)
                            activity.runOnReaderUiThread { renderThemeRows(container, dialog, refs, selectedReaderTheme) }
                        }, "delete reader theme")
                    }
                    container.addView(row)
                }
            }
        }, "render reader themes")
    }

    private fun promptSaveTheme(onSaved: Runnable) {
        val input = EditText(activity).apply { hint = "主题名称" }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("保存当前主题")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    ui.showToast("请输入主题名称")
                    return@setPositiveButton
                }
                runtime.safeExecute(Runnable {
                    runtime.databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(runtime.settingsStore).toString())
                    activity.runOnReaderUiThread(onSaved)
                }, "save reader theme")
            }
            .create()
        dialogSupport.showStyledDialog(dialog)
    }

    private fun effectiveReaderThemeForDialog(selectedReaderTheme: String?): String {
        if (ReaderDisplayModeHelper.isAutoNightActive(activity, runtime.settingsStore)) return "night"
        return if (selectedReaderTheme.isNullOrBlank()) "paper" else selectedReaderTheme
    }

    private fun showCustomColorPickerDialog(onApply: Runnable?) {
        val contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_color_picker, null, false)
        val colorPlane = contentView.findViewById<HsvColorPlaneView>(R.id.color_plane)
        val hueSeek = contentView.findViewById<SeekBar>(R.id.color_seek_hue)
        val rgbText = contentView.findViewById<TextView>(R.id.color_text_rgb)
        val hexText = contentView.findViewById<TextView>(R.id.color_text_hex)
        val colorPreview = contentView.findViewById<View>(R.id.color_preview)
        val applyButton = contentView.findViewById<Button>(R.id.color_button_apply)
        var currentColor = 0xFF374151.toInt()
        val customColor = runtime.settingsStore.customTextColor
        if (customColor.isNotEmpty()) {
            try {
                currentColor = Color.parseColor(customColor)
            } catch (_: Exception) {
            }
        }
        colorPlane.setColor(currentColor)
        hueSeek.progress = Math.round(colorPlane.getHue()) % 360
        val updatePreview = Runnable {
            val color = colorPlane.getSelectedColor()
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            rgbText.text = "RGB: $red, $green, $blue"
            hexText.text = String.format("HEX: #%02X%02X%02X", red, green, blue)
            colorPreview.setBackgroundColor(color)
        }
        updatePreview.run()
        colorPlane.setOnColorChangeListener { updatePreview.run() }
        hueSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = colorPlane.setHue(progress.toFloat())
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val dialog = AlertDialog.Builder(activity).setView(contentView).create()
        applyButton.setOnClickListener {
            val color = colorPlane.getSelectedColor()
            val hexColor = String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
            runtime.settingsStore.customTextColor = hexColor
            runtime.settingsStore.readerTextColor = "custom"
            dialog.dismiss()
            onApply?.run()
        }
        dialogSupport.showStyledDialog(dialog)
    }

    private fun updateStyleLabels(refs: StyleDialogViews) {
        refs.fontValue.text = "${refs.fontSeek.progress + 12} sp"
        refs.fontWeightValue.text = "${ReaderOptionCatalog.readerFontWeightLabelForProgress(refs.fontWeightSeek.progress)} " +
            "(${ReaderOptionCatalog.fontWeightValueForProgress(refs.fontWeightSeek.progress)})"
        refs.lineValue.text = "${refs.lineSeek.progress} px"
        refs.leftValue.text = "${refs.leftSeek.progress} dp"
        refs.rightValue.text = "${refs.rightSeek.progress} dp"
        refs.topValue.text = "${refs.topSeek.progress} dp"
        refs.bottomValue.text = "${refs.bottomSeek.progress} dp"
    }

    private class ItemSelectedListener(private val action: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private class StyleDialogViews(root: View) {
        val fontFamilySpinner: Spinner = root.findViewById(R.id.style_spinner_font_family)
        val textColorSpinner: Spinner = root.findViewById(R.id.style_spinner_text_color)
        val contentContainer: View = root.findViewById(R.id.style_content)
        val fontSeek: SeekBar = root.findViewById(R.id.style_seek_font)
        val fontWeightSeek: SeekBar = root.findViewById(R.id.style_seek_font_weight)
        val lineSeek: SeekBar = root.findViewById(R.id.style_seek_line_spacing)
        val leftSeek: SeekBar = root.findViewById(R.id.style_seek_left_padding)
        val rightSeek: SeekBar = root.findViewById(R.id.style_seek_right_padding)
        val topSeek: SeekBar = root.findViewById(R.id.style_seek_top_padding)
        val bottomSeek: SeekBar = root.findViewById(R.id.style_seek_bottom_padding)
        val letterSpacingSeek: SeekBar = root.findViewById(R.id.style_seek_letter_spacing)
        val firstLineIndentSeek: SeekBar = root.findViewById(R.id.style_seek_first_line_indent)
        val paragraphSpacingSeek: SeekBar = root.findViewById(R.id.style_seek_paragraph_spacing)
        val backgroundBlurSeek: SeekBar = root.findViewById(R.id.style_seek_background_blur)
        val textColorValue: TextView = root.findViewById(R.id.style_text_text_color)
        val fontValue: TextView = root.findViewById(R.id.style_text_font)
        val fontWeightValue: TextView = root.findViewById(R.id.style_text_font_weight)
        val lineValue: TextView = root.findViewById(R.id.style_text_line_spacing)
        val leftValue: TextView = root.findViewById(R.id.style_text_left_padding)
        val rightValue: TextView = root.findViewById(R.id.style_text_right_padding)
        val topValue: TextView = root.findViewById(R.id.style_text_top_padding)
        val bottomValue: TextView = root.findViewById(R.id.style_text_bottom_padding)
        val letterSpacingValue: TextView = root.findViewById(R.id.style_text_letter_spacing)
        val firstLineIndentValue: TextView = root.findViewById(R.id.style_text_first_line_indent)
        val paragraphSpacingValue: TextView = root.findViewById(R.id.style_text_paragraph_spacing)
        val backgroundBlurValue: TextView = root.findViewById(R.id.style_text_background_blur)
        val uiThemeSpinner: Spinner = root.findViewById(R.id.style_spinner_ui_theme_mode)
        val doublePageModeSpinner: Spinner = root.findViewById(R.id.style_spinner_double_page_mode)
        val doublePageTurnStepLayout: LinearLayout = root.findViewById(R.id.style_layout_double_page_turn_step)
        val keepScreenOn: CheckBox = root.findViewById(R.id.style_check_keep_screen_on)
        val showTitleCheck: CheckBox = root.findViewById(R.id.style_check_show_title)
        val doublePageCheck: CheckBox = root.findViewById(R.id.style_check_double_page)
        val backgroundText: TextView = root.findViewById(R.id.style_text_background)
        val customThemeList: LinearLayout = root.findViewById(R.id.style_custom_theme_list)
        val paperThemeButton: Button = root.findViewById(R.id.style_button_theme_paper)
        val forestThemeButton: Button = root.findViewById(R.id.style_button_theme_forest)
        val nightThemeButton: Button = root.findViewById(R.id.style_button_theme_night)
        val titleLeftButton: Button = root.findViewById(R.id.style_button_title_left)
        val titleCenterButton: Button = root.findViewById(R.id.style_button_title_center)
        val bodyJustifyButton: Button = root.findViewById(R.id.style_button_body_justify)
        val bodyLeftButton: Button = root.findViewById(R.id.style_button_body_left)
        val customColorButton: Button = root.findViewById(R.id.style_button_custom_color)
        val doublePageTurnOneButton: Button = root.findViewById(R.id.style_button_double_page_turn_one)
        val doublePageTurnTwoButton: Button = root.findViewById(R.id.style_button_double_page_turn_two)
    }

    private companion object {
        const val LETTER_SPACING_STEP = 0.05f
    }
}
