package com.bing.androidvoiceflow.capture.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState

@Entity(
    tableName = "single_captures",
    indices = [
        Index(value = ["state", "grace_deadline_at"])
    ]
)
internal data class SingleCaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "capture_type")
    val captureType: CaptureType,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    val comment: String?,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String?,
    @ColumnInfo(name = "title_hint")
    val titleHint: String?,
    @ColumnInfo(name = "source_package")
    val sourcePackage: String?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "grace_deadline_at")
    val graceDeadlineAt: Long,
    val state: SingleCaptureState = SingleCaptureState.LocalGrace
)

@Entity(
    tableName = "reading_sessions",
    indices = [
        Index(value = ["state", "last_activity_at"]),
        Index(value = ["source_package", "state"])
    ]
)
internal data class ReadingSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String?,
    @ColumnInfo(name = "title_hint")
    val titleHint: String?,
    @ColumnInfo(name = "source_package")
    val sourcePackage: String?,
    @ColumnInfo(name = "raw_share_text")
    val rawShareText: String?,
    val state: ReadingSessionState = ReadingSessionState.Active,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "last_activity_at")
    val lastActivityAt: Long,
    @ColumnInfo(name = "inactivity_deadline_at")
    val inactivityDeadlineAt: Long
)

@Entity(
    tableName = "reading_blocks",
    foreignKeys = [
        ForeignKey(
            entity = ReadingSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "position"], unique = true)
    ]
)
internal data class ReadingBlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "block_id")
    val blockId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val position: Long,
    val type: ReadingBlockType,
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "outbound_capture_requests",
    indices = [
        Index(value = ["origin_type", "origin_id"], unique = true),
        Index(value = ["state", "next_retry_at"])
    ]
)
internal data class OutboundCaptureRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "origin_type")
    val originType: CaptureOriginType,
    @ColumnInfo(name = "origin_id")
    val originId: String,
    val content: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "client_platform")
    val clientPlatform: String,
    val state: OutboundRequestState = OutboundRequestState.Pending,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,
    @ColumnInfo(name = "send_started_at")
    val sendStartedAt: Long? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)

@Entity(
    tableName = "capture_retention",
    primaryKeys = ["origin_type", "origin_id"],
    indices = [Index(value = ["delete_after"])]
)
internal data class CaptureRetentionEntity(
    @ColumnInfo(name = "origin_type")
    val originType: CaptureOriginType,
    @ColumnInfo(name = "origin_id")
    val originId: String,
    @ColumnInfo(name = "retained_at")
    val retainedAt: Long,
    @ColumnInfo(name = "delete_after")
    val deleteAfter: Long,
    val reason: String
)

@Entity(
    tableName = "capture_tags",
    indices = [Index(value = ["normalized_name"], unique = true)]
)
internal data class CaptureTagEntity(
    @PrimaryKey
    @ColumnInfo(name = "tag_id")
    val tagId: String,
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "capture_tag_refs",
    primaryKeys = ["origin_type", "origin_id", "tag_id"],
    indices = [
        Index(value = ["origin_type", "origin_id"]),
        Index(value = ["tag_id"])
    ]
)
internal data class CaptureTagRefEntity(
    @ColumnInfo(name = "origin_type")
    val originType: CaptureOriginType,
    @ColumnInfo(name = "origin_id")
    val originId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
    @ColumnInfo(name = "tag_name_snapshot")
    val tagNameSnapshot: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long
)

internal data class CaptureTagSummary(
    @ColumnInfo(name = "tag_id") val tagId: String,
    val name: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "usage_count") val usageCount: Int
)
