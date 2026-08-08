package com.bing.androidvoiceflow.capture.clipboard

import com.bing.androidvoiceflow.capture.entry.MAX_CAPTURE_CODE_POINTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImportPolicyTest {
    @Test
    fun `fresh text is trimmed and accepted`() {
        val decision = ClipboardImportPolicy.evaluate(
            snapshot = ClipboardSnapshot("  第一段\n第二段  ", 100_000L),
            lastImport = null,
            nowMillis = 110_000L
        )

        assertTrue(decision is ClipboardImportDecision.Accept)
        assertEquals("第一段\n第二段", (decision as ClipboardImportDecision.Accept).text)
        assertEquals(64, decision.fingerprint.length)
    }

    @Test
    fun `empty missing timestamp stale and oversized text are rejected`() {
        assertRejected(ClipboardSnapshot("  ", 100_000L), 110_000L, ClipboardRejectReason.Empty)
        assertRejected(
            ClipboardSnapshot("内容", 0L),
            110_000L,
            ClipboardRejectReason.TimestampUnavailable
        )
        assertRejected(
            ClipboardSnapshot("内容", 100_000L),
            100_000L + CLIPBOARD_FRESHNESS_MILLIS + 1L,
            ClipboardRejectReason.Stale
        )
        assertRejected(
            ClipboardSnapshot("文".repeat(MAX_CAPTURE_CODE_POINTS + 1), 100_000L),
            110_000L,
            ClipboardRejectReason.TooLong
        )
    }

    @Test
    fun `same clipboard cannot be imported twice`() {
        val accepted = requireAccepted("相同内容", 100_000L, 101_000L)
        val last = LastClipboardImport(
            accepted.fingerprint,
            accepted.clipboardTimestampMillis,
            101_000L
        )

        assertRejected(
            ClipboardSnapshot("相同内容", 100_000L),
            105_000L,
            ClipboardRejectReason.Duplicate,
            last
        )
    }

    @Test
    fun `recopying same text is deduplicated only inside short window`() {
        val accepted = requireAccepted("再次复制", 100_000L, 101_000L)
        val last = LastClipboardImport(accepted.fingerprint, 100_000L, 101_000L)
        assertRejected(
            ClipboardSnapshot("再次复制", 120_000L),
            120_000L,
            ClipboardRejectReason.Duplicate,
            last
        )

        val afterWindow = ClipboardImportPolicy.evaluate(
            ClipboardSnapshot("再次复制", 170_000L),
            last,
            170_000L
        )
        assertTrue(afterWindow is ClipboardImportDecision.Accept)
    }

    private fun requireAccepted(
        text: String,
        clipboardTimestamp: Long,
        now: Long
    ): ClipboardImportDecision.Accept = ClipboardImportPolicy.evaluate(
        ClipboardSnapshot(text, clipboardTimestamp),
        null,
        now
    ) as ClipboardImportDecision.Accept

    private fun assertRejected(
        snapshot: ClipboardSnapshot,
        now: Long,
        expectedReason: ClipboardRejectReason,
        lastImport: LastClipboardImport? = null
    ) {
        val decision = ClipboardImportPolicy.evaluate(snapshot, lastImport, now)
        assertTrue(decision is ClipboardImportDecision.Reject)
        assertEquals(expectedReason, (decision as ClipboardImportDecision.Reject).reason)
    }
}
