package com.bing.androidvoiceflow.capture.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query

internal data class CaptureRecordRow(
    val stableId: String,
    val kind: String,
    val occurredAt: Long,
    val title: String,
    val summary: String,
    val source: String?,
    val excerptCount: Int,
    val activeSessionId: String?
)

@Dao
internal interface CaptureRecordDao {
    @Query(
        """
        WITH capture_records AS (
            SELECT
                'single-' || capture_id AS stableId,
                'single' AS kind,
                received_at AS occurredAt,
                CASE
                    WHEN TRIM(COALESCE(title_hint, '')) != '' THEN TRIM(title_hint)
                    WHEN capture_type = 'article' THEN '文章分享'
                    WHEN capture_type = 'excerpt' THEN '文字摘录'
                    WHEN capture_type = 'manual_text' THEN '手动记录'
                    ELSE '分享内容'
                END AS title,
                TRIM(raw_text) AS summary,
                source_package AS source,
                0 AS excerptCount,
                NULL AS activeSessionId
            FROM single_captures

            UNION ALL

            SELECT
                'reading-' || session_id AS stableId,
                'reading' AS kind,
                last_activity_at AS occurredAt,
                CASE
                    WHEN TRIM(COALESCE(title_hint, '')) != '' THEN TRIM(title_hint)
                    ELSE '未命名阅读摘录'
                END AS title,
                COALESCE(
                    (
                        SELECT GROUP_CONCAT(ordered_blocks.content, ' ')
                        FROM (
                            SELECT content
                            FROM reading_blocks
                            WHERE reading_blocks.session_id = reading_sessions.session_id
                            ORDER BY position ASC
                        ) AS ordered_blocks
                    ),
                    TRIM(COALESCE(raw_share_text, ''))
                ) AS summary,
                source_package AS source,
                (
                    SELECT COUNT(*)
                    FROM reading_blocks
                    WHERE reading_blocks.session_id = reading_sessions.session_id
                      AND type = 'excerpt'
                ) AS excerptCount,
                CASE
                    WHEN state IN ('active', 'awaiting_finish') THEN session_id
                    ELSE NULL
                END AS activeSessionId
            FROM reading_sessions
        )
        SELECT *
        FROM capture_records
        WHERE (:kind IS NULL OR kind = :kind)
        ORDER BY occurredAt DESC, stableId ASC
        """
    )
    fun pagingSource(kind: String?): PagingSource<Int, CaptureRecordRow>
}
