package com.metahumanz.pacilread

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.metahumanz.pacilread.reader.JustifiedPageTextView
import com.metahumanz.pacilread.reader.ReaderThemeConfig
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemedActivity
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.GlassUiHelper
import com.metahumanz.pacilread.ui.PredictiveBackScaleController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import com.metahumanz.pacilread.util.FileAssetHelper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PreviewActivity : ThemedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var databaseHelper: JsonDatabase
    private lateinit var settingsStore: SettingsStore
    private var drawerController: AppDrawerController? = null
    private lateinit var mainRoot: View
    private lateinit var previewTheme: TextView
    private lateinit var previewReaderStage: View
    private lateinit var previewReaderBackground: ImageView
    private lateinit var previewReaderScrim: View
    private lateinit var previewReaderPage: View
    private lateinit var previewReaderRightPage: View
    private lateinit var previewReaderGutter: View
    private lateinit var previewReaderHeading: TextView
    private lateinit var previewReaderHeadingRight: TextView
    private lateinit var previewReaderBody: JustifiedPageTextView
    private lateinit var previewReaderBodyRight: JustifiedPageTextView
    private lateinit var previewStyleUiThemeSpinner: Spinner
    private lateinit var previewStyleDoublePageModeSpinner: Spinner
    private lateinit var previewStyleFontFamilySpinner: Spinner
    private lateinit var previewStyleFontSeekBar: SeekBar
    private lateinit var previewStyleFontWeightSeekBar: SeekBar
    private lateinit var previewStyleLineSeekBar: SeekBar
    private lateinit var previewStyleLeftSeekBar: SeekBar
    private lateinit var previewStyleRightSeekBar: SeekBar
    private lateinit var previewStyleTopSeekBar: SeekBar
    private lateinit var previewStyleBottomSeekBar: SeekBar
    private lateinit var previewStyleFontText: TextView
    private lateinit var previewStyleFontWeightText: TextView
    private lateinit var previewStyleLineText: TextView
    private lateinit var previewStyleLeftText: TextView
    private lateinit var previewStyleRightText: TextView
    private lateinit var previewStyleTopText: TextView
    private lateinit var previewStyleBottomText: TextView
    private lateinit var previewStyleKeepScreenOnCheck: CheckBox
    private lateinit var previewStyleShowTitleCheck: CheckBox
    private lateinit var previewStyleDoublePageCheck: CheckBox
    private lateinit var previewStyleBackgroundText: TextView
    private lateinit var previewThemePaperButton: Button
    private lateinit var previewThemeForestButton: Button
    private lateinit var previewThemeNightButton: Button
    private lateinit var previewStyleCustomThemeList: LinearLayout
    private var bindingPreviewStyleValues = false
    private var previewSelectedReaderTheme = "paper"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        databaseHelper = JsonDatabase.getInstance(this)
        settingsStore = SettingsStore(this)
        mainRoot = findViewById(R.id.main_root)
        bindViews()
        setupPreviewStyleSection()
        configureDrawer()
        installPredictiveBack()
    }

    override fun onResume() {
        super.onResume()
        updatePreviewPanels()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val drawer = drawerController
        if (drawer != null && drawer.handleTouchEvent(event)) {
            if (drawer.consumePendingChildTouchCancel()) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null && requestCode == REQUEST_PICK_READER_BACKGROUND) {
            data.data?.let(::attachPreviewBackground)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun installPredictiveBack() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) return
        PredictiveBackScaleController.install(
            this,
            mainRoot,
            PredictiveBackScaleController.Profile.standard(),
            object : PredictiveBackScaleController.Delegate {
                override fun shouldAnimateBack(): Boolean = drawerController?.isDrawerVisible() != true
                override fun consumeBack(): Boolean = drawerController?.onBackPressed() == true
                override fun commitBack() = finish()
            },
        )
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            if (drawerController?.onBackPressed() == true) return
            finish()
            return
        }
        super.onBackPressed()
    }

    private fun bindViews() {
        previewTheme = findViewById(R.id.text_preview_theme)
        previewReaderStage = findViewById(R.id.layout_preview_reader_stage)
        previewReaderBackground = findViewById(R.id.image_preview_reader_background)
        previewReaderScrim = findViewById(R.id.view_preview_reader_scrim)
        previewReaderPage = findViewById(R.id.layout_preview_reader_page)
        previewReaderRightPage = findViewById(R.id.layout_preview_reader_right_page)
        previewReaderGutter = findViewById(R.id.view_preview_reader_gutter)
        previewReaderHeading = findViewById(R.id.text_preview_reader_heading)
        previewReaderHeadingRight = findViewById(R.id.text_preview_reader_heading_right)
        previewReaderBody = findViewById(R.id.text_preview_reader_body)
        previewReaderBodyRight = findViewById(R.id.text_preview_reader_body_right)
        previewStyleUiThemeSpinner = findViewById(R.id.preview_style_spinner_ui_theme_mode)
        previewStyleDoublePageModeSpinner = findViewById(R.id.preview_style_spinner_double_page_mode)
        previewStyleFontFamilySpinner = findViewById(R.id.preview_style_spinner_font_family)
        previewStyleFontSeekBar = findViewById(R.id.preview_style_seek_font)
        previewStyleFontWeightSeekBar = findViewById(R.id.preview_style_seek_font_weight)
        previewStyleLineSeekBar = findViewById(R.id.preview_style_seek_line_spacing)
        previewStyleLeftSeekBar = findViewById(R.id.preview_style_seek_left_padding)
        previewStyleRightSeekBar = findViewById(R.id.preview_style_seek_right_padding)
        previewStyleTopSeekBar = findViewById(R.id.preview_style_seek_top_padding)
        previewStyleBottomSeekBar = findViewById(R.id.preview_style_seek_bottom_padding)
        previewStyleFontText = findViewById(R.id.preview_style_text_font)
        previewStyleFontWeightText = findViewById(R.id.preview_style_text_font_weight)
        previewStyleLineText = findViewById(R.id.preview_style_text_line_spacing)
        previewStyleLeftText = findViewById(R.id.preview_style_text_left_padding)
        previewStyleRightText = findViewById(R.id.preview_style_text_right_padding)
        previewStyleTopText = findViewById(R.id.preview_style_text_top_padding)
        previewStyleBottomText = findViewById(R.id.preview_style_text_bottom_padding)
        previewStyleKeepScreenOnCheck = findViewById(R.id.preview_style_check_keep_screen_on)
        previewStyleShowTitleCheck = findViewById(R.id.preview_style_check_show_title)
        previewStyleDoublePageCheck = findViewById(R.id.preview_style_check_double_page)
        previewStyleBackgroundText = findViewById(R.id.preview_style_text_background)
        previewThemePaperButton = findViewById(R.id.preview_style_button_theme_paper)
        previewThemeForestButton = findViewById(R.id.preview_style_button_theme_forest)
        previewThemeNightButton = findViewById(R.id.preview_style_button_theme_night)
        previewStyleCustomThemeList = findViewById(R.id.preview_style_custom_theme_list)
        previewReaderStage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updatePreviewReader()
        }
    }

    private fun configureDrawer() {
        drawerController = AppDrawerController(this, mainRoot) { destination ->
            when (destination) {
                AppDrawerController.SECTION_BOOKSHELF -> finish()
                AppDrawerController.SECTION_SETTINGS -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    drawerController?.closeDrawer()
                }
                else -> drawerController?.closeDrawer()
            }
        }.also {
            it.bindMenuButton(R.id.button_open_drawer)
            it.setCurrentSection(AppDrawerController.SECTION_PREVIEW)
        }
    }

    private fun setupPreviewStyleSection() {
        bindingPreviewStyleValues = true
        previewStyleUiThemeSpinner.adapter = buildSpinnerAdapter(arrayOf("跟随应用", "跟随系统", "浅色", "深色"))
        previewStyleDoublePageModeSpinner.adapter = buildSpinnerAdapter(ReaderOptionCatalog.DOUBLE_PAGE_MODE_LABELS)
        previewStyleFontFamilySpinner.adapter = buildSpinnerAdapter(READER_FONT_FAMILY_LABELS)
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = applyPreviewStyleControls()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        previewStyleUiThemeSpinner.onItemSelectedListener = spinnerListener
        previewStyleDoublePageModeSpinner.onItemSelectedListener = spinnerListener
        previewStyleFontFamilySpinner.onItemSelectedListener = spinnerListener
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreviewStyleLabels()
                if (fromUser) applyPreviewStyleControls()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = applyPreviewStyleControls()
        }
        arrayOf(previewStyleFontSeekBar, previewStyleFontWeightSeekBar, previewStyleLineSeekBar,
            previewStyleLeftSeekBar, previewStyleRightSeekBar, previewStyleTopSeekBar, previewStyleBottomSeekBar)
            .forEach { it.setOnSeekBarChangeListener(seekListener) }
        previewStyleKeepScreenOnCheck.setOnCheckedChangeListener { _, _ -> applyPreviewStyleControls() }
        previewStyleShowTitleCheck.setOnCheckedChangeListener { _, _ -> applyPreviewStyleControls() }
        previewStyleDoublePageCheck.setOnCheckedChangeListener { _, _ ->
            updatePreviewDoublePageModeAvailability()
            applyPreviewStyleControls()
        }
        previewThemePaperButton.setOnClickListener { selectTheme("paper") }
        previewThemeForestButton.setOnClickListener { selectTheme("forest") }
        previewThemeNightButton.setOnClickListener { selectTheme("night") }
        findViewById<View>(R.id.preview_style_button_pick_background).setOnClickListener { openPreviewBackgroundPicker() }
        findViewById<View>(R.id.preview_style_button_clear_background).setOnClickListener {
            FileAssetHelper.deleteIfExists(settingsStore.readerBackgroundPath)
            settingsStore.readerBackgroundPath = ""
            updatePreviewPanels()
        }
        findViewById<View>(R.id.preview_style_button_save_theme).setOnClickListener { promptSavePreviewTheme() }
        bindingPreviewStyleValues = false
        renderPreviewThemeRows()
        bindPreviewStyleValues()
    }

    private fun selectTheme(theme: String) {
        previewSelectedReaderTheme = theme
        updatePreviewThemeButtons()
        applyPreviewStyleControls()
    }

    private fun updatePreviewPanels() {
        previewTheme.text = "界面: ${ThemeModeHelper.getResolvedReaderAppearanceLabel(this)}" +
            " · 阅读预设: ${labelForReaderPreset(ReaderDisplayModeHelper.resolveReaderThemeKey(this, settingsStore))}" +
            " · 双页: ${if (settingsStore.isReaderDoublePageEnabled) labelForDoublePageMode(settingsStore.readerDoublePageMode) else "关闭"}" +
            " · 字体 ${labelForReaderFontFamily(settingsStore.readerFontFamily)}" +
            " · 字重 ${labelForReaderFontWeight(settingsStore.readerFontWeight)}" +
            " · 字号 ${settingsStore.fontSizeSp.roundToInt()}sp"
        bindPreviewStyleValues()
        updatePreviewReader()
    }

    private fun bindPreviewStyleValues() {
        bindingPreviewStyleValues = true
        previewSelectedReaderTheme = settingsStore.readerTheme
        previewStyleUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.readerUiThemeMode, 0), false)
        previewStyleDoublePageModeSpinner.setSelection(indexOf(ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS, settingsStore.readerDoublePageMode, 0), false)
        previewStyleFontFamilySpinner.setSelection(indexOf(READER_FONT_FAMILY_KEYS, settingsStore.readerFontFamily, 0), false)
        previewStyleFontSeekBar.progress = settingsStore.fontSizeSp.roundToInt() - 12
        previewStyleFontWeightSeekBar.progress = fontWeightProgress(settingsStore.readerFontWeight)
        previewStyleLineSeekBar.progress = settingsStore.lineSpacingExtraSp.roundToInt()
        previewStyleLeftSeekBar.progress = settingsStore.leftPaddingDp
        previewStyleRightSeekBar.progress = settingsStore.rightPaddingDp
        previewStyleTopSeekBar.progress = settingsStore.topPaddingDp
        previewStyleBottomSeekBar.progress = settingsStore.bottomPaddingDp
        previewStyleKeepScreenOnCheck.isChecked = settingsStore.isKeepScreenOn
        previewStyleShowTitleCheck.isChecked = settingsStore.isChapterTitleVisible
        previewStyleDoublePageCheck.isChecked = settingsStore.isReaderDoublePageEnabled
        previewStyleBackgroundText.text = currentPreviewBackgroundLabel()
        updatePreviewDoublePageModeAvailability()
        updatePreviewThemeButtons()
        updatePreviewStyleLabels()
        bindingPreviewStyleValues = false
    }

    private fun applyPreviewStyleControls() {
        if (bindingPreviewStyleValues) return
        settingsStore.readerUiThemeMode = READER_THEME_KEYS[previewStyleUiThemeSpinner.selectedItemPosition]
        settingsStore.isReaderDoublePageEnabled = previewStyleDoublePageCheck.isChecked
        settingsStore.readerDoublePageMode = ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS[previewStyleDoublePageModeSpinner.selectedItemPosition]
        settingsStore.readerFontFamily = READER_FONT_FAMILY_KEYS[previewStyleFontFamilySpinner.selectedItemPosition]
        settingsStore.fontSizeSp = previewStyleFontSeekBar.progress + 12f
        settingsStore.readerFontWeight = fontWeightValueForProgress(previewStyleFontWeightSeekBar.progress)
        settingsStore.lineSpacingExtraSp = previewStyleLineSeekBar.progress.toFloat()
        settingsStore.leftPaddingDp = previewStyleLeftSeekBar.progress
        settingsStore.rightPaddingDp = previewStyleRightSeekBar.progress
        settingsStore.topPaddingDp = previewStyleTopSeekBar.progress
        settingsStore.bottomPaddingDp = previewStyleBottomSeekBar.progress
        settingsStore.isKeepScreenOn = previewStyleKeepScreenOnCheck.isChecked
        settingsStore.isChapterTitleVisible = previewStyleShowTitleCheck.isChecked
        settingsStore.readerTheme = previewSelectedReaderTheme
        updatePreviewPanels()
    }

    private fun updatePreviewReader() {
        val palette = ReaderDisplayModeHelper.resolvePalette(this, settingsStore)
        val bodyTypeface = buildReaderTypeface(settingsStore.readerFontFamily, settingsStore.readerFontWeight)
        val titleTypeface = buildReaderTypeface(settingsStore.readerFontFamily, max(600, min(900, settingsStore.readerFontWeight + 200)))
        val doublePageActive = ReaderDisplayModeHelper.isDoublePageActive(this, settingsStore, previewReaderStage.width, previewReaderStage.height)
        previewReaderRightPage.visibility = if (doublePageActive) View.VISIBLE else View.GONE
        previewReaderGutter.visibility = if (doublePageActive) View.VISIBLE else View.GONE
        previewReaderHeading.visibility = if (settingsStore.isChapterTitleVisible) View.VISIBLE else View.GONE
        previewReaderHeadingRight.visibility = View.GONE
        previewReaderHeading.text = "第一章 雨落书页时"
        previewReaderHeading.includeFontPadding = false
        previewReaderHeadingRight.includeFontPadding = false
        previewReaderHeading.typeface = titleTypeface
        previewReaderHeadingRight.typeface = titleTypeface
        previewReaderHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.fontSizeSp * 1.4f)
        previewReaderHeadingRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.fontSizeSp * 1.4f)
        previewReaderHeading.setTextColor(palette.textColor)
        previewReaderHeadingRight.setTextColor(palette.textColor)
        previewReaderBody.text = if (doublePageActive)
            "雨点敲在窗沿上时，旧书的纸页也跟着轻轻起伏。字距、行距、边距与字重会共同决定这页文字是松弛、沉稳，还是压迫。"
        else "雨点敲在窗沿上时，旧书的纸页也跟着轻轻起伏。字距、行距、边距与字重会共同决定这页文字是松弛、沉稳，还是压迫。\n\n如果一段文字像现在这样安静地铺开，说明当前排版已经接近真实阅读状态。"
        previewReaderBodyRight.text = "如果一段文字像现在这样安静地铺开，说明当前排版已经接近真实阅读状态。双页模式下，下一页会并排放在右侧。"
        stylePreviewBody(previewReaderBody, bodyTypeface, palette.textColor)
        stylePreviewBody(previewReaderBodyRight, bodyTypeface, palette.textColor)
        previewReaderPage.setBackgroundColor(palette.pageColor)
        previewReaderScrim.setBackgroundColor(palette.overlayColor)
        previewReaderPage.setPadding(dp(settingsStore.leftPaddingDp), dp(settingsStore.topPaddingDp + 24),
            dp(settingsStore.rightPaddingDp), dp(settingsStore.bottomPaddingDp + 24))
        (previewReaderBody.layoutParams as LinearLayout.LayoutParams).also {
            it.topMargin = if (settingsStore.isChapterTitleVisible) max(dp(16), (previewReaderHeading.textSize * 1.5f).roundToInt()) else 0
            previewReaderBody.layoutParams = it
        }
        (previewReaderBodyRight.layoutParams as LinearLayout.LayoutParams).also {
            it.topMargin = 0
            previewReaderBodyRight.layoutParams = it
        }
        val backgroundPath = settingsStore.readerBackgroundPath
        var applied = false
        if (backgroundPath.isNotBlank() && !ReaderDisplayModeHelper.shouldOverrideCustomVisuals(this, settingsStore, null)) {
            val file = File(backgroundPath)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    previewReaderBackground.setImageBitmap(it)
                    applied = true
                }
            }
        }
        if (!applied) previewReaderBackground.setImageResource(palette.backgroundDrawableRes)
    }

    private fun stylePreviewBody(body: JustifiedPageTextView, typeface: Typeface, textColor: Int) {
        body.typeface = typeface
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.fontSizeSp)
        body.setTextColor(textColor)
        body.setLineSpacing(settingsStore.lineSpacingExtraSp, 1f)
        body.letterSpacing = settingsStore.letterSpacing
        body.setFullJustifyEnabled(settingsStore.isBodyTextJustified)
    }

    private fun updatePreviewDoublePageModeAvailability() {
        val enabled = previewStyleDoublePageCheck.isChecked
        previewStyleDoublePageModeSpinner.isEnabled = enabled
        previewStyleDoublePageModeSpinner.alpha = if (enabled) 1f else 0.45f
    }

    private fun updatePreviewThemeButtons() {
        stylePreviewThemeButton(previewThemePaperButton, previewSelectedReaderTheme == "paper")
        stylePreviewThemeButton(previewThemeForestButton, previewSelectedReaderTheme == "forest")
        stylePreviewThemeButton(previewThemeNightButton, previewSelectedReaderTheme == "night")
    }

    private fun stylePreviewThemeButton(button: Button, active: Boolean) {
        button.setBackgroundResource(if (active) R.drawable.bg_app_primary_button else R.drawable.bg_app_outline_button)
        button.setTextColor(ThemeModeHelper.resolveColor(this, if (active) R.color.app_button_primary_text else R.color.app_button_outline_text))
        GlassUiHelper.applyToView(this, button, settingsStore.glassOpacityPercent)
    }

    private fun updatePreviewStyleLabels() {
        previewStyleFontText.text = "${previewStyleFontSeekBar.progress + 12} sp"
        previewStyleFontWeightText.text = "${readerFontWeightLabelForProgress(previewStyleFontWeightSeekBar.progress)} (${fontWeightValueForProgress(previewStyleFontWeightSeekBar.progress)})"
        previewStyleLineText.text = "${previewStyleLineSeekBar.progress} px"
        previewStyleLeftText.text = "${previewStyleLeftSeekBar.progress} dp"
        previewStyleRightText.text = "${previewStyleRightSeekBar.progress} dp"
        previewStyleTopText.text = "${previewStyleTopSeekBar.progress} dp"
        previewStyleBottomText.text = "${previewStyleBottomSeekBar.progress} dp"
    }

    private fun openPreviewBackgroundPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, REQUEST_PICK_READER_BACKGROUND)
    }

    private fun attachPreviewBackground(uri: Uri) {
        executor.execute {
            try {
                val oldPath = settingsStore.readerBackgroundPath
                val newFile = FileAssetHelper.copyUriToFolder(this, uri, "backgrounds", "reader_bg")
                if (oldPath.isNotBlank()) FileAssetHelper.deleteIfExists(oldPath)
                settingsStore.readerBackgroundPath = newFile.absolutePath
                runOnUiThread(::updatePreviewPanels)
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "设置背景失败: ${error.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun currentPreviewBackgroundLabel(): String {
        val path = settingsStore.readerBackgroundPath
        if (path.isBlank() || ReaderDisplayModeHelper.shouldOverrideCustomVisuals(this, settingsStore, null)) {
            val suffix = if (ReaderDisplayModeHelper.isAutoNightActive(this, settingsStore)) "（自动夜航）" else ""
            return "当前背景：使用${labelForReaderPreset(ReaderDisplayModeHelper.resolveReaderThemeKey(this, settingsStore))}内置壁纸$suffix"
        }
        return "当前背景：${File(path).name}"
    }

    private fun renderPreviewThemeRows() {
        previewStyleCustomThemeList.removeAllViews()
        executor.execute {
            try {
                val themes = databaseHelper.getCustomThemes()
                runOnUiThread {
                    previewStyleCustomThemeList.removeAllViews()
                    for (theme in themes) {
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        val applyButton = Button(this).apply {
                            text = theme.name
                            isAllCaps = false
                            setBackgroundResource(R.drawable.bg_app_outline_button)
                            setTextColor(ThemeModeHelper.resolveColor(this@PreviewActivity, R.color.app_button_outline_text))
                            setPadding(dp(16), dp(8), dp(16), dp(8))
                            GlassUiHelper.applyToView(this@PreviewActivity, this, settingsStore.glassOpacityPercent)
                        }
                        row.addView(applyButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        val deleteButton = Button(this).apply {
                            text = "删除"
                            isAllCaps = false
                            setBackgroundResource(R.drawable.bg_app_danger_button)
                            setTextColor(ThemeModeHelper.resolveColor(this@PreviewActivity, R.color.app_button_danger_text))
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        row.addView(deleteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
                        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                        applyButton.setOnClickListener {
                            try {
                                ReaderThemeConfig.apply(settingsStore, JSONObject(theme.configJson ?: "{}"))
                                previewSelectedReaderTheme = settingsStore.readerTheme
                                bindPreviewStyleValues()
                                updatePreviewPanels()
                            } catch (_: Exception) {
                                Toast.makeText(this, "主题配置损坏", Toast.LENGTH_SHORT).show()
                            }
                        }
                        deleteButton.setOnClickListener {
                            executor.execute {
                                databaseHelper.deleteCustomTheme(theme.id)
                                runOnUiThread(::renderPreviewThemeRows)
                            }
                        }
                        previewStyleCustomThemeList.addView(row)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    previewStyleCustomThemeList.removeAllViews()
                    Toast.makeText(this, "加载主题失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun promptSavePreviewTheme() {
        val input = EditText(this).apply {
            hint = "主题名称"
            setBackgroundResource(R.drawable.bg_app_input)
            setTextColor(ThemeModeHelper.resolveColor(this@PreviewActivity, R.color.app_text_primary))
            setHintTextColor(ThemeModeHelper.resolveColor(this@PreviewActivity, R.color.app_text_secondary))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this).setTitle("保存当前主题").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入主题名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                executor.execute {
                    databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(settingsStore).toString())
                    runOnUiThread(::renderPreviewThemeRows)
                }
            }.show()
    }

    private fun fontWeightProgress(weight: Int): Int = READER_FONT_WEIGHT_VALUES.indexOf(weight).let { if (it < 0) 1 else it }
    private fun fontWeightValueForProgress(progress: Int): Int = READER_FONT_WEIGHT_VALUES[progress.coerceIn(READER_FONT_WEIGHT_VALUES.indices)]
    private fun readerFontWeightLabelForProgress(progress: Int): String = READER_FONT_WEIGHT_LABELS[progress.coerceIn(READER_FONT_WEIGHT_LABELS.indices)]

    private fun buildReaderTypeface(familyKey: String, weight: Int): Typeface {
        val base = when (familyKey) {
            "sans-serif" -> Typeface.SANS_SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    private fun buildSpinnerAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.item_app_spinner_selected, items).also { it.setDropDownViewResource(R.layout.item_app_spinner_dropdown) }

    private fun indexOf(values: Array<String>, target: String, fallback: Int): Int = values.indexOf(target).let { if (it < 0) fallback else it }
    private fun labelForReaderPreset(value: String): String = when (value) { "forest" -> "护眼"; "night" -> "夜航"; else -> "纸控" }
    private fun labelForDoublePageMode(value: String): String = ReaderOptionCatalog.DOUBLE_PAGE_MODE_LABELS[indexOf(ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS, value, 0)]
    private fun labelForReaderFontFamily(value: String): String = when (value) { "sans-serif" -> "无衬线"; "monospace" -> "等宽体"; else -> "系统默认" }
    private fun labelForReaderFontWeight(value: Int): String = when { value <= 325 -> "细体"; value >= 550 -> "粗体"; else -> "标准" }

    companion object {
        private const val REQUEST_PICK_READER_BACKGROUND = 1003
        private val READER_THEME_KEYS = arrayOf("follow_app", "system", "light", "dark")
        private val READER_FONT_FAMILY_KEYS = arrayOf("system_default", "sans-serif", "monospace")
        private val READER_FONT_FAMILY_LABELS = arrayOf("系统默认", "无衬线", "等宽体")
        private val READER_FONT_WEIGHT_VALUES = intArrayOf(250, 400, 700)
        private val READER_FONT_WEIGHT_LABELS = arrayOf("细体", "标准", "粗体")
    }
}
