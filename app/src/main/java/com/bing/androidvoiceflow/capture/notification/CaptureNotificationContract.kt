package com.bing.androidvoiceflow.capture.notification

internal object CaptureNotificationContract {
    const val ACTION_UNDO_SINGLE = "com.bing.androidvoiceflow.capture.UNDO_SINGLE"
    const val ACTION_UNDO_LAST_BLOCK = "com.bing.androidvoiceflow.capture.UNDO_LAST_BLOCK"
    const val ACTION_COMPLETE_SESSION = "com.bing.androidvoiceflow.capture.COMPLETE_SESSION"
    const val ACTION_SELECT_TAG = "com.bing.androidvoiceflow.capture.SELECT_TAG"
    const val EXTRA_RECORD_ID = "capture_record_id"
    const val EXTRA_TAG_CHOICE = "capture_tag_choice"
    const val CHOICE_MORE_TAGS = "更多标签…"
    const val EXTRA_COMMENT_TARGET = "capture_comment_target"
    const val TARGET_SINGLE = "single"
    const val TARGET_SESSION = "session"
}
