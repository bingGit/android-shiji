package com.bing.androidvoiceflow.capture.clipboard

import com.bing.androidvoiceflow.capture.entry.MAX_CAPTURE_CODE_POINTS
import java.security.MessageDigest

internal const val CLIPBOARD_FRESHNESS_MILLIS = 2L * 60L * 1_000L
internal const val CLIPBOARD_DUPLICATE_WINDOW_MILLIS = 60L * 1_000L
private const val FUTURE_TIMESTAMP_TOLERANCE_MILLIS = 10_000L

internal data class ClipboardSnapshot(
    val text: CharSequence?,
    val timestampMillis: Long
)

internal data class LastClipboardImport(
    val fingerprint: String,
    val clipboardTimestampMillis: Long,
    val importedAtMillis: Long
)

internal sealed interface ClipboardImportDecision {
    data class Accept(
        val text: String,
        val fingerprint: String,
        val clipboardTimestampMillis: Long
    ) : ClipboardImportDecision

    data class Reject(val reason: ClipboardRejectReason) : ClipboardImportDecision
}

internal enum class ClipboardRejectReason {
    Empty,
    TimestampUnavailable,
    Stale,
    TooLong,
    Duplicate
}

internal object ClipboardImportPolicy {
    fun evaluate(
        snapshot: ClipboardSnapshot,
        lastImport: LastClipboardImport?,
        nowMillis: Long
    ): ClipboardImportDecision {
        val text = snapshot.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ClipboardImportDecision.Reject(ClipboardRejectReason.Empty)
        if (text.codePointCount(0, text.length) > MAX_CAPTURE_CODE_POINTS) {
            return ClipboardImportDecision.Reject(ClipboardRejectReason.TooLong)
        }
        if (snapshot.timestampMillis <= 0L) {
            return ClipboardImportDecision.Reject(ClipboardRejectReason.TimestampUnavailable)
        }
        val age = nowMillis - snapshot.timestampMillis
        if (age > CLIPBOARD_FRESHNESS_MILLIS || age < -FUTURE_TIMESTAMP_TOLERANCE_MILLIS) {
            return ClipboardImportDecision.Reject(ClipboardRejectReason.Stale)
        }

        val fingerprint = text.sha256()
        if (lastImport != null) {
            val exactClipboardAlreadyImported =
                lastImport.clipboardTimestampMillis == snapshot.timestampMillis &&
                    lastImport.fingerprint == fingerprint
            val sameContentRecentlyImported = lastImport.fingerprint == fingerprint &&
                nowMillis - lastImport.importedAtMillis < CLIPBOARD_DUPLICATE_WINDOW_MILLIS
            if (exactClipboardAlreadyImported || sameContentRecentlyImported) {
                return ClipboardImportDecision.Reject(ClipboardRejectReason.Duplicate)
            }
        }

        return ClipboardImportDecision.Accept(text, fingerprint, snapshot.timestampMillis)
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
