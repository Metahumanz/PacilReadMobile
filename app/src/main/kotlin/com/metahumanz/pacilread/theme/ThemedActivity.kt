package com.metahumanz.pacilread.theme

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.metahumanz.pacilread.ui.EdgeToEdgeHelper

abstract class ThemedActivity : AppCompatActivity() {
    private var appliedThemeBucket: String = ThemeModeHelper.MODE_LIGHT
    private var appliedStyleVariant: String = ThemeModeHelper.LIGHT_STYLE_YUNBAI

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeModeHelper.wrapForApp(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeModeHelper.resolveAppThemeResId(this))
        appliedThemeBucket = ThemeModeHelper.getResolvedAppBucket(this)
        appliedStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this)
        super.onCreate(savedInstanceState)
        EdgeToEdgeHelper.configure(this)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        EdgeToEdgeHelper.applySystemBarPaddingToContentRoot(this)
    }

    override fun onResume() {
        super.onResume()
        val desiredBucket = ThemeModeHelper.getResolvedAppBucket(this)
        val desiredStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this)
        if (desiredBucket != appliedThemeBucket || desiredStyleVariant != appliedStyleVariant) recreate()
    }

    protected fun isDarkAppTheme(): Boolean = ThemeModeHelper.isDark(resources)
}
