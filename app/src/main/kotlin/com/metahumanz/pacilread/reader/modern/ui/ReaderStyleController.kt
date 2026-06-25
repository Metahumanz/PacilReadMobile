package com.metahumanz.pacilread.reader.modern.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.metahumanz.pacilread.reader.JustifiedPageTextView
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette
import com.metahumanz.pacilread.reader.modern.tts.ReaderTtsController
import com.metahumanz.pacilread.util.FileAssetHelper
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ReaderStyleController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
) {
    private var chrome: ReaderChromeController? = null
    private var paging: ReaderPagingAnimator? = null
    @Suppress("unused")
    private var content: ReaderContentController? = null
    private var tts: ReaderTtsController? = null

    fun attachControllers(
        chrome: ReaderChromeController?,
        paging: ReaderPagingAnimator?,
        content: ReaderContentController?,
        tts: ReaderTtsController?,
    ) {
        this.chrome = chrome
        this.paging = paging
        this.content = content
        this.tts = tts
    }

    fun applyReaderSettings() {
        val attachedChrome = chrome!!
        val attachedPaging = paging!!
        val palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore)
        val bodyTypeface = buildReaderTypeface(
            runtime.settingsStore.readerFontFamily,
            runtime.settingsStore.readerFontWeight,
        )
        val titleTypeface = buildReaderTypeface(
            runtime.settingsStore.readerFontFamily,
            max(600, min(900, runtime.settingsStore.readerFontWeight + 200)),
        )
        val resolvedTextColor = resolveReaderTextColor(palette)
        state.currentReaderPageColor = palette.pageColor
        state.currentReaderTextColor = resolvedTextColor
        applyDoublePageVisibility()
        views.readerRoot.setBackgroundColor(palette.backgroundColor)
        attachedChrome.applyReaderMenuPalette(palette, resolvedTextColor)
        views.pageCurrent.setBackgroundColor(Color.TRANSPARENT)
        views.pageIncoming.setBackgroundColor(Color.TRANSPARENT)

        val leftPadding = ui.dp(runtime.settingsStore.leftPaddingDp)
        val rightPadding = ui.dp(runtime.settingsStore.rightPaddingDp)
        val topPadding = ui.dp(runtime.settingsStore.topPaddingDp) +
            state.readerContentInsetTop + computeHudReservedTopPx()
        val bottomPadding = ui.dp(runtime.settingsStore.bottomPaddingDp) +
            state.readerContentInsetBottom + computeHudReservedBottomPx()
        (views.pageCurrent as ViewGroup).setPadding(leftPadding, topPadding, rightPadding, bottomPadding)
        (views.pageIncoming as ViewGroup).setPadding(leftPadding, topPadding, rightPadding, bottomPadding)

        stylePageTitleView(views.pageTitleCurrent, titleTypeface, resolvedTextColor)
        stylePageTitleView(views.pageTitleCurrentRight, titleTypeface, resolvedTextColor)
        stylePageTitleView(views.pageTitleIncoming, titleTypeface, resolvedTextColor)
        stylePageTitleView(views.pageTitleIncomingRight, titleTypeface, resolvedTextColor)
        stylePageBodyView(views.pageBodyCurrent, bodyTypeface, resolvedTextColor)
        stylePageBodyView(views.pageBodyCurrentRight, bodyTypeface, resolvedTextColor)
        stylePageBodyView(views.pageBodyIncoming, bodyTypeface, resolvedTextColor)
        stylePageBodyView(views.pageBodyIncomingRight, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudTopLeft, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudTopCenter, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudTopRight, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudBottomLeft, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudBottomCenter, bodyTypeface, resolvedTextColor)
        styleHudTextView(views.hudBottomRight, bodyTypeface, resolvedTextColor)

        val titleGravity = if (runtime.settingsStore.chapterTitleAlignment == "center") Gravity.CENTER else Gravity.LEFT
        views.pageTitleCurrent.gravity = titleGravity
        views.pageTitleCurrentRight.gravity = titleGravity
        views.pageTitleIncoming.gravity = titleGravity
        views.pageTitleIncomingRight.gravity = titleGravity

        attachedPaging.invalidatePreparedPagingSnapshots()
        tts?.updateTtsHighlight()
        if (runtime.settingsStore.isKeepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyBackgroundImage()
        attachedChrome.updateReaderLayoutInsets()
        views.pageCurrent.requestLayout()
        views.pageIncoming.requestLayout()
        views.pageBodyCurrent.requestLayout()
        views.pageBodyCurrentRight.requestLayout()
        views.pageBodyIncoming.requestLayout()
        views.pageBodyIncomingRight.requestLayout()
        attachedChrome.updateSystemBarsVisibility(state.controlsVisible)
        attachedChrome.applyGlassOpacity()
        attachedChrome.updateReaderHud()
        attachedPaging.invalidatePreparedPagingSnapshots()
    }

    fun attachBackground(uri: Uri?) {
        runtime.safeExecute(Runnable {
            try {
                if (!activity.isReaderActive) return@Runnable
                val oldPath = runtime.settingsStore.readerBackgroundPath
                val newFile = FileAssetHelper.copyUriToFolder(activity, uri!!, "backgrounds", "reader_bg")
                if (!oldPath.isNullOrBlank()) FileAssetHelper.deleteIfExists(oldPath)
                runtime.settingsStore.readerBackgroundPath = newFile.absolutePath
                activity.runOnReaderUiThread(::applyReaderSettings)
            } catch (error: Exception) {
                activity.runOnReaderUiThread { ui.showToast("设置背景失败: ${error.message}") }
            }
        }, "attach reader background")
    }

    fun applyBackgroundImage() {
        val path = runtime.settingsStore.readerBackgroundPath
        val palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore)
        if (!shouldUseCustomBackground(path)) {
            applyBuiltInBackground(palette)
            return
        }
        val bitmap = BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            applyBuiltInBackground(palette)
            return
        }
        views.readerBackgroundImage.setImageBitmap(bitmap)
        views.readerBackgroundImage.visibility = View.VISIBLE
        applyBackgroundBlur()
    }

    private fun applyBuiltInBackground(palette: ReaderThemePalette) {
        if (palette.backgroundDrawableRes != 0) {
            views.readerBackgroundImage.setImageResource(palette.backgroundDrawableRes)
            views.readerBackgroundImage.visibility = View.VISIBLE
            applyBackgroundBlur()
        } else {
            views.readerBackgroundImage.setImageDrawable(null)
            views.readerBackgroundImage.visibility = View.GONE
        }
    }

    fun applyBackgroundBlur() {
        val blurPercent = runtime.settingsStore.backgroundBlurPercent
        if (blurPercent <= 0) {
            views.readerBackgroundImage.alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) views.readerBackgroundImage.setRenderEffect(null)
            return
        }
        views.readerBackgroundImage.alpha = 1f - blurPercent / 100f * 0.5f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = blurPercent / 100f * 25f
            views.readerBackgroundImage.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    fun openBackgroundPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        activity.startActivityForResult(intent, requestCode)
    }

    fun currentBackgroundLabel(): String {
        val path = runtime.settingsStore.readerBackgroundPath
        val palette = ReaderDisplayModeHelper.resolvePalette(activity, runtime.settingsStore)
        if (!shouldUseCustomBackground(path)) {
            val suffix = if (ReaderDisplayModeHelper.isAutoNightActive(activity, runtime.settingsStore)) "（自动夜航）" else ""
            return "当前背景：使用${palette.displayName}内置壁纸$suffix"
        }
        return "当前背景：${File(path!!).name}"
    }

    fun resolveReaderTextColor(palette: ReaderThemePalette): Int {
        var colorKey = runtime.settingsStore.readerTextColor
        if (ReaderDisplayModeHelper.shouldOverrideCustomVisuals(activity, runtime.settingsStore, null)) {
            colorKey = "theme_default"
        }
        return resolveReaderTextColorValue(colorKey, palette)
    }

    fun resolveReaderTextColorValue(colorKey: String?, palette: ReaderThemePalette): Int {
        if (colorKey == "custom") {
            val customColor = runtime.settingsStore.customTextColor
            if (!customColor.isNullOrEmpty()) {
                try {
                    return Color.parseColor(customColor)
                } catch (_: Exception) {
                }
            }
            return palette.textColor
        }
        return when (colorKey) {
            "ink_brown" -> 0xFF5A4330.toInt()
            "graphite" -> 0xFF374151.toInt()
            "warm_gray" -> 0xFF635B52.toInt()
            "jade_ink" -> 0xFF255B57.toInt()
            "forest_ink" -> 0xFF274235.toInt()
            "moon_white" -> 0xFFF5F7FA.toInt()
            else -> palette.textColor
        }
    }

    fun updateTextColorPreview(preview: TextView?, colorKey: String?, palette: ReaderThemePalette) {
        preview ?: return
        val index = ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, colorKey, 0)
        preview.text = "字色预览：${ReaderOptionCatalog.READER_TEXT_COLOR_LABELS[index]}"
        preview.setTextColor(resolveReaderTextColorValue(colorKey, palette))
        preview.setBackgroundColor(palette.pageColor)
    }

    private fun computeHudReservedTopPx(): Int {
        val showCenterSlots = isLandscapeHudMode()
        if (!hasVisibleHudSlot(
                runtime.settingsStore.hudTopLeft,
                if (showCenterSlots) runtime.settingsStore.hudTopCenter else "none",
                runtime.settingsStore.hudTopRight,
            )) return 0
        return ui.dp(runtime.settingsStore.hudTopMarginDp + HUD_BAR_HEIGHT_DP)
    }

    private fun computeHudReservedBottomPx(): Int {
        val showCenterSlots = isLandscapeHudMode()
        if (!hasVisibleHudSlot(
                runtime.settingsStore.hudBottomLeft,
                if (showCenterSlots) runtime.settingsStore.hudBottomCenter else "none",
                runtime.settingsStore.hudBottomRight,
            )) return 0
        return ui.dp(runtime.settingsStore.hudBottomMarginDp + HUD_BAR_HEIGHT_DP)
    }

    private fun isLandscapeHudMode(): Boolean =
        views.pageStage.width > 0 && views.pageStage.height > 0 && views.pageStage.width > views.pageStage.height

    private fun hasVisibleHudSlot(vararg slots: String?): Boolean = slots.any { it != null && it != "none" }

    private fun applyDoublePageVisibility() {
        val active = ReaderDisplayModeHelper.isDoublePageActive(
            activity,
            runtime.settingsStore,
            views.pageStage.width,
            views.pageStage.height,
        )
        val visibility = if (active) View.VISIBLE else View.GONE
        views.pageCurrentRightPane.visibility = visibility
        views.pageIncomingRightPane.visibility = visibility
        val gutterVisibility = if (active && shouldShowDoublePageGutter()) View.VISIBLE else View.GONE
        views.pageCurrentGutter.visibility = gutterVisibility
        views.pageIncomingGutter.visibility = gutterVisibility
        val showOuterPageSimulationSpine = active &&
            runtime.settingsStore.flipMode == "simulation" &&
            runtime.settingsStore.simulationDoublePageTurnMode == "outerPage"
        if (showOuterPageSimulationSpine) {
            views.pageBookSpineOverlay.background = buildAdaptiveBookSpineDrawable(state.currentReaderPageColor)
        }
        views.pageBookSpineOverlay.visibility = if (showOuterPageSimulationSpine) View.VISIBLE else View.GONE
    }

    private fun shouldShowDoublePageGutter(): Boolean = true

    private fun buildAdaptiveBookSpineDrawable(pageColor: Int): Drawable {
        val lightInk = shouldUseLightSpineInk(pageColor)
        return GradientDrawable().apply { setColor(spineInkColor(lightInk, if (lightInk) 184 else 132)) }
    }

    private fun shouldUseLightSpineInk(pageColor: Int): Boolean = relativeLuminance(pageColor) < 0.45f

    private fun spineInkColor(lightInk: Boolean, alpha: Int): Int =
        if (lightInk) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)

    private fun relativeLuminance(color: Int): Float = (
        0.2126 * linearizedChannel(Color.red(color)) +
            0.7152 * linearizedChannel(Color.green(color)) +
            0.0722 * linearizedChannel(Color.blue(color))
        ).toFloat()

    private fun linearizedChannel(value: Int): Double {
        val channel = value / 255.0
        return if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }

    private fun shouldUseCustomBackground(path: String?): Boolean =
        !path.isNullOrBlank() && !ReaderDisplayModeHelper.shouldOverrideCustomVisuals(activity, runtime.settingsStore, null)

    private fun stylePageTitleView(textView: TextView?, typeface: Typeface, textColor: Int) {
        textView ?: return
        textView.setTextColor(textColor)
        textView.typeface = typeface
        textView.includeFontPadding = false
        textView.textSize = runtime.settingsStore.fontSizeSp * CHAPTER_TITLE_SCALE
    }

    private fun stylePageBodyView(textView: JustifiedPageTextView?, typeface: Typeface, textColor: Int) {
        textView ?: return
        textView.setTextColor(textColor)
        textView.typeface = typeface
        textView.textSize = runtime.settingsStore.fontSizeSp
        textView.setLineSpacing(runtime.settingsStore.lineSpacingExtraSp, 1f)
        textView.letterSpacing = runtime.settingsStore.letterSpacing
        textView.setFullJustifyEnabled(runtime.settingsStore.isBodyTextJustified)
        textView.gravity = Gravity.START or Gravity.TOP
        textView.setPadding(0, 0, 0, 0)
    }

    private fun styleHudTextView(textView: TextView?, typeface: Typeface, textColor: Int) {
        textView ?: return
        textView.typeface = typeface
        textView.includeFontPadding = false
        textView.textSize = HUD_TEXT_SIZE_SP
        textView.setTextColor(applyAlpha(textColor, HUD_TEXT_ALPHA))
    }

    private fun applyAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    fun updateLetterSpacingLabel(label: TextView, seekBar: SeekBar) {
        label.text = String.format(Locale.SIMPLIFIED_CHINESE, "%.2f", seekBar.progress / 20f)
    }

    fun updateFirstLineIndentLabel(label: TextView, seekBar: SeekBar) {
        label.text = "${seekBar.progress} 字符"
    }

    fun updateParagraphSpacingLabel(label: TextView, seekBar: SeekBar) {
        label.text = "${seekBar.progress} dp"
    }

    fun updateBackgroundBlurLabel(label: TextView, seekBar: SeekBar) {
        label.text = "${seekBar.progress}%"
    }

    fun buildReaderTypeface(familyKey: String?, weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(familyKey, weight)
        } else {
            Typeface.create(familyKey, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }

    private companion object {
        const val CHAPTER_TITLE_SCALE = 1.4f
        const val HUD_BAR_HEIGHT_DP = 22
        const val HUD_TEXT_SIZE_SP = 12f
        const val HUD_TEXT_ALPHA = 0.78f
    }
}
