package com.bing.androidvoiceflow.capture.clipboard

import android.content.Context
import androidx.core.content.edit

internal class ClipboardImportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): LastClipboardImport? {
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null).orEmpty()
        val clipboardTimestamp = preferences.getLong(KEY_CLIPBOARD_TIMESTAMP, 0L)
        val importedAt = preferences.getLong(KEY_IMPORTED_AT, 0L)
        if (fingerprint.isBlank() || clipboardTimestamp <= 0L || importedAt <= 0L) return null
        return LastClipboardImport(fingerprint, clipboardTimestamp, importedAt)
    }

    fun save(import: ClipboardImportDecision.Accept, importedAtMillis: Long) {
        preferences.edit(commit = true) {
            putString(KEY_FINGERPRINT, import.fingerprint)
            putLong(KEY_CLIPBOARD_TIMESTAMP, import.clipboardTimestampMillis)
            putLong(KEY_IMPORTED_AT, importedAtMillis)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "clipboard_capture_state"
        const val KEY_FINGERPRINT = "last_fingerprint"
        const val KEY_CLIPBOARD_TIMESTAMP = "last_clipboard_timestamp"
        const val KEY_IMPORTED_AT = "last_imported_at"
    }
}
