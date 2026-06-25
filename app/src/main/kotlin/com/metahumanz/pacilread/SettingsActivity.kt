package com.metahumanz.pacilread

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemedActivity
import com.metahumanz.pacilread.ui.ActivityTransitionCompat
import com.metahumanz.pacilread.ui.PredictiveBackScaleController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper

open class SettingsActivity : ThemedActivity() {
    private var settingsController: SettingsScreenController? = null
    private var homeBottomNavigationTransition = false

    override fun onCreate(savedInstanceState: Bundle?) {
        homeBottomNavigationTransition = intent.getBooleanExtra(EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION, false)
        if (homeBottomNavigationTransition) {
            ActivityTransitionCompat.overrideOpen(this, R.anim.activity_home_settings_enter, R.anim.activity_home_settings_under_exit)
        } else {
            ActivityTransitionCompat.overrideOpen(this, R.anim.activity_slide_forward, R.anim.activity_recede)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsController = SettingsScreenController(this, object : SettingsScreenController.Host {
            override fun openBookPicker(intent: Intent, requestCode: Int) {
                startActivityForResult(intent, requestCode)
            }

            override fun openReader(bookId: Long) {
                startActivity(Intent(this@SettingsActivity, ReaderActivity::class.java).putExtra("book_id", bookId))
            }

            override fun onSettingsSaved() = Unit
            override fun onLibraryDataRestored() = Unit
            override fun onThemeChanged() = recreate()
        })

        findViewById<ImageButton?>(R.id.button_back)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        installPredictiveBack()
    }

    private fun installPredictiveBack() {
        if (!TransitionMotionModeHelper.isFluidMode(SettingsStore(this))) return
        val root = findViewById<android.view.View?>(R.id.settings_root) ?: return
        PredictiveBackScaleController.install(
            this,
            root,
            PredictiveBackScaleController.Profile.standard(),
            object : PredictiveBackScaleController.Delegate {
                override fun shouldAnimateBack(): Boolean = true
                override fun consumeBack(): Boolean = false
                override fun commitBack() = finishWithSettingsTransition()
            },
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!TransitionMotionModeHelper.isFluidMode(SettingsStore(this))) {
            finishWithSettingsTransition()
            return
        }
        super.onBackPressed()
    }

    private fun finishWithSettingsTransition() {
        settingsController?.saveSettings()
        finish()
        if (homeBottomNavigationTransition) {
            ActivityTransitionCompat.overrideClose(this, R.anim.activity_home_settings_under_enter, R.anim.activity_home_settings_exit)
        } else {
            ActivityTransitionCompat.overrideClose(this, R.anim.activity_return_from_recede, R.anim.activity_slide_backward)
        }
    }

    override fun onResume() {
        super.onResume()
        settingsController?.onResume()
    }

    override fun onPause() {
        settingsController?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        settingsController?.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pickedUri = data?.data
        if (requestCode != SettingsScreenController.REQUEST_PICK_BOOK || resultCode != RESULT_OK || pickedUri == null) return
        settingsController?.onBookPicked(pickedUri)
    }

    companion object {
        const val EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION =
            "com.metahumanz.pacilread.EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION"
    }
}
