package com.metahumanz.pacilread

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemedActivity
import java.util.concurrent.Executors

class SplashActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val splashExecutor = Executors.newSingleThreadExecutor()
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
            return
        }

        val settingsStore = SettingsStore(this)
        if (settingsStore.isAutoOpenLastBook) {
            splashExecutor.execute {
                try {
                    val databaseHelper = JsonDatabase.getInstance(this@SplashActivity)
                    var bookId = -1L
                    if (databaseHelper.isDatabaseHealthyForStartup) {
                        bookId = databaseHelper.mostRecentBookId
                    } else {
                        Log.w(TAG, "Database health check failed, opening bookshelf instead of reader")
                    }
                    val launchBookId = bookId
                    handler.post {
                        if (finishing) return@post
                        val launchIntent = Intent(this@SplashActivity, BookshelfActivity::class.java)
                        if (launchBookId > 0) launchIntent.putExtra(EXTRA_AUTO_OPEN_BOOK_ID, launchBookId)
                        startActivity(launchIntent)
                        finish()
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Auto-open startup failed, opening bookshelf", error)
                    handler.post {
                        if (finishing) return@post
                        startActivity(Intent(this@SplashActivity, BookshelfActivity::class.java))
                        finish()
                    }
                }
            }
        } else {
            handler.post {
                if (finishing) return@post
                startActivity(Intent(this@SplashActivity, BookshelfActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        finishing = true
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        splashExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SplashActivity"
        const val EXTRA_AUTO_OPEN_BOOK_ID = "com.metahumanz.pacilread.EXTRA_AUTO_OPEN_BOOK_ID"
    }
}
