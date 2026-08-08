package com.bing.androidvoiceflow.capture.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bing.androidvoiceflow.capture.entry.QuickRecordShortcutActivity
import com.bing.androidvoiceflow.capture.ui.LockScreenCaptureActivity

internal class VolumeQuickCaptureService : AccessibilityService() {
    private val detector = TriplePressDetector()
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        detector.reset()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            detector.reset()
            return false
        }
        if (keyguardManager.isDeviceLocked) {
            if (!powerManager.isInteractive) {
                detector.reset()
                return false
            }
            if (detector.registerPress(event.eventTime)) launchLockScreenCapture()
            return false
        }
        if (detector.registerPress(event.eventTime)) launchQuickCapture()
        return false
    }

    private fun launchQuickCapture() {
        startActivity(
            Intent(this, QuickRecordShortcutActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
    }

    private fun launchLockScreenCapture() {
        startActivity(
            Intent(this, LockScreenCaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        )
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            if (
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) != 1
            ) return false

            val expected = ComponentName(context, VolumeQuickCaptureService::class.java)
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
                .split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }
    }
}
