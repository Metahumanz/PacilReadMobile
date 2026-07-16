package com.metahumanz.pacilread

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.metahumanz.pacilread.importer.BookDuplicateDetector
import com.metahumanz.pacilread.importer.BookImportService
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager
import com.metahumanz.pacilread.sync.SyncDiffItem
import com.metahumanz.pacilread.sync.SyncDiffPreview
import com.metahumanz.pacilread.sync.WebDavBackupManager
import com.metahumanz.pacilread.sync.WebDavClient
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.tts.MimoTtsClient
import com.metahumanz.pacilread.tts.SystemTtsClient
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsScreenController(
    private val activity: Activity,
    private val host: Host?,
) {
    interface Host {
        fun openBookPicker(intent: Intent, requestCode: Int)
        fun openReader(bookId: Long)
        fun onSettingsSaved()
        fun onLibraryDataRestored()
        fun onThemeChanged()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val databaseHelper = JsonDatabase.getInstance(activity)
    private val settingsStore = SettingsStore(activity)
    private val webDavClient = WebDavClient(settingsStore)
    private val backupManager = WebDavBackupManager(activity, databaseHelper, settingsStore, webDavClient)
    private val readingStatsSyncManager = ReadingStatsSyncManager(activity, databaseHelper, settingsStore, webDavClient)
    private val importService = BookImportService(activity)
    private val testMimoTtsClient = MimoTtsClient()

    private var homeNavigationSettingsController: SettingsHomeNavigationController? = null
    private var readingStatsController: SettingsReadingStatsController? = null
    private var testSystemTtsClient: SystemTtsClient? = null

    private val statusText: TextView = activity.findViewById(R.id.text_status)
    private val databaseSizeText: TextView? = activity.findViewById(R.id.text_database_size)
    private val maintenanceSummaryText: TextView? = activity.findViewById(R.id.text_maintenance_summary)
    private val textAppVersion: TextView? = activity.findViewById(R.id.text_app_version)
    private val scrollChangelog: NestedScrollView? = activity.findViewById(R.id.scroll_changelog)
    private val optimizeDatabaseButton: Button? = activity.findViewById(R.id.button_optimize_database)
    private val fullBackupText: TextView? = activity.findViewById(R.id.text_backup_full)
    private val liteBackupText: TextView? = activity.findViewById(R.id.text_backup_lite)
    private val backupProgressLayout: View = activity.findViewById(R.id.layout_backup_progress)
    private val backupProgressBar: ProgressBar = activity.findViewById(R.id.progress_backup)
    private val backupProgressText: TextView = activity.findViewById(R.id.text_backup_progress)
    private val autoOpenCheck: CheckBox = activity.findViewById(R.id.check_auto_open)
    private val readerMenuAutoHideCheck: CheckBox = activity.findViewById(R.id.check_reader_menu_auto_hide)
    private val bookshelfShowAddEntryCheck: CheckBox = activity.findViewById(R.id.check_bookshelf_show_add_entry)
    private val webDavEnabledCheck: CheckBox = activity.findViewById(R.id.check_webdav_enabled)
    private val urlInput: EditText = activity.findViewById(R.id.input_webdav_url)
    private val dirInput: EditText = activity.findViewById(R.id.input_webdav_dir)
    private val settingsSubdirInput: EditText = activity.findViewById(R.id.input_webdav_settings_subdir)
    private val userInput: EditText = activity.findViewById(R.id.input_webdav_user)
    private val passwordInput: EditText = activity.findViewById(R.id.input_webdav_password)
    private val bookshelfProgressPrefetchLimitInput: EditText = activity.findViewById(R.id.input_webdav_bookshelf_progress_prefetch_limit)
    private val mimoApiKeyInput: EditText = activity.findViewById(R.id.input_mimo_api_key)
    private val appThemeSpinner: Spinner = activity.findViewById(R.id.spinner_app_theme_mode)
    private val readerUiThemeSpinner: Spinner = activity.findViewById(R.id.spinner_reader_ui_theme_mode)
    private val lightStyleYaobaiButton: Button? = activity.findViewById(R.id.button_light_style_yaobai)
    private val lightStyleYunbaiButton: Button? = activity.findViewById(R.id.button_light_style_yunbai)
    private val darkStyleYemuButton: Button? = activity.findViewById(R.id.button_dark_style_yemu)
    private val darkStyleJiyeButton: Button? = activity.findViewById(R.id.button_dark_style_jiye)
    private val readerOrientationSystemButton: Button? = activity.findViewById(R.id.button_reader_orientation_system)
    private val readerOrientationPortraitButton: Button? = activity.findViewById(R.id.button_reader_orientation_portrait)
    private val readerOrientationLandscapeButton: Button? = activity.findViewById(R.id.button_reader_orientation_landscape)
    private val ttsEngineSpinner: Spinner? = activity.findViewById(R.id.spinner_tts_engine)
    private val volumeKeyUpActionSpinner: Spinner? = activity.findViewById(R.id.spinner_volume_key_up_action)
    private val volumeKeyDownActionSpinner: Spinner? = activity.findViewById(R.id.spinner_volume_key_down_action)
    private val glassOpacitySeekBar: SeekBar = activity.findViewById(R.id.seek_glass_opacity)
    private val glassOpacityText: TextView = activity.findViewById(R.id.text_glass_opacity)
    private val testButton: Button = activity.findViewById(R.id.button_test_webdav)
    private val ttsTestButton: Button? = activity.findViewById(R.id.button_test_tts)
    private val fullBackupButton: Button = activity.findViewById(R.id.button_full_backup)
    private val fullRestoreButton: Button = activity.findViewById(R.id.button_full_restore)
    private val liteBackupButton: Button = activity.findViewById(R.id.button_lite_backup)
    private val liteRestoreButton: Button = activity.findViewById(R.id.button_lite_restore)
    private val webDavSyncBookshelfButton: Button = activity.findViewById(R.id.button_webdav_sync_bookshelf)
    private val webDavSyncFilesButton: Button = activity.findViewById(R.id.button_webdav_sync_files)
    private val webDavSyncUiButton: Button = activity.findViewById(R.id.button_webdav_sync_ui)
    private val webDavSyncThemesButton: Button = activity.findViewById(R.id.button_webdav_sync_themes)
    private val webDavSyncBackgroundsButton: Button = activity.findViewById(R.id.button_webdav_sync_backgrounds)
    private val webDavSyncReadingStatsButton: Button = activity.findViewById(R.id.button_webdav_sync_reading_stats)
    private val webDavCleanRemoteOrphansCheck: CheckBox? = activity.findViewById(R.id.check_webdav_clean_remote_orphans)
    private val webDavSyncOptionsLayout: View? = activity.findViewById(R.id.layout_webdav_sync_options)
    private val ttsMimoKeyLayout: View? = activity.findViewById(R.id.layout_tts_mimo_key)
    private val transitionFluidButton: Button? = activity.findViewById(R.id.button_transition_fluid)
    private val transitionSimpleButton: Button? = activity.findViewById(R.id.button_transition_simple)
    private val transitionMotionDescriptionText: TextView? = activity.findViewById(R.id.text_transition_motion_description)
    private val rulesListContainer: LinearLayout? = activity.findViewById(R.id.layout_rules_list)
    private val rulesEmptyText: TextView? = activity.findViewById(R.id.text_rules_empty)
    private val filterAllButton: Button? = activity.findViewById(R.id.button_rule_filter_all)
    private val filterGlobalButton: Button? = activity.findViewById(R.id.button_rule_filter_global)
    private val filterBookButton: Button? = activity.findViewById(R.id.button_rule_filter_book)

    private var ruleFilter = "all"
    private var bindingSettingsValues = false
    private var settingsBusy = false
    private var selectedLightStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI
    private var selectedDarkStyleVariant = ThemeModeHelper.DARK_STYLE_YEMU
    private var selectedReaderOrientationMode = READER_ORIENTATION_SYSTEM
    private var selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_FLUID
    private var hideBackupProgressRunnable: Runnable? = null

    init {
        setupChangelogScrolling()
        setupSharedControllers()
        setupThemeSpinners()
        setupStyleVariantButtons()
        setupReaderOrientationButtons()
        setupTransitionMotionButtons()
        bindCurrentValues()
        setupGlassOpacityControl()
        setupAutoSaveListeners()
        setupWebDavSyncButtons()
        refreshBackupLabels()
        setupActionButtons()
        setupRuleFilterButtons()
    }

    fun bindCurrentValues() {
        bindingSettingsValues = true
        autoOpenCheck.isChecked = settingsStore.isAutoOpenLastBook
        readerMenuAutoHideCheck.isChecked = settingsStore.isReaderMenuAutoHideEnabled
        bookshelfShowAddEntryCheck.isChecked = settingsStore.isBookshelfAddEntryVisible
        webDavEnabledCheck.isChecked = settingsStore.isWebDavEnabled
        webDavCleanRemoteOrphansCheck?.isChecked = settingsStore.isWebDavCleanRemoteOrphansEnabled
        urlInput.setText(settingsStore.webDavUrl)
        dirInput.setText(settingsStore.webDavDir)
        settingsSubdirInput.setText(settingsStore.webDavSettingsSubdir)
        userInput.setText(settingsStore.webDavUser)
        passwordInput.setText(settingsStore.webDavPassword)
        bookshelfProgressPrefetchLimitInput.setText(settingsStore.webDavBookshelfProgressPrefetchLimit.toString())
        mimoApiKeyInput.setText(settingsStore.ttsMimoApiKey)
        appThemeSpinner.setSelection(indexOf(APP_THEME_KEYS, settingsStore.appThemeMode, 0))
        readerUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.readerUiThemeMode, 0))
        selectedLightStyleVariant = settingsStore.appLightStyleVariant
        selectedDarkStyleVariant = settingsStore.appDarkStyleVariant
        selectedReaderOrientationMode = settingsStore.readerOrientationMode
        updateStyleVariantButtons()
        updateReaderOrientationButtons()
        selectedTransitionMotionMode = TransitionMotionModeHelper.resolveMode(settingsStore)
        updateTransitionMotionButtons()
        homeNavigationSettingsController?.bindValues()
        ttsEngineSpinner?.setSelection(indexOf(TTS_ENGINE_KEYS, settingsStore.ttsEngine, 0))
        volumeKeyUpActionSpinner?.setSelection(indexOf(VOLUME_KEY_ACTION_KEYS, settingsStore.volumeKeyUpAction, 1))
        volumeKeyDownActionSpinner?.setSelection(indexOf(VOLUME_KEY_ACTION_KEYS, settingsStore.volumeKeyDownAction, 2))
        glassOpacitySeekBar.progress = settingsStore.glassOpacityPercent - 20
        updateGlassOpacityLabel(settingsStore.glassOpacityPercent)
        updateWebDavSyncButtons()
        readingStatsController?.bindValues()
        updateTtsSettingsVisibility()
        refreshRulesList()
        textAppVersion?.text = "v${BuildConfig.VERSION_NAME}"
        bindingSettingsValues = false
        refreshStatusSummary()
        refreshDatabaseSizeLabel()
        refreshMaintenanceSummary()
    }

    fun saveSettings() {
        val previousAppBucket = ThemeModeHelper.getResolvedAppBucket(activity)
        val previousAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(activity)
        settingsStore.isAutoOpenLastBook = autoOpenCheck.isChecked
        settingsStore.isReaderMenuAutoHideEnabled = readerMenuAutoHideCheck.isChecked
        settingsStore.isBookshelfAddEntryVisible = bookshelfShowAddEntryCheck.isChecked
        settingsStore.isWebDavEnabled = webDavEnabledCheck.isChecked
        settingsStore.webDavUrl = urlInput.text.toString()
        settingsStore.webDavDir = dirInput.text.toString()
        settingsStore.webDavSettingsSubdir = settingsSubdirInput.text.toString()
        settingsStore.webDavUser = userInput.text.toString()
        settingsStore.webDavPassword = passwordInput.text.toString()
        settingsStore.webDavBookshelfProgressPrefetchLimit = readBookshelfProgressPrefetchLimit()
        ttsEngineSpinner?.let { settingsStore.ttsEngine = TTS_ENGINE_KEYS[it.selectedItemPosition] }
        settingsStore.ttsMimoApiKey = mimoApiKeyInput.text.toString()
        settingsStore.appThemeMode = APP_THEME_KEYS[appThemeSpinner.selectedItemPosition]
        settingsStore.readerUiThemeMode = READER_THEME_KEYS[readerUiThemeSpinner.selectedItemPosition]
        settingsStore.appLightStyleVariant = selectedLightStyleVariant
        settingsStore.appDarkStyleVariant = selectedDarkStyleVariant
        settingsStore.readerOrientationMode = selectedReaderOrientationMode
        settingsStore.transitionMotionMode = if (TransitionMotionModeHelper.isFluidAvailable()) selectedTransitionMotionMode else TransitionMotionModeHelper.MODE_SIMPLE
        homeNavigationSettingsController?.saveValues()
        readingStatsController?.saveValues()
        volumeKeyUpActionSpinner?.let { settingsStore.volumeKeyUpAction = VOLUME_KEY_ACTION_KEYS[it.selectedItemPosition] }
        volumeKeyDownActionSpinner?.let { settingsStore.volumeKeyDownAction = VOLUME_KEY_ACTION_KEYS[it.selectedItemPosition] }
        settingsStore.glassOpacityPercent = glassOpacitySeekBar.progress + 20
        settingsStore.isWebDavSyncBookshelfEnabled = webDavSyncBookshelfButton.isSelected
        settingsStore.isWebDavSyncFilesEnabled = webDavSyncFilesButton.isSelected
        settingsStore.isWebDavSyncUiSettingsEnabled = webDavSyncUiButton.isSelected
        settingsStore.isWebDavSyncThemesEnabled = webDavSyncThemesButton.isSelected
        settingsStore.isWebDavSyncBackgroundsEnabled = webDavSyncBackgroundsButton.isSelected
        settingsStore.isWebDavSyncReadingStatsEnabled = webDavSyncReadingStatsButton.isSelected
        webDavCleanRemoteOrphansCheck?.let { settingsStore.isWebDavCleanRemoteOrphansEnabled = it.isChecked }
        updateTtsSettingsVisibility()
        refreshStatusSummary()
        host?.onSettingsSaved()
        val nextAppBucket = ThemeModeHelper.getResolvedAppBucket(activity)
        val nextAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(activity)
        if (previousAppBucket != nextAppBucket || previousAppStyleVariant != nextAppStyleVariant) host?.onThemeChanged()
    }

    fun onResume() = refreshReadingStatsSummary(true)
    fun onPause() = persistSettingsIfReady()
    fun onDestroy() {
        hideBackupProgressRunnable?.let(mainHandler::removeCallbacks)
        hideBackupProgressRunnable = null
        testMimoTtsClient.cancel()
        testSystemTtsClient?.shutdown()
        executor.shutdownNow()
    }

    fun onBookPicked(uri: Uri?): Boolean {
        if (uri == null) return false
        importBook(uri)
        return true
    }

    fun refreshReadingStatsSummary(syncFirst: Boolean) {
        readingStatsController?.refreshSummary(syncFirst)
    }

    private fun setupChangelogScrolling() {
        var startY = 0f
        scrollChangelog?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - startY
                    val atBottom = dy < 0 && !view.canScrollVertically(1)
                    val atTop = dy > 0 && !view.canScrollVertically(-1)
                    if (atBottom || atTop) view.parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun setupSharedControllers() {
        homeNavigationSettingsController = SettingsHomeNavigationController(
            activity, settingsStore,
            object : SettingsHomeNavigationController.Callback {
                override fun onSettingsChanged() = handleSettingsChanged()
            },
        )
        readingStatsController = SettingsReadingStatsController(
            activity, databaseHelper, settingsStore, readingStatsSyncManager, executor,
            object : SettingsReadingStatsController.Callback {
                override fun isSettingsBusy() = settingsBusy
                override fun saveSettings() = this@SettingsScreenController.saveSettings()
                override fun setBusy(busy: Boolean) = this@SettingsScreenController.setBusy(busy)
                override fun setStatusText(text: String) { statusText.text = text }
                override fun showToast(text: String) = this@SettingsScreenController.showToast(text)
            },
        )
    }

    private fun setupActionButtons() {
        testButton.setOnClickListener { testWebDav() }
        ttsTestButton?.setOnClickListener { testTtsEngine() }
        optimizeDatabaseButton?.setOnClickListener { startDatabaseOptimization() }
        fullBackupButton.setOnClickListener { runWebDavAction("正在执行全量备份...") { backupManager.fullBackup(it) } }
        liteBackupButton.setOnClickListener { runWebDavAction("正在执行增量备份...") { backupManager.incrementalBackup(it) } }
        fullRestoreButton.setOnClickListener {
            confirmRestore("将先预览云端全量备份和本地数据的差异。恢复操作不可撤销，请确认云端备份可用。", { previewWebDavRestore(true, it) }, false)
        }
        liteRestoreButton.setOnClickListener {
            confirmRestore("将先预览云端增量备份和本地数据的差异。恢复操作不可撤销，请确认云端备份可用。", { previewWebDavRestore(false, it) }, false)
        }
    }

    private fun refreshBackupLabels() {
        fullBackupText?.text = "全量备份：最近一次 ${backupManager.lastFullBackupLabel()}"
        liteBackupText?.text = "增量备份：最近一次 ${backupManager.lastLiteBackupLabel()}"
    }

    private fun refreshDatabaseSizeLabel() {
        val target = databaseSizeText ?: return
        try { target.text = "本地存储占用：${databaseHelper.databaseSizeInfo}" } catch (_: Exception) {}
    }

    private fun readCurrentDatabaseSize(): String = try { databaseHelper.databaseSizeInfo } catch (_: Exception) { "未知" }

    private fun refreshMaintenanceSummary() {
        val target = maintenanceSummaryText ?: return
        try {
            target.text = "维护任务：${databaseHelper.pendingMaintenanceSummary}"
            optimizeDatabaseButton?.isEnabled = !settingsBusy && databaseHelper.hasPendingMaintenanceWork()
        } catch (_: Exception) {
            optimizeDatabaseButton?.isEnabled = false
        }
    }

    private fun startDatabaseOptimization() {
        val currentSize = readCurrentDatabaseSize()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        layout.addView(TextView(activity).apply {
            text = "请勿退出应用，优化过程中需要保持数据库锁定。"
            textSize = 13f
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_danger))
            setPadding(0, 0, 0, dp(12))
        })
        layout.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            setPadding(0, 0, 0, dp(12))
        })
        val phaseText = TextView(activity).apply {
            text = "准备中..."
            textSize = 14f
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
            setPadding(0, dp(4), 0, dp(4))
        }
        layout.addView(phaseText)
        val dialog = AlertDialog.Builder(activity).setTitle("优化数据库存储").setCancelable(false).setView(layout).create()
        dialog.show()
        setBusy(true)
        setAllButtonsEnabled(false)
        executor.execute {
            databaseHelper.runStorageMaintenanceWithProgress(object : JsonDatabase.MaintenanceProgressListener {
                override fun onPhaseStart(phaseName: String) = activity.runOnUiThread { phaseText.text = "正在${phaseName}…" }
                override fun onPhaseDone(phaseName: String) = activity.runOnUiThread { phaseText.text = "$phaseName 完成" }
                override fun onAllDone() {
                    val afterSize = readCurrentDatabaseSize()
                    activity.runOnUiThread {
                        dialog.dismiss(); setBusy(false); setAllButtonsEnabled(true)
                        refreshDatabaseSizeLabel(); refreshMaintenanceSummary()
                        showOptimizationResultDialog(currentSize, afterSize)
                    }
                }
                override fun onError(errorMessage: String) = activity.runOnUiThread {
                    dialog.dismiss(); setBusy(false); setAllButtonsEnabled(true)
                    refreshDatabaseSizeLabel(); refreshMaintenanceSummary(); showToast("优化失败: $errorMessage")
                }
            })
        }
    }

    private fun showOptimizationResultDialog(before: String, after: String) {
        val resultDialog = AlertDialog.Builder(activity)
            .setTitle("优化完成").setMessage("优化前：\n$before\n\n优化后：\n$after")
            .setPositiveButton("知道了", null).create()
        resultDialog.setOnShowListener { resultDialog.window?.setBackgroundDrawableResource(R.drawable.bg_app_dialog) }
        resultDialog.show()
    }

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).roundToInt()

    private fun setAllButtonsEnabled(enabled: Boolean) {
        optimizeDatabaseButton?.isEnabled = enabled && hasPendingMaintenanceWork()
        fullBackupButton.isEnabled = enabled
        fullRestoreButton.isEnabled = enabled
        liteBackupButton.isEnabled = enabled
        liteRestoreButton.isEnabled = enabled
        testButton.isEnabled = enabled
        ttsTestButton?.isEnabled = enabled
    }

    private fun setupThemeSpinners() {
        appThemeSpinner.adapter = ArrayAdapter(activity, R.layout.item_app_spinner_selected, arrayOf("跟随系统", "浅色", "深色")).apply {
            setDropDownViewResource(R.layout.item_app_spinner_dropdown)
        }
        readerUiThemeSpinner.adapter = ArrayAdapter(activity, R.layout.item_app_spinner_selected, arrayOf("跟随应用", "跟随系统", "浅色", "深色")).apply {
            setDropDownViewResource(R.layout.item_app_spinner_dropdown)
        }
        ttsEngineSpinner?.adapter = ArrayAdapter(activity, R.layout.item_app_spinner_selected, TTS_ENGINE_LABELS).apply {
            setDropDownViewResource(R.layout.item_app_spinner_dropdown)
        }
        val autoSaveSpinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = handleSettingsChanged()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        appThemeSpinner.onItemSelectedListener = autoSaveSpinnerListener
        readerUiThemeSpinner.onItemSelectedListener = autoSaveSpinnerListener
        ttsEngineSpinner?.onItemSelectedListener = autoSaveSpinnerListener
        volumeKeyUpActionSpinner?.let {
            it.adapter = ArrayAdapter(activity, R.layout.item_app_spinner_selected, VOLUME_KEY_ACTION_LABELS).apply {
                setDropDownViewResource(R.layout.item_app_spinner_dropdown)
            }
            it.onItemSelectedListener = autoSaveSpinnerListener
        }
        volumeKeyDownActionSpinner?.let {
            it.adapter = ArrayAdapter(activity, R.layout.item_app_spinner_selected, VOLUME_KEY_ACTION_LABELS).apply {
                setDropDownViewResource(R.layout.item_app_spinner_dropdown)
            }
            it.onItemSelectedListener = autoSaveSpinnerListener
        }
    }

    private fun setupGlassOpacityControl() {
        glassOpacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateGlassOpacityLabel(progress + 20)
                if (fromUser) handleSettingsChanged()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = handleSettingsChanged()
        })
    }

    private fun setupStyleVariantButtons() {
        lightStyleYaobaiButton?.setOnClickListener { selectLightStyleVariant(ThemeModeHelper.LIGHT_STYLE_YAOBAI) }
        lightStyleYunbaiButton?.setOnClickListener { selectLightStyleVariant(ThemeModeHelper.LIGHT_STYLE_YUNBAI) }
        darkStyleYemuButton?.setOnClickListener { selectDarkStyleVariant(ThemeModeHelper.DARK_STYLE_YEMU) }
        darkStyleJiyeButton?.setOnClickListener { selectDarkStyleVariant(ThemeModeHelper.DARK_STYLE_JIYE) }
    }

    private fun setupReaderOrientationButtons() {
        readerOrientationSystemButton?.setOnClickListener { selectReaderOrientationMode(READER_ORIENTATION_SYSTEM) }
        readerOrientationPortraitButton?.setOnClickListener { selectReaderOrientationMode(READER_ORIENTATION_PORTRAIT) }
        readerOrientationLandscapeButton?.setOnClickListener { selectReaderOrientationMode(READER_ORIENTATION_LANDSCAPE) }
    }

    private fun setupTransitionMotionButtons() {
        transitionFluidButton?.setOnClickListener { selectTransitionMotionMode(TransitionMotionModeHelper.MODE_FLUID) }
        transitionSimpleButton?.setOnClickListener { selectTransitionMotionMode(TransitionMotionModeHelper.MODE_SIMPLE) }
    }

    private fun selectTransitionMotionMode(mode: String) {
        if (mode == TransitionMotionModeHelper.MODE_FLUID && !TransitionMotionModeHelper.isFluidAvailable()) {
            selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_SIMPLE
            updateTransitionMotionButtons()
            return
        }
        selectedTransitionMotionMode = mode
        updateTransitionMotionButtons()
        handleSettingsChanged()
    }

    private fun updateTransitionMotionButtons() {
        val fluidAvailable = TransitionMotionModeHelper.isFluidAvailable()
        if (!fluidAvailable) selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_SIMPLE
        val fluid = selectedTransitionMotionMode == TransitionMotionModeHelper.MODE_FLUID
        AppUiUtils.styleToggleButton(activity, transitionFluidButton, fluid)
        AppUiUtils.styleToggleButton(activity, transitionSimpleButton, !fluid)
        transitionFluidButton?.let {
            it.isEnabled = fluidAvailable
            it.alpha = if (fluidAvailable) 1f else 0.55f
        }
        transitionMotionDescriptionText?.text = if (fluidAvailable)
            "流动会使用来源贴合和预测返回动画；简洁使用更稳定的基础转场。"
        else "当前系统低于 Android 14，默认使用简洁转场；流动动效需要 Android 14 及以上的预测返回支持。"
    }

    private fun setupAutoSaveListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = handleSettingsChanged()
        }
        urlInput.addTextChangedListener(watcher)
        dirInput.addTextChangedListener(watcher)
        settingsSubdirInput.addTextChangedListener(watcher)
        userInput.addTextChangedListener(watcher)
        passwordInput.addTextChangedListener(watcher)
        bookshelfProgressPrefetchLimitInput.addTextChangedListener(watcher)
        mimoApiKeyInput.addTextChangedListener(watcher)
        autoOpenCheck.setOnCheckedChangeListener { _, _ -> handleSettingsChanged() }
        readerMenuAutoHideCheck.setOnCheckedChangeListener { _, _ -> handleSettingsChanged() }
        bookshelfShowAddEntryCheck.setOnCheckedChangeListener { _, _ -> handleSettingsChanged() }
        webDavEnabledCheck.setOnCheckedChangeListener { _, _ -> handleSettingsChanged() }
        webDavCleanRemoteOrphansCheck?.setOnCheckedChangeListener { _, _ -> handleSettingsChanged() }
    }

    private fun setupWebDavSyncButtons() {
        webDavSyncBookshelfButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncBookshelfButton) }
        webDavSyncFilesButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncFilesButton) }
        webDavSyncUiButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncUiButton) }
        webDavSyncThemesButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncThemesButton) }
        webDavSyncBackgroundsButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncBackgroundsButton) }
        webDavSyncReadingStatsButton.setOnClickListener { toggleWebDavSyncButton(webDavSyncReadingStatsButton) }
    }

    private fun handleSettingsChanged() {
        if (bindingSettingsValues || settingsBusy) return
        saveSettings()
    }

    private fun refreshStatusSummary() {
        if (settingsBusy) return
        updateWebDavSyncOptionsVisibility()
        if (!settingsStore.isWebDavEnabled) {
            statusText.text = "当前未启用云同步"
            return
        }
        statusText.text = "已启用自动进度同步\n手动备份范围：${buildWebDavScopeSummary()}" +
            "\n书架进度预取：${buildBookshelfProgressPrefetchSummary()}" +
            "\nAndroid 设置快照：${buildWebDavSettingsSnapshotSummary()}" +
            "\n阅读时长累计：${if (settingsStore.isWebDavSyncReadingStatsEnabled) "已启用" else "已关闭"}" +
            "\n远端清理：${if (settingsStore.isWebDavCleanRemoteOrphansEnabled) "备份后执行" else "已关闭"}"
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/epub+zip", "application/pdf", "application/octet-stream"))
        }
        host?.openBookPicker(intent, REQUEST_PICK_BOOK)
    }

    private fun importBook(uri: Uri) {
        setBusy(true)
        statusText.text = "正在检查书籍..."
        executor.execute {
            try {
                val prepared = importService.prepareFromUri(uri)
                val existing = ArrayList<BookDuplicateDetector.Candidate>()
                for (book in databaseHelper.backfillMissingContentHashes()) {
                    existing.add(BookDuplicateDetector.Candidate("existing-${book.id}", book.title, book.author, book.contentSha256))
                }
                val incoming = listOf(BookDuplicateDetector.Candidate("incoming", prepared.title, prepared.author, prepared.contentSha256))
                val duplicate = BookDuplicateDetector.detect(existing, incoming).isNotEmpty()
                activity.runOnUiThread {
                    if (duplicate) {
                        AlertDialog.Builder(activity)
                            .setTitle("发现重复书籍")
                            .setMessage("《${prepared.title}》与书架中的书籍内容或书名作者重复。")
                            .setNegativeButton("跳过重复") { _, _ ->
                                prepared.deleteLocalCopy(); setBusy(false); statusText.text = "已跳过重复书籍"
                            }
                            .setPositiveButton("仍然导入") { _, _ -> continueSettingsImport(prepared) }
                            .setOnCancelListener { prepared.deleteLocalCopy(); setBusy(false) }
                            .show()
                    } else continueSettingsImport(prepared)
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    setBusy(false); statusText.text = "导入失败: ${error.message}"; showToast("导入失败")
                }
            }
        }
    }

    private fun continueSettingsImport(prepared: BookImportService.PreparedImport) {
        statusText.text = "正在导入书籍..."
        executor.execute {
            try {
                val bookId = databaseHelper.insertImportedBook(importService.parsePrepared(prepared, false))
                activity.runOnUiThread {
                    setBusy(false); statusText.text = "书籍已导入"; showToast("导入成功"); host?.openReader(bookId)
                }
            } catch (error: Exception) {
                prepared.deleteLocalCopy()
                activity.runOnUiThread {
                    setBusy(false); statusText.text = "导入失败: ${error.message}"; showToast("导入失败")
                }
            }
        }
    }

    private fun testWebDav() {
        saveSettings(); setBusy(true); statusText.text = "正在探测并初始化目录..."
        executor.execute {
            try {
                val response = webDavClient.probe()
                activity.runOnUiThread { setBusy(false); statusText.text = "连接成功，HTTP ${response.code}"; showToast("WebDAV 可用") }
            } catch (error: Exception) {
                activity.runOnUiThread { setBusy(false); statusText.text = "连接失败: ${error.message}"; showToast("WebDAV 探测失败") }
            }
        }
    }

    private fun testTtsEngine() {
        saveSettings()
        val engine = settingsStore.ttsEngine
        if (engine == "mimo" && settingsStore.ttsMimoApiKey.isBlank()) {
            showToast("请先填写 MiMo API Key")
            return
        }
        val engineLabel = if (engine == "mimo") "MiMo" else "系统 TTS"
        setBusy(true)
        ttsTestButton?.text = "正在测试朗读..."
        if (engine == "mimo") {
            executor.execute {
                try {
                    testMimoTtsClient.speak(TTS_TEST_TEXT, settingsStore.ttsMimoApiKey, settingsStore.ttsMimoVoice, settingsStore.ttsRate)
                    activity.runOnUiThread { setBusy(false); restoreTtsTestButton(); showToast("$engineLabel 测试朗读完成") }
                } catch (error: Exception) {
                    activity.runOnUiThread { setBusy(false); restoreTtsTestButton(); showToast("$engineLabel 测试朗读失败: ${error.message}") }
                }
            }
        } else {
            getTestSystemTtsClient().speak(TTS_TEST_TEXT, settingsStore.ttsRate, object : SystemTtsClient.SpeakCallback {
                override fun onStart() = Unit
                override fun onDone() = activity.runOnUiThread { setBusy(false); restoreTtsTestButton(); showToast("$engineLabel 测试朗读完成") }
                override fun onError(message: String) = activity.runOnUiThread { setBusy(false); restoreTtsTestButton(); showToast("$engineLabel 测试朗读失败: $message") }
            })
        }
    }

    private fun confirmRestore(message: String, action: BackgroundAction, refreshLibraryOnSuccess: Boolean) {
        AlertDialog.Builder(activity).setTitle("确认恢复").setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> runWebDavAction("正在恢复数据...", action, refreshLibraryOnSuccess) }.show()
    }

    @Throws(Exception::class)
    private fun previewWebDavRestore(full: Boolean, listener: WebDavBackupManager.StatusListener) {
        val preview = if (full) {
            backupManager.previewFullRestore(listener)
        } else {
            backupManager.previewIncrementalRestore(listener)
        }
        activity.runOnUiThread { showSyncDiffDialog(preview) }
    }

    private fun showSyncDiffDialog(preview: SyncDiffPreview) {
        val message = StringBuilder().append("本地新增 ").append(preview.localCount())
            .append(" · 云端新增 ").append(preview.remoteCount())
            .append(" · 冲突 ").append(preview.conflictCount())
            .append(" · 未变化 ").append(preview.unchangedCount())
        var shown = 0
        for (item in preview.items) {
            if (item.status != SyncDiffItem.STATUS_CONFLICT) continue
            if (shown == 0) message.append("\n\n冲突项：")
            if (shown >= 8) { message.append("\n还有更多冲突项..."); break }
            message.append('\n').append(item.entityType).append(" · ").append(item.title).append('\n').append(item.summary)
            shown++
        }
        AlertDialog.Builder(activity).setTitle("WebDAV 差异预览").setMessage(message.toString())
            .setNegativeButton("保留本地") { _, _ ->
                runWebDavAction("正在保留本地数据...", { backupManager.applySyncResolution(preview, WebDavBackupManager.RESOLUTION_LOCAL, it) }, true)
            }
            .setNeutralButton("全部用远端") { _, _ ->
                runWebDavAction("正在应用远端数据...", { backupManager.applySyncResolution(preview, WebDavBackupManager.RESOLUTION_REMOTE, it) }, true)
            }
            .setPositiveButton("按更新时间合并") { _, _ ->
                runWebDavAction("正在合并数据...", { backupManager.applySyncResolution(preview, WebDavBackupManager.RESOLUTION_MERGE, it) }, true)
            }.show()
    }

    private fun runWebDavAction(startMessage: String, action: BackgroundAction) = runWebDavAction(startMessage, action, false)

    private fun runWebDavAction(startMessage: String, action: BackgroundAction, refreshLibraryOnSuccess: Boolean) {
        saveSettings(); setBusy(true); showBackupProgress(startMessage)
        executor.execute {
            try {
                action.run(object : WebDavBackupManager.StatusListener {
                    override fun onStatus(status: String) {
                        activity.runOnUiThread { showBackupProgress(status) }
                    }

                    override fun onProgress(current: Int, total: Int) {
                        activity.runOnUiThread { updateBackupProgress(current, total) }
                    }
                })
                activity.runOnUiThread {
                    setBusy(false); bindCurrentValues(); refreshBackupLabels(); finishBackupProgress("操作完成，设置已自动保存", false)
                    if (refreshLibraryOnSuccess) host?.onLibraryDataRestored()
                    showToast("WebDAV 操作已完成")
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    val message = readableError(error); Log.w(TAG, "WebDAV 操作失败", error); setBusy(false)
                    finishBackupProgress("操作失败: $message", true); showToast("操作失败: $message")
                }
            }
        }
    }

    private fun showBackupProgress(message: String) {
        hideBackupProgressRunnable?.let(mainHandler::removeCallbacks)
        hideBackupProgressRunnable = null
        backupProgressLayout.visibility = View.VISIBLE
        backupProgressText.text = message
        backupProgressBar.visibility = View.VISIBLE
        if (!PROGRESS_FRACTION_PATTERN.containsMatchIn(message)) {
            backupProgressBar.isIndeterminate = true
        }
    }

    private fun updateBackupProgress(current: Int, total: Int) {
        if (total <= 0) {
            backupProgressBar.isIndeterminate = true
            return
        }
        backupProgressLayout.visibility = View.VISIBLE
        backupProgressBar.visibility = View.VISIBLE
        backupProgressBar.isIndeterminate = false
        backupProgressBar.max = total
        backupProgressBar.progress = current.coerceIn(0, total)
    }

    private fun finishBackupProgress(message: String, failed: Boolean) {
        backupProgressLayout.visibility = View.VISIBLE
        backupProgressText.text = message
        backupProgressBar.isIndeterminate = false
        backupProgressBar.visibility = View.GONE
        hideBackupProgressRunnable?.let(mainHandler::removeCallbacks)
        hideBackupProgressRunnable = Runnable {
            backupProgressLayout.visibility = View.GONE
            hideBackupProgressRunnable = null
        }.also { mainHandler.postDelayed(it, if (failed) 6000L else 4000L) }
    }

    private fun setBusy(busy: Boolean) {
        settingsBusy = busy
        testButton.isEnabled = !busy
        ttsTestButton?.isEnabled = !busy
        ttsEngineSpinner?.isEnabled = !busy
        mimoApiKeyInput.isEnabled = !busy
        optimizeDatabaseButton?.isEnabled = !busy && hasPendingMaintenanceWork()
        fullBackupButton.isEnabled = !busy
        fullRestoreButton.isEnabled = !busy
        liteBackupButton.isEnabled = !busy
        liteRestoreButton.isEnabled = !busy
        webDavSyncBookshelfButton.isEnabled = !busy
        webDavSyncFilesButton.isEnabled = !busy
        webDavSyncUiButton.isEnabled = !busy
        webDavSyncThemesButton.isEnabled = !busy
        webDavSyncBackgroundsButton.isEnabled = !busy
        webDavSyncReadingStatsButton.isEnabled = !busy
        webDavCleanRemoteOrphansCheck?.isEnabled = !busy
        readingStatsController?.setBusy(busy)
    }

    private fun hasPendingMaintenanceWork(): Boolean = try { databaseHelper.hasPendingMaintenanceWork() } catch (_: Exception) { false }
    private fun persistSettingsIfReady() = saveSettings()
    private fun showToast(text: String) = AppUiUtils.showToast(activity, text)

    private fun readableError(error: Throwable?): String {
        if (error == null) return "未知错误"
        var message = error.message
        if (message.isNullOrBlank()) message = error.cause?.message
        if (message.isNullOrBlank()) message = error.javaClass.simpleName
        return if (message.length > 160) message.substring(0, 160) + "..." else message
    }

    private fun readBookshelfProgressPrefetchLimit(): Int = try {
        bookshelfProgressPrefetchLimitInput.text.toString().toInt()
    } catch (_: Exception) {
        settingsStore.webDavBookshelfProgressPrefetchLimit
    }

    private fun buildBookshelfProgressPrefetchSummary(): String {
        val limit = settingsStore.webDavBookshelfProgressPrefetchLimit
        return if (limit == 0) "已关闭" else "前 $limit 本"
    }

    @Synchronized
    private fun getTestSystemTtsClient(): SystemTtsClient {
        testSystemTtsClient?.let { return it }
        return SystemTtsClient(activity).also { testSystemTtsClient = it }
    }

    private fun restoreTtsTestButton() { ttsTestButton?.text = "测试朗读" }
    private fun updateGlassOpacityLabel(opacityPercent: Int) {
        glassOpacityText.text = String.format(Locale.SIMPLIFIED_CHINESE, "阅读菜单与弹窗当前不透明度 %d%%", opacityPercent)
    }

    private fun toggleWebDavSyncButton(button: Button) {
        button.isSelected = !button.isSelected
        AppUiUtils.styleToggleButton(activity, button, button.isSelected)
        handleSettingsChanged()
    }

    private fun updateWebDavSyncButtons() {
        AppUiUtils.styleToggleButton(activity, webDavSyncBookshelfButton, settingsStore.isWebDavSyncBookshelfEnabled)
        AppUiUtils.styleToggleButton(activity, webDavSyncFilesButton, settingsStore.isWebDavSyncFilesEnabled)
        AppUiUtils.styleToggleButton(activity, webDavSyncUiButton, settingsStore.isWebDavSyncUiSettingsEnabled)
        AppUiUtils.styleToggleButton(activity, webDavSyncThemesButton, settingsStore.isWebDavSyncThemesEnabled)
        AppUiUtils.styleToggleButton(activity, webDavSyncBackgroundsButton, settingsStore.isWebDavSyncBackgroundsEnabled)
        AppUiUtils.styleToggleButton(activity, webDavSyncReadingStatsButton, settingsStore.isWebDavSyncReadingStatsEnabled)
    }

    private fun selectLightStyleVariant(styleVariant: String) {
        if (styleVariant == selectedLightStyleVariant) return
        selectedLightStyleVariant = SettingsStore.normalizeAppLightStyleVariant(styleVariant)
        updateStyleVariantButtons(); handleSettingsChanged()
    }

    private fun selectDarkStyleVariant(styleVariant: String) {
        if (styleVariant == selectedDarkStyleVariant) return
        selectedDarkStyleVariant = SettingsStore.normalizeAppDarkStyleVariant(styleVariant)
        updateStyleVariantButtons(); handleSettingsChanged()
    }

    private fun selectReaderOrientationMode(mode: String) {
        val normalized = SettingsStore.normalizeReaderOrientationMode(mode)
        if (normalized == selectedReaderOrientationMode) return
        selectedReaderOrientationMode = normalized
        updateReaderOrientationButtons(); handleSettingsChanged()
    }

    private fun updateStyleVariantButtons() {
        AppUiUtils.styleToggleButton(activity, lightStyleYaobaiButton, selectedLightStyleVariant == ThemeModeHelper.LIGHT_STYLE_YAOBAI)
        AppUiUtils.styleToggleButton(activity, lightStyleYunbaiButton, selectedLightStyleVariant == ThemeModeHelper.LIGHT_STYLE_YUNBAI)
        AppUiUtils.styleToggleButton(activity, darkStyleYemuButton, selectedDarkStyleVariant == ThemeModeHelper.DARK_STYLE_YEMU)
        AppUiUtils.styleToggleButton(activity, darkStyleJiyeButton, selectedDarkStyleVariant == ThemeModeHelper.DARK_STYLE_JIYE)
    }

    private fun updateReaderOrientationButtons() {
        AppUiUtils.styleToggleButton(activity, readerOrientationSystemButton, selectedReaderOrientationMode == READER_ORIENTATION_SYSTEM)
        AppUiUtils.styleToggleButton(activity, readerOrientationPortraitButton, selectedReaderOrientationMode == READER_ORIENTATION_PORTRAIT)
        AppUiUtils.styleToggleButton(activity, readerOrientationLandscapeButton, selectedReaderOrientationMode == READER_ORIENTATION_LANDSCAPE)
    }

    private fun updateWebDavSyncOptionsVisibility() {
        webDavSyncOptionsLayout?.visibility = if (webDavEnabledCheck.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateTtsSettingsVisibility() {
        ttsMimoKeyLayout?.visibility = if (settingsStore.ttsEngine == "mimo") View.VISIBLE else View.GONE
    }

    private fun buildWebDavScopeSummary(): String {
        val items = ArrayList<String>()
        if (settingsStore.isWebDavSyncBookshelfEnabled) items.add("书架内容")
        if (settingsStore.isWebDavSyncFilesEnabled) items.add("书籍文件")
        if (settingsStore.isWebDavSyncUiSettingsEnabled) items.add("界面设置")
        if (settingsStore.isWebDavSyncThemesEnabled) items.add("阅读主题")
        if (settingsStore.isWebDavSyncBackgroundsEnabled) items.add("背景图片")
        return if (items.isEmpty()) "未选择" else items.joinToString(" / ")
    }

    private fun buildWebDavSettingsSnapshotSummary() = settingsStore.webDavDir + settingsStore.webDavSettingsSubdir + "android-settings.json"

    private fun setupRuleFilterButtons() {
        if (filterAllButton == null || filterGlobalButton == null || filterBookButton == null) return
        filterAllButton.setOnClickListener { selectRuleFilter("all") }
        filterGlobalButton.setOnClickListener { selectRuleFilter("global") }
        filterBookButton.setOnClickListener { selectRuleFilter("book") }
    }

    private fun selectRuleFilter(filter: String) {
        ruleFilter = filter
        AppUiUtils.styleToggleButton(activity, filterAllButton, filter == "all")
        AppUiUtils.styleToggleButton(activity, filterGlobalButton, filter == "global")
        AppUiUtils.styleToggleButton(activity, filterBookButton, filter == "book")
        refreshRulesList()
    }

    private fun refreshRulesList() {
        val container = rulesListContainer ?: return
        val emptyText = rulesEmptyText ?: return
        container.removeAllViews()
        val rules = ArrayList(databaseHelper.rulesMutable)
        val bookTitles = HashMap<Long, String?>()
        for (book in databaseHelper.books) bookTitles[book.id] = book.title
        val filtered = ArrayList<ReplacementRuleRecord>()
        for (rule in rules) {
            if (ruleFilter == "all" || ruleFilter == "global" && rule.scope == "global" || ruleFilter == "book" && rule.scope == "book") {
                filtered.add(rule)
            }
        }
        if (filtered.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        for (index in filtered.indices) {
            val rule = filtered[index]
            val row = buildRuleRow(rule, bookTitles, index > 0)
            row.tag = rule
            row.setOnClickListener { toggleRuleActive(it.tag as ReplacementRuleRecord) }
            row.setOnLongClickListener { confirmDeleteRule(it.tag as ReplacementRuleRecord); true }
            row.alpha = if (rule.active) 1f else 0.5f
            container.addView(row)
        }
    }

    private fun buildRuleRow(rule: ReplacementRuleRecord, bookTitles: Map<Long, String?>, showDivider: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        if (showDivider) {
            val divider = View(activity).apply { setBackgroundColor(ThemeModeHelper.resolveColor(activity, R.color.app_border)) }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) }
            row.addView(divider, 0, params)
        }
        val replacement = if (rule.replacement.isNullOrEmpty()) "(删除)" else rule.replacement
        val replacementSuffix = if (rule.regex) "  [正则]" else ""
        row.addView(TextView(activity).apply {
            textSize = 13f
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_primary))
            text = "${rule.pattern}  →  $replacement$replacementSuffix"
        })
        row.addView(TextView(activity).apply {
            textSize = 11f
            setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary))
            val meta = StringBuilder(if (rule.scope == "global") "全局" else "单书")
            if (rule.scope == "book" && rule.bookId != null) {
                val id = rule.bookId!!
                meta.append(" - ").append(bookTitles[id] ?: "#$id")
            }
            if (rule.regex) meta.append(" - 正则")
            meta.append(if (rule.active) " - 已启用" else " - 已停用")
            text = meta.toString()
        })
        return row
    }

    private fun toggleRuleActive(rule: ReplacementRuleRecord) {
        databaseHelper.toggleReplacementRule(rule.id, !rule.active)
        refreshRulesList()
    }

    private fun confirmDeleteRule(rule: ReplacementRuleRecord) {
        val sourcePattern = rule.pattern!!
        val pattern = if (sourcePattern.length > 30) sourcePattern.substring(0, 30) + "..." else sourcePattern
        AlertDialog.Builder(activity).setTitle("删除替换规则").setMessage("确定要删除规则 \"$pattern\" 吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> databaseHelper.deleteReplacementRule(rule.id); refreshRulesList() }
            .show()
    }

    private fun interface BackgroundAction {
        @Throws(Exception::class)
        fun run(listener: WebDavBackupManager.StatusListener)
    }

    private fun indexOf(values: Array<String>, target: String, fallback: Int): Int {
        val index = values.indexOf(target)
        return if (index >= 0) index else fallback
    }

    companion object {
        private const val TAG = "SettingsScreen"
        const val REQUEST_PICK_BOOK = 3001
        private val APP_THEME_KEYS = arrayOf("system", "light", "dark")
        private val READER_THEME_KEYS = arrayOf("follow_app", "system", "light", "dark")
        private val TTS_ENGINE_KEYS = arrayOf("system", "mimo")
        private val TTS_ENGINE_LABELS = arrayOf("系统 TTS", "小米 MiMo")
        private const val TTS_TEST_TEXT = "这是一段听书测试，用来确认当前朗读引擎可以正常播放。"
        private val VOLUME_KEY_ACTION_KEYS = arrayOf("system", "page_up", "page_down")
        private val VOLUME_KEY_ACTION_LABELS = arrayOf("系统音量", "上一页", "下一页")
        private const val READER_ORIENTATION_SYSTEM = "system"
        private const val READER_ORIENTATION_PORTRAIT = "portrait"
        private const val READER_ORIENTATION_LANDSCAPE = "landscape"
        private val PROGRESS_FRACTION_PATTERN = Regex("\\d+\\s*/\\s*\\d+")
    }
}
