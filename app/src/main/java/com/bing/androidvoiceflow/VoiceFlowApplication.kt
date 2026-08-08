package com.bing.androidvoiceflow

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.bing.androidvoiceflow.capture.entry.QuickRecordShortcutActivity
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler

internal class VoiceFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerQuickRecordShortcut()
        CaptureWorkScheduler.schedulePeriodicCleanup(this)
    }

    private fun registerQuickRecordShortcut() {
        val shortcutManager = getSystemService(ShortcutManager::class.java)
        val shortcut = ShortcutInfo.Builder(this, QUICK_RECORD_SHORTCUT_ID)
            .setShortLabel(getString(R.string.shortcut_quick_record_short))
            .setLongLabel(getString(R.string.shortcut_quick_record_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher_foreground))
            .setIntent(
                Intent(this, QuickRecordShortcutActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
            .build()

        shortcutManager.dynamicShortcuts = listOf(shortcut)
    }

    private companion object {
        const val QUICK_RECORD_SHORTCUT_ID = "quick_record"
    }
}
