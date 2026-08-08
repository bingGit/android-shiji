package com.bing.androidvoiceflow.capture.domain

enum class CaptureType(val storageValue: String) {
    Article("article"),
    Excerpt("excerpt"),
    SharedText("shared_text"),
    ManualText("manual_text");

    companion object {
        fun fromStorage(value: String): CaptureType = entries.first { it.storageValue == value }
    }
}

enum class SingleCaptureState(val storageValue: String) {
    LocalGrace("local_grace"),
    Frozen("frozen"),
    Abandoned("abandoned");

    companion object {
        fun fromStorage(value: String): SingleCaptureState = entries.first { it.storageValue == value }
    }
}

enum class ReadingSessionState(val storageValue: String) {
    Active("active"),
    AwaitingFinish("awaiting_finish"),
    Frozen("frozen"),
    Abandoned("abandoned");

    companion object {
        fun fromStorage(value: String): ReadingSessionState = entries.first { it.storageValue == value }
    }
}

enum class ReadingBlockType(val storageValue: String) {
    Excerpt("excerpt"),
    Comment("comment");

    companion object {
        fun fromStorage(value: String): ReadingBlockType = entries.first { it.storageValue == value }
    }
}

enum class OutboundRequestState(val storageValue: String) {
    Pending("pending"),
    Sending("sending"),
    RetryWait("retry_wait"),
    AuthRequired("auth_required"),
    Failed("failed");

    companion object {
        fun fromStorage(value: String): OutboundRequestState = entries.first { it.storageValue == value }
    }
}

enum class CaptureOriginType(val storageValue: String) {
    SingleCapture("single_capture"),
    ReadingSession("reading_session");

    companion object {
        fun fromStorage(value: String): CaptureOriginType = entries.first { it.storageValue == value }
    }
}

enum class CaptureClientPlatform(val storageValue: String) {
    AndroidShare("android-share"),
    AndroidProcessText("android-process-text"),
    AndroidReadingSession("android-reading-session");
}

data class ReadingContentBlock(
    val position: Long,
    val type: ReadingBlockType,
    val content: String
)
