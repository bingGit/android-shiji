package com.bing.androidvoiceflow.capture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SingleCaptureDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(capture: SingleCaptureEntity)

    @Query("SELECT * FROM single_captures WHERE capture_id = :captureId")
    suspend fun getById(captureId: String): SingleCaptureEntity?

    @Query("SELECT * FROM single_captures ORDER BY received_at DESC")
    fun observeAll(): Flow<List<SingleCaptureEntity>>

    @Query(
        """
        SELECT * FROM single_captures
        ORDER BY CASE WHEN state = 'local_grace' THEN 0 ELSE 1 END, received_at DESC
        LIMIT :limit
        """
    )
    fun observeInbox(limit: Int = 20): Flow<List<SingleCaptureEntity>>

    @Query("SELECT COUNT(*) FROM single_captures")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM single_captures ORDER BY received_at DESC")
    suspend fun getAll(): List<SingleCaptureEntity>

    @Query(
        """
        UPDATE single_captures
        SET comment = :comment
        WHERE capture_id = :captureId AND state = :expectedState
        """
    )
    suspend fun updateCommentIfState(
        captureId: String,
        comment: String?,
        expectedState: SingleCaptureState = SingleCaptureState.LocalGrace
    ): Int

    @Query(
        """
        UPDATE single_captures
        SET grace_deadline_at = :deadlineAt
        WHERE capture_id = :captureId AND state = :expectedState
        """
    )
    suspend fun updateGraceDeadlineIfState(
        captureId: String,
        deadlineAt: Long,
        expectedState: SingleCaptureState = SingleCaptureState.LocalGrace
    ): Int

    @Query(
        """
        UPDATE single_captures
        SET state = :nextState
        WHERE capture_id = :captureId AND state = :expectedState
        """
    )
    suspend fun updateStateIfCurrent(
        captureId: String,
        expectedState: SingleCaptureState,
        nextState: SingleCaptureState
    ): Int

    @Query(
        """
        DELETE FROM single_captures
        WHERE capture_id = :captureId AND state = :expectedState
        """
    )
    suspend fun deleteIfState(
        captureId: String,
        expectedState: SingleCaptureState = SingleCaptureState.LocalGrace
    ): Int

    @Query(
        """
        DELETE FROM single_captures
        WHERE capture_id = :captureId AND state = :frozenState
        """
    )
    suspend fun deleteFrozenById(
        captureId: String,
        frozenState: SingleCaptureState = SingleCaptureState.Frozen
    ): Int
}

@Dao
internal interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ReadingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBlock(block: ReadingBlockEntity)

    @Query("SELECT * FROM reading_sessions WHERE session_id = :sessionId")
    suspend fun getSession(sessionId: String): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    fun observeAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query(
        """
        SELECT * FROM reading_sessions
        ORDER BY
            CASE WHEN state IN ('active', 'awaiting_finish') THEN 0 ELSE 1 END,
            last_activity_at DESC
        LIMIT :limit
        """
    )
    fun observeInboxSessions(limit: Int = 20): Flow<List<ReadingSessionEntity>>

    @Query(
        """
        SELECT * FROM reading_blocks
        WHERE session_id IN (
            SELECT session_id FROM reading_sessions
            ORDER BY
                CASE WHEN state IN ('active', 'awaiting_finish') THEN 0 ELSE 1 END,
                last_activity_at DESC
            LIMIT :limit
        )
        ORDER BY session_id ASC, position ASC
        """
    )
    fun observeInboxBlocks(limit: Int = 20): Flow<List<ReadingBlockEntity>>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    suspend fun getAllSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_blocks ORDER BY session_id ASC, position ASC")
    fun observeAllBlocks(): Flow<List<ReadingBlockEntity>>

    @Query(
        """
        SELECT * FROM reading_sessions
        WHERE state IN (:editableStates)
        ORDER BY last_activity_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestEditableSession(
        editableStates: List<ReadingSessionState> = listOf(
            ReadingSessionState.Active,
            ReadingSessionState.AwaitingFinish
        )
    ): ReadingSessionEntity?

    @Query(
        """
        SELECT * FROM reading_blocks
        WHERE session_id = :sessionId
        ORDER BY position ASC
        """
    )
    suspend fun getBlocks(sessionId: String): List<ReadingBlockEntity>

    @Query("SELECT COALESCE(MAX(position), 0) FROM reading_blocks WHERE session_id = :sessionId")
    suspend fun getLastPosition(sessionId: String): Long

    @Query(
        """
        UPDATE reading_sessions
        SET last_activity_at = :lastActivityAt,
            inactivity_deadline_at = :inactivityDeadlineAt,
            state = :activeState
        WHERE session_id = :sessionId AND state IN (:editableStates)
        """
    )
    suspend fun touchEditableSession(
        sessionId: String,
        lastActivityAt: Long,
        inactivityDeadlineAt: Long,
        activeState: ReadingSessionState = ReadingSessionState.Active,
        editableStates: List<ReadingSessionState> = listOf(
            ReadingSessionState.Active,
            ReadingSessionState.AwaitingFinish
        )
    ): Int

    @Query(
        """
        UPDATE reading_sessions
        SET source_url = :sourceUrl,
            title_hint = :titleHint,
            source_package = :sourcePackage,
            raw_share_text = :rawShareText,
            last_activity_at = :lastActivityAt,
            inactivity_deadline_at = :inactivityDeadlineAt,
            state = :activeState
        WHERE session_id = :sessionId
          AND state IN (:editableStates)
          AND source_url IS NULL
          AND raw_share_text IS NULL
        """
    )
    suspend fun bindSourceIfUnbound(
        sessionId: String,
        sourceUrl: String?,
        titleHint: String?,
        sourcePackage: String?,
        rawShareText: String,
        lastActivityAt: Long,
        inactivityDeadlineAt: Long,
        activeState: ReadingSessionState = ReadingSessionState.Active,
        editableStates: List<ReadingSessionState> = listOf(
            ReadingSessionState.Active,
            ReadingSessionState.AwaitingFinish
        )
    ): Int

    @Query(
        """
        DELETE FROM reading_blocks
        WHERE block_id = (
            SELECT block_id FROM reading_blocks
            WHERE session_id = :sessionId
            ORDER BY position DESC
            LIMIT 1
        )
        AND EXISTS (
            SELECT 1 FROM reading_sessions
            WHERE session_id = :sessionId AND state IN (:editableStates)
        )
        """
    )
    suspend fun deleteLastBlockIfEditable(
        sessionId: String,
        editableStates: List<ReadingSessionState> = listOf(
            ReadingSessionState.Active,
            ReadingSessionState.AwaitingFinish
        )
    ): Int

    @Query(
        """
        UPDATE reading_sessions
        SET state = :nextState
        WHERE session_id = :sessionId AND state IN (:expectedStates)
        """
    )
    suspend fun updateStateIfCurrent(
        sessionId: String,
        expectedStates: List<ReadingSessionState>,
        nextState: ReadingSessionState
    ): Int

    @Query(
        """
        DELETE FROM reading_sessions
        WHERE session_id = :sessionId AND state IN (:editableStates)
        """
    )
    suspend fun deleteIfEditable(
        sessionId: String,
        editableStates: List<ReadingSessionState> = listOf(
            ReadingSessionState.Active,
            ReadingSessionState.AwaitingFinish
        )
    ): Int

    @Query(
        """
        UPDATE reading_sessions
        SET state = :awaitingState
        WHERE session_id = :sessionId
          AND state = :activeState
          AND inactivity_deadline_at <= :now
        """
    )
    suspend fun markAwaitingFinishIfDue(
        sessionId: String,
        now: Long,
        activeState: ReadingSessionState = ReadingSessionState.Active,
        awaitingState: ReadingSessionState = ReadingSessionState.AwaitingFinish
    ): Int

    @Query(
        """
        DELETE FROM reading_sessions
        WHERE session_id = :sessionId AND state = :frozenState
        """
    )
    suspend fun deleteFrozenById(
        sessionId: String,
        frozenState: ReadingSessionState = ReadingSessionState.Frozen
    ): Int
}

@Dao
internal interface OutboundCaptureRequestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(request: OutboundCaptureRequestEntity)

    @Query("SELECT * FROM outbound_capture_requests WHERE client_id = :clientId")
    suspend fun getByClientId(clientId: String): OutboundCaptureRequestEntity?

    @Query("SELECT * FROM outbound_capture_requests ORDER BY created_at ASC")
    fun observeAll(): Flow<List<OutboundCaptureRequestEntity>>

    @Query(
        """
        SELECT * FROM outbound_capture_requests
        WHERE state IN ('failed', 'auth_required')
        ORDER BY created_at ASC
        """
    )
    fun observeActionable(): Flow<List<OutboundCaptureRequestEntity>>

    @Query("SELECT COUNT(*) FROM outbound_capture_requests")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM outbound_capture_requests ORDER BY created_at ASC")
    suspend fun getAll(): List<OutboundCaptureRequestEntity>

    @Query(
        """
        SELECT * FROM outbound_capture_requests
        WHERE client_id = :clientId
          AND (
            state = :pendingState
            OR (state = :retryState AND (next_retry_at IS NULL OR next_retry_at <= :now))
          )
        LIMIT 1
        """
    )
    suspend fun getClaimableByClientId(
        clientId: String,
        now: Long,
        pendingState: OutboundRequestState = OutboundRequestState.Pending,
        retryState: OutboundRequestState = OutboundRequestState.RetryWait
    ): OutboundCaptureRequestEntity?

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :sendingState,
            attempt_count = attempt_count + 1,
            next_retry_at = NULL,
            send_started_at = :sendStartedAt,
            last_error = NULL
        WHERE client_id = :clientId AND state IN (:claimableStates)
        """
    )
    suspend fun claimForSending(
        clientId: String,
        sendStartedAt: Long,
        sendingState: OutboundRequestState = OutboundRequestState.Sending,
        claimableStates: List<OutboundRequestState> = listOf(
            OutboundRequestState.Pending,
            OutboundRequestState.RetryWait
        )
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :nextState,
            next_retry_at = :nextRetryAt,
            send_started_at = NULL,
            last_error = :lastError
        WHERE client_id = :clientId AND state = :expectedState
        """
    )
    suspend fun finishAttemptIfSending(
        clientId: String,
        nextState: OutboundRequestState,
        nextRetryAt: Long?,
        lastError: String?,
        expectedState: OutboundRequestState = OutboundRequestState.Sending
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :retryState,
            next_retry_at = :nextRetryAt,
            send_started_at = NULL,
            last_error = :lastError
        WHERE state = :sendingState
          AND send_started_at IS NOT NULL
          AND send_started_at <= :staleBefore
        """
    )
    suspend fun recoverStaleSending(
        staleBefore: Long,
        nextRetryAt: Long,
        lastError: String,
        sendingState: OutboundRequestState = OutboundRequestState.Sending,
        retryState: OutboundRequestState = OutboundRequestState.RetryWait
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :failedState,
            next_retry_at = NULL,
            send_started_at = NULL,
            last_error = :lastError
        WHERE state IN (:automaticStates) AND created_at <= :expiredBefore
        """
    )
    suspend fun failExpiredAutomaticRequests(
        expiredBefore: Long,
        lastError: String,
        failedState: OutboundRequestState = OutboundRequestState.Failed,
        automaticStates: List<OutboundRequestState> = listOf(
            OutboundRequestState.Pending,
            OutboundRequestState.RetryWait
        )
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :pendingState,
            next_retry_at = NULL,
            send_started_at = NULL,
            last_error = NULL
        WHERE state = :authState
        """
    )
    suspend fun resetAuthRequired(
        authState: OutboundRequestState = OutboundRequestState.AuthRequired,
        pendingState: OutboundRequestState = OutboundRequestState.Pending
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :pendingState,
            next_retry_at = NULL,
            send_started_at = NULL,
            last_error = NULL
        WHERE client_id = :clientId AND state = :failedState
        """
    )
    suspend fun restartFailed(
        clientId: String,
        failedState: OutboundRequestState = OutboundRequestState.Failed,
        pendingState: OutboundRequestState = OutboundRequestState.Pending
    ): Int

    @Query(
        """
        UPDATE outbound_capture_requests
        SET state = :pendingState,
            attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
            next_retry_at = NULL,
            send_started_at = NULL,
            last_error = NULL
        WHERE client_id = :clientId AND state = :sendingState
        """
    )
    suspend fun returnUnsentToPending(
        clientId: String,
        sendingState: OutboundRequestState = OutboundRequestState.Sending,
        pendingState: OutboundRequestState = OutboundRequestState.Pending
    ): Int

    @Query(
        """
        DELETE FROM outbound_capture_requests
        WHERE client_id = :clientId AND state = :expectedState
        """
    )
    suspend fun deleteByClientIdIfState(
        clientId: String,
        expectedState: OutboundRequestState = OutboundRequestState.Sending
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbound_capture_requests
        WHERE origin_type = :originType AND origin_id = :originId
        """
    )
    suspend fun countByOrigin(originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType, originId: String): Int
}

@Dao
internal interface CaptureRetentionDao {
    @Upsert
    suspend fun upsert(retention: CaptureRetentionEntity)

    @Query("SELECT * FROM capture_retention WHERE delete_after <= :now ORDER BY delete_after ASC")
    suspend fun getDue(now: Long): List<CaptureRetentionEntity>

    @Query(
        """
        DELETE FROM capture_retention
        WHERE origin_type = :originType AND origin_id = :originId
        """
    )
    suspend fun delete(
        originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType,
        originId: String
    ): Int
}

@Dao
internal interface CaptureTagDao {
    @Query(
        "SELECT * FROM capture_tags ORDER BY is_pinned DESC, sort_order ASC, created_at ASC"
    )
    fun observeAll(): Flow<List<CaptureTagEntity>>

    @Query(
        "SELECT * FROM capture_tags ORDER BY is_pinned DESC, sort_order ASC, created_at ASC"
    )
    suspend fun getAll(): List<CaptureTagEntity>

    @Query(
        "SELECT * FROM capture_tags ORDER BY is_pinned DESC, sort_order ASC, created_at ASC LIMIT :limit"
    )
    suspend fun getQuick(limit: Int): List<CaptureTagEntity>

    @Query("SELECT * FROM capture_tags WHERE tag_id = :tagId")
    suspend fun getById(tagId: String): CaptureTagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: CaptureTagEntity)

    @Query(
        "UPDATE capture_tags SET name = :name, normalized_name = :normalizedName, updated_at = :updatedAt WHERE tag_id = :tagId"
    )
    suspend fun rename(tagId: String, name: String, normalizedName: String, updatedAt: Long): Int

    @Query("UPDATE capture_tags SET is_pinned = :pinned, updated_at = :updatedAt WHERE tag_id = :tagId")
    suspend fun setPinned(tagId: String, pinned: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM capture_tags WHERE tag_id = :tagId")
    suspend fun delete(tagId: String): Int

    @Query(
        """
        SELECT t.tag_id, t.name, t.is_pinned, t.sort_order, COUNT(r.tag_id) AS usage_count
        FROM capture_tags t
        LEFT JOIN capture_tag_refs r ON r.tag_id = t.tag_id
        GROUP BY t.tag_id
        ORDER BY t.is_pinned DESC, t.sort_order ASC, t.created_at ASC
        """
    )
    fun observeSummaries(): Flow<List<CaptureTagSummary>>
}

@Dao
internal interface CaptureTagRefDao {
    @Query(
        "SELECT * FROM capture_tag_refs WHERE origin_type = :originType AND origin_id = :originId ORDER BY added_at ASC"
    )
    suspend fun getForOrigin(
        originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType,
        originId: String
    ): List<CaptureTagRefEntity>

    @Query(
        "SELECT COUNT(*) FROM capture_tag_refs WHERE origin_type = :originType AND origin_id = :originId"
    )
    suspend fun countForOrigin(
        originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType,
        originId: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ref: CaptureTagRefEntity): Long

    @Query(
        "DELETE FROM capture_tag_refs WHERE origin_type = :originType AND origin_id = :originId AND tag_id = :tagId"
    )
    suspend fun deleteTag(
        originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType,
        originId: String,
        tagId: String
    ): Int

    @Query("DELETE FROM capture_tag_refs WHERE origin_type = :originType AND origin_id = :originId")
    suspend fun deleteForOrigin(
        originType: com.bing.androidvoiceflow.capture.domain.CaptureOriginType,
        originId: String
    ): Int

}
