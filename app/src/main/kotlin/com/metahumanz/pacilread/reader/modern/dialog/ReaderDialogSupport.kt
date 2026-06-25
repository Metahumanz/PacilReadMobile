package com.metahumanz.pacilread.reader.modern.dialog

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.GlassUiHelper
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.PredictiveDialogDismissController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper

class ReaderDialogSupport(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val ui: ReaderUiUtils,
) {
    private var nextDismissSource: LaunchSourceTransition.Source? = null

    fun buildSpinnerAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(activity, R.layout.item_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

    fun buildDialogListAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, R.layout.item_dialog_list_row, android.R.id.text1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { view ->
                    view.findViewById<TextView>(android.R.id.text1).setTextColor(ui.themeColor(R.color.on_surface))
                }
        }

    fun setNextDismissSource(sourceView: View?) {
        nextDismissSource = LaunchSourceTransition.captureSource(sourceView)
    }

    fun setNextDismissSource(sourceBounds: Rect?) {
        nextDismissSource = sourceBounds?.let(LaunchSourceTransition::sourceFromBounds)
    }

    fun setNextDismissSource(source: LaunchSourceTransition.Source?) {
        nextDismissSource = source
    }

    fun showStyledDialog(dialog: AlertDialog) {
        val window = showDialogWithAnimation(dialog, R.style.ReaderPopDialogAnimation)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val registration = installPredictiveDismiss(dialog, window, consumeNextDismissSource())
        dialog.setOnDismissListener { registration.unregister() }
        GlassUiHelper.applyToHierarchy(activity, dialog.findViewById(android.R.id.content), runtime.settingsStore.glassOpacityPercent)
    }

    fun showFullscreenDialog(dialog: AlertDialog) {
        val window = showDialogWithAnimation(dialog, R.style.ReaderFullscreenDialogAnimation)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val registration = installPredictiveDismiss(dialog, window, consumeNextDismissSource())
        dialog.setOnDismissListener { registration.unregister() }
        GlassUiHelper.applyToHierarchy(activity, dialog.findViewById(android.R.id.content), runtime.settingsStore.glassOpacityPercent)
    }

    @Suppress("DEPRECATION")
    fun applyTocStyleFullscreenInsets(root: View?, contentContainer: View?) {
        if (root == null || contentContainer == null) return
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val leftInset: Int
            val topInset: Int
            val rightInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars())
                val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
                val landscape = view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                leftInset = if (landscape) systemBars.left else Math.max(systemBars.left, cutout.left)
                topInset = Math.max(systemBars.top, cutout.top)
                rightInset = if (landscape) systemBars.right else Math.max(systemBars.right, cutout.right)
                bottomInset = Math.max(systemBars.bottom, cutout.bottom)
            } else {
                leftInset = windowInsets.systemWindowInsetLeft
                topInset = windowInsets.systemWindowInsetTop
                rightInset = windowInsets.systemWindowInsetRight
                bottomInset = windowInsets.systemWindowInsetBottom
            }
            contentContainer.setPadding(ui.dp(20) + leftInset, ui.dp(18) + topInset, ui.dp(16) + rightInset, ui.dp(16) + bottomInset)
            windowInsets
        }
    }

    fun addAlignedCloseButton(contentView: View?, titleViewId: Int, contentContainer: View?, dialog: AlertDialog?) {
        if (contentView !is FrameLayout || contentContainer == null || dialog == null) return
        val titleView = contentView.findViewById<View>(titleViewId) ?: return
        val closeButton = TextView(activity).apply {
            text = "×"
            textSize = 20f
            setTextColor(ui.themeColor(R.color.on_surface))
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "关闭"
            setOnClickListener { dialog.dismiss() }
        }
        val size = ui.dp(48)
        contentView.addView(closeButton, FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.TOP or Gravity.START })
        val position = Runnable { positionAlignedCloseButton(contentView, titleView, contentContainer, closeButton, size) }
        contentView.post(position)
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position.run() }
        contentView.addOnLayoutChangeListener(listener)
        titleView.addOnLayoutChangeListener(listener)
        contentContainer.addOnLayoutChangeListener(listener)
    }

    private fun positionAlignedCloseButton(root: FrameLayout, titleView: View, contentContainer: View, closeButton: View, size: Int) {
        if (root.width <= 0 || titleView.width <= 0 || contentContainer.width <= 0) return
        val rootLocation = IntArray(2)
        val titleLocation = IntArray(2)
        val containerLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        titleView.getLocationOnScreen(titleLocation)
        contentContainer.getLocationOnScreen(containerLocation)
        val titleCenterY = titleLocation[1] - rootLocation[1] + titleView.height / 2
        val contentRight = containerLocation[0] - rootLocation[0] + contentContainer.width - contentContainer.paddingRight
        val left = Math.max(0, contentRight - size)
        val top = Math.max(0, titleCenterY - size / 2)
        val params = closeButton.layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin != left || params.topMargin != top) {
            params.leftMargin = left
            params.topMargin = top
            closeButton.layoutParams = params
        }
    }

    fun showImmersiveFullscreenDialog(dialog: AlertDialog, restoreShowSystemBars: Boolean) {
        val window = showDialogWithAnimation(dialog, R.style.ReaderFullscreenDialogAnimation)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            configureEdgeToEdgeWindow(this)
            applySystemBarsVisibility(this, false)
        }
        val registration = installPredictiveDismiss(dialog, window, consumeNextDismissSource())
        dialog.setOnDismissListener {
            registration.unregister()
            applySystemBarsVisibility(activity.window, restoreShowSystemBars)
        }
    }

    private fun showDialogWithAnimation(dialog: AlertDialog, animationStyleResId: Int): Window? {
        applyWindowAnimation(dialog, animationStyleResId)
        dialog.show()
        applyWindowAnimation(dialog, animationStyleResId)
        return dialog.window
    }

    private fun applyWindowAnimation(dialog: AlertDialog, animationStyleResId: Int) {
        dialog.window?.setWindowAnimations(animationStyleResId)
    }

    private fun consumeNextDismissSource(): LaunchSourceTransition.Source? = nextDismissSource.also { nextDismissSource = null }

    private fun installPredictiveDismiss(
        dialog: AlertDialog, window: Window?, dismissSource: LaunchSourceTransition.Source?,
    ): PredictiveDialogDismissController.Registration = PredictiveDialogDismissController.install(
        dialog, window, TransitionMotionModeHelper.isFluidMode(runtime.settingsStore), dismissSource,
    )

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdgeWindow(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsVisibility(window: Window, showSystemBars: Boolean) {
        val decorView = window.decorView
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!isDarkReaderUi()) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (!showSystemBars) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                if (showSystemBars) show(types) else hide(types)
            }
        }
    }

    private fun isDarkReaderUi(): Boolean = ThemeModeHelper.MODE_DARK == ThemeModeHelper.getResolvedReaderBucket(activity)

    class SimpleSeekListener(private val callback: Runnable) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = callback.run()
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
