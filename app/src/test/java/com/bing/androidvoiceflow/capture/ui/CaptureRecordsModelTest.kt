package com.bing.androidvoiceflow.capture.ui

import com.bing.androidvoiceflow.capture.data.ReadingBlockEntity
import com.bing.androidvoiceflow.capture.data.ReadingSessionEntity
import com.bing.androidvoiceflow.capture.data.SingleCaptureEntity
import com.bing.androidvoiceflow.capture.data.CaptureRecordRow
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRecordsModelTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `records merge by latest activity and preserve user facing kinds`() {
        val records = buildCaptureRecordItems(
            singles = listOf(single("single-old", 100L), single("single-new", 300L)),
            sessions = listOf(reading("reading", 200L, 2))
        )

        assertEquals(
            listOf("single-single-new", "reading-reading", "single-single-old"),
            records.map { it.stableId }
        )
        assertEquals(CaptureRecordKind.Single, records[0].kind)
        assertEquals(CaptureRecordKind.Reading, records[1].kind)
        assertEquals(2, records[1].excerptCount)
    }

    @Test
    fun `record filters keep only selected kind`() {
        val singles = listOf(single("single", 100L))
        val sessions = listOf(reading("reading", 200L, 1))

        assertEquals(
            listOf(CaptureRecordKind.Single),
            buildCaptureRecordItems(singles, sessions, CaptureRecordFilter.Single).map { it.kind }
        )
        assertEquals(
            listOf(CaptureRecordKind.Reading),
            buildCaptureRecordItems(singles, sessions, CaptureRecordFilter.Reading).map { it.kind }
        )
    }

    @Test
    fun `date groups distinguish today yesterday this week and earlier`() {
        val now = millis(2026, 7, 30, 12, 0)

        assertEquals(CaptureRecordDateGroup.Today, captureRecordDateGroup(millis(2026, 7, 30, 9, 0), now, zone))
        assertEquals(CaptureRecordDateGroup.Yesterday, captureRecordDateGroup(millis(2026, 7, 29, 9, 0), now, zone))
        assertEquals(CaptureRecordDateGroup.ThisWeek, captureRecordDateGroup(millis(2026, 7, 28, 9, 0), now, zone))
        assertEquals(CaptureRecordDateGroup.Earlier, captureRecordDateGroup(millis(2026, 7, 20, 9, 0), now, zone))
    }

    @Test
    fun `time labels use compact relative dates`() {
        val now = millis(2026, 7, 30, 12, 0)

        assertEquals("09:05", captureRecordTimeLabel(millis(2026, 7, 30, 9, 5), now, zone))
        assertEquals("昨天 09:05", captureRecordTimeLabel(millis(2026, 7, 29, 9, 5), now, zone))
        assertEquals("周二 09:05", captureRecordTimeLabel(millis(2026, 7, 28, 9, 5), now, zone))
        assertEquals("07-20 09:05", captureRecordTimeLabel(millis(2026, 7, 20, 9, 5), now, zone))
    }

    @Test
    fun `database rows map packages to user facing source labels`() {
        assertEquals("微信读书", recordRow("com.tencent.weread").toListItem().source)
        assertEquals(null, recordRow("com.example.reader").toListItem().source)
    }

    private fun recordRow(source: String) = CaptureRecordRow(
        stableId = "single-source",
        kind = "single",
        occurredAt = 100L,
        title = "标题",
        summary = "正文",
        source = source,
        excerptCount = 0,
        activeSessionId = null
    )

    private fun single(id: String, receivedAt: Long) = SingleCaptureEntity(
        captureId = id,
        captureType = CaptureType.ManualText,
        rawText = "单条记录 $id",
        comment = null,
        sourceUrl = null,
        titleHint = null,
        sourcePackage = null,
        receivedAt = receivedAt,
        graceDeadlineAt = receivedAt + 8_000L,
        state = SingleCaptureState.Frozen
    )

    private fun reading(id: String, lastActivityAt: Long, excerptCount: Int): CaptureRecordSession {
        val session = ReadingSessionEntity(
            sessionId = id,
            sourceUrl = null,
            titleHint = "阅读摘录 $id",
            sourcePackage = "reader",
            rawShareText = null,
            state = ReadingSessionState.Frozen,
            startedAt = lastActivityAt - 100L,
            lastActivityAt = lastActivityAt,
            inactivityDeadlineAt = lastActivityAt + 1_000L
        )
        val blocks = (1..excerptCount).map { position ->
            ReadingBlockEntity(
                blockId = "$id-$position",
                sessionId = id,
                position = position.toLong(),
                type = ReadingBlockType.Excerpt,
                content = "摘录 $position",
                createdAt = lastActivityAt
            )
        }
        return CaptureRecordSession(session, blocks)
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
