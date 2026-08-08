package com.bing.androidvoiceflow.capture.ui

import android.content.Context
import androidx.core.content.edit

internal class ManualCaptureDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): String = preferences.getString(KEY_CONTENT, null).orEmpty()

    fun save(content: String) {
        preferences.edit {
            if (content.isEmpty()) {
                remove(KEY_CONTENT)
            } else {
                putString(KEY_CONTENT, content)
            }
        }
    }

    fun clear() {
        preferences.edit { remove(KEY_CONTENT) }
    }

    private companion object {
        const val PREFERENCES_NAME = "manual_capture_draft"
        const val KEY_CONTENT = "content"
    }
}
