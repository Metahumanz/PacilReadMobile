package com.metahumanz.pacilread.theme

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.metahumanz.pacilread.ui.EdgeToEdgeHelper

abstract class ThemedReaderActivity : AppCompatActivity() {
    private var appliedThemeBucket: String = ThemeModeHelper.MODE_LIGHT
    private var appliedStyleVariant: String = ThemeModeHelper.LIGHT_STYLE_YUNBAI

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeModeHelper.wrapForReader(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeModeHelper.resolveReaderThemeResId(this))
        appliedThemeBucket = ThemeModeHelper.getResolvedReaderBucket(this)
        appliedStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this)
        super.onCreate(savedInstanceState)
        EdgeToEdgeHelper.configure(this)
    }

    override fun onResume() {
        super.onResume()
        val desiredBucket = ThemeModeHelper.getResolvedReaderBucket(this)
        val desiredStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this)
        if (desiredBucket != appliedThemeBucket || desiredStyleVariant != appliedStyleVariant) recreate()
    }

    protected fun applyResolvedReaderThemeWithoutRecreate() {
        setTheme(ThemeModeHelper.resolveReaderThemeResId(this))
        appliedThemeBucket = ThemeModeHelper.getResolvedReaderBucket(this)
        appliedStyleVariant = ThemeModeHelper.getResolvedReaderStyleVariant(this)
        EdgeToEdgeHelper.configure(this)
    }

    protected fun isDarkReaderUi(): Boolean =
        ThemeModeHelper.MODE_DARK == ThemeModeHelper.getResolvedReaderBucket(this)
}
