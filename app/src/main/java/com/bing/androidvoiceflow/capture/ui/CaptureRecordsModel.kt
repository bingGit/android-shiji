package com.bing.androidvoiceflow.capture.ui

import com.bing.androidvoiceflow.capture.data.ReadingBlockEntity
import com.bing.androidvoiceflow.capture.data.ReadingSessionEntity
import com.bing.androidvoiceflow.capture.data.SingleCaptureEntity
import com.bing.androidvoiceflow.capture.data.CaptureRecordRow
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

internal data class CaptureRecordSession(
    val session: ReadingSessionEntity,
    val blocks: List<ReadingBlockEntity>
)

internal enum class CaptureRecordFilter(val label: String, val queryKind: String?) {
    All("全部", null),
    Single("单条记录", "single"),
    Reading("阅读摘录", "reading")
}

internal enum class CaptureRecordKind(val label: String) {
    Single("单条记录"),
    Reading("阅读摘录")
}

internal enum class CaptureRecordDateGroup(val label: String) {
    Today("今天"),
    Yesterday("昨天"),
    ThisWeek("本周"),
    Earlier("更早")
}

internal data class CaptureRecordListItem(
    val stableId: String,
    val kind: CaptureRecordKind,
    val occurredAt: Long,
    val title: String,
    val summary: String,
    val source: String?,
    val excerptCount: Int = 0,
    val activeSessionId: String? = null
)

internal fun buildCaptureRecordItems(
    singles: List<SingleCaptureEntity>,
    sessions: List<CaptureRecordSession>,
    filter: CaptureRecordFilter = CaptureRecordFilter.All
): List<CaptureRecordListItem> {
    val singleItems = singles.map { capture ->
        CaptureRecordListItem(
            stableId = "single-${capture.captureId}",
            kind = CaptureRecordKind.Single,
            occurredAt = capture.receivedAt,
            title = capture.titleHint?.trim().orEmpty().ifBlank { capture.captureType.defaultTitle() },
            summary = capture.rawText.trim(),
            source = captureSourceLabel(capture.sourcePackage)
        )
    }
    val readingItems = sessions.map { item ->
        val excerpts = item.blocks.count { it.type == ReadingBlockType.Excerpt }
        val summary = item.blocks
            .sortedBy { it.position }
            .joinToString(" ") { it.content.trim() }
            .trim()
        val isActive = item.session.state == ReadingSessionState.Active ||
            item.session.state == ReadingSessionState.AwaitingFinish
        CaptureRecordListItem(
            stableId = "reading-${item.session.sessionId}",
            kind = CaptureRecordKind.Reading,
            occurredAt = item.session.lastActivityAt,
            title = item.session.titleHint?.trim().orEmpty().ifBlank {
                "未命名阅读摘录"
            },
            summary = summary.ifBlank { item.session.rawShareText?.trim().orEmpty() },
            source = captureSourceLabel(item.session.sourcePackage),
            excerptCount = excerpts,
            activeSessionId = item.session.sessionId.takeIf { isActive }
        )
    }
    return (singleItems + readingItems)
        .asSequence()
        .filter { item ->
            when (filter) {
                CaptureRecordFilter.All -> true
                CaptureRecordFilter.Single -> item.kind == CaptureRecordKind.Single
                CaptureRecordFilter.Reading -> item.kind == CaptureRecordKind.Reading
            }
        }
        .sortedWith(compareByDescending<CaptureRecordListItem> { it.occurredAt }.thenBy { it.stableId })
        .toList()
}

internal fun CaptureRecordRow.toListItem(): CaptureRecordListItem = CaptureRecordListItem(
    stableId = stableId,
    kind = if (kind == "reading") CaptureRecordKind.Reading else CaptureRecordKind.Single,
    occurredAt = occurredAt,
    title = title,
    summary = summary,
    source = captureSourceLabel(source),
    excerptCount = excerptCount,
    activeSessionId = activeSessionId
)

internal fun captureRecordDateGroup(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): CaptureRecordDateGroup {
    val itemDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return when {
        itemDate == today -> CaptureRecordDateGroup.Today
        itemDate == today.minusDays(1) -> CaptureRecordDateGroup.Yesterday
        !itemDate.isBefore(weekStart) -> CaptureRecordDateGroup.ThisWeek
        else -> CaptureRecordDateGroup.Earlier
    }
}

internal fun captureRecordTimeLabel(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val itemTime = Instant.ofEpochMilli(timestamp).atZone(zoneId)
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val time = itemTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (captureRecordDateGroup(timestamp, now, zoneId)) {
        CaptureRecordDateGroup.Today -> time
        CaptureRecordDateGroup.Yesterday -> "昨天 $time"
        CaptureRecordDateGroup.ThisWeek -> "${itemTime.dayOfWeek.chineseLabel()} $time"
        CaptureRecordDateGroup.Earlier -> itemTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }
}

internal fun captureSourceLabel(sourcePackage: String?): String? {
    val source = sourcePackage?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return when (source) {
        "com.tencent.weread" -> "微信读书"
        "com.tencent.mm" -> "微信"
        "com.android.chrome", "com.google.android.apps.chrome" -> "Chrome"
        "com.dedao", "com.luojilab.player" -> "得到"
        else -> source.takeUnless { '.' in it }
    }
}

private fun java.time.DayOfWeek.chineseLabel(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "周一"
    java.time.DayOfWeek.TUESDAY -> "周二"
    java.time.DayOfWeek.WEDNESDAY -> "周三"
    java.time.DayOfWeek.THURSDAY -> "周四"
    java.time.DayOfWeek.FRIDAY -> "周五"
    java.time.DayOfWeek.SATURDAY -> "周六"
    java.time.DayOfWeek.SUNDAY -> "周日"
}

private fun CaptureType.defaultTitle(): String = when (this) {
    CaptureType.Article -> "文章分享"
    CaptureType.Excerpt -> "文字摘录"
    CaptureType.SharedText -> "分享内容"
    CaptureType.ManualText -> "手动记录"
}
