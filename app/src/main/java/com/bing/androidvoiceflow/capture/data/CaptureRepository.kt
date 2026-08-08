package com.bing.androidvoiceflow.capture.data

import androidx.room.withTransaction
import com.bing.androidvoiceflow.capture.domain.CaptureClientPlatform
import com.bing.androidvoiceflow.capture.domain.CaptureContentAssembler
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.CapturePayloadFactory
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.CaptureUserTag
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingContentBlock
import com.bing.androidvoiceflow.capture.domain.ReadingSessionContent
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureContent
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState
import java.util.UUID

internal const val DEFAULT_SINGLE_CAPTURE_GRACE_MILLIS = 10_000L
internal const val MAX_TAGS_PER_CAPTURE = 10
internal const val DEFAULT_READING_SESSION_INACTIVITY_MILLIS = 30L * 60L * 1_000L
internal const val DEFAULT_SENDING_LEASE_MILLIS = 15L * 60L * 1_000L
internal const val DEFAULT_OUTBOUND_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L

internal data class NewSingleCapture(
    val captureType: CaptureType,
    val rawText: String,
    val sourceUrl: String?,
    val titleHint: String?,
    val sourcePackage: String?,
    val receivedAt: Long
)

internal data class NewReadingSession(
    val sourceUrl: String?,
    val titleHint: String?,
    val sourcePackage: String?,
    val rawShareText: String?,
    val startedAt: Long
)

internal class CaptureRepository(
    private val database: CaptureDatabase,
    private val payloadFactory: CapturePayloadFactory,
    private val localIdGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val singleCaptureDao = database.singleCaptureDao()
    private val readingSessionDao = database.readingSessionDao()
    private val outboundRequestDao = database.outboundCaptureRequestDao()
    private val retentionDao = database.captureRetentionDao()
    private val tagDao = database.captureTagDao()
    private val tagRefDao = database.captureTagRefDao()

    suspend fun saveSingleCapture(
        input: NewSingleCapture,
        tagIds: Set<String> = emptySet()
    ): String = database.withTransaction {
        require(input.rawText.isNotBlank()) { "捕获内容不能为空" }
        val captureId = localIdGenerator()
        singleCaptureDao.insert(
            SingleCaptureEntity(
                captureId = captureId,
                captureType = input.captureType,
                rawText = input.rawText.trim(),
                comment = null,
                sourceUrl = input.sourceUrl,
                titleHint = input.titleHint,
                sourcePackage = input.sourcePackage,
                receivedAt = input.receivedAt,
                graceDeadlineAt = input.receivedAt + DEFAULT_SINGLE_CAPTURE_GRACE_MILLIS
            )
        )
        insertTagRefs(
            originType = CaptureOriginType.SingleCapture,
            originId = captureId,
            tagIds = tagIds,
            addedAt = input.receivedAt
        )
        captureId
    }

    suspend fun updateSingleCaptureComment(captureId: String, comment: String?): Boolean =
        database.withTransaction {
            val capture = singleCaptureDao.getById(captureId) ?: return@withTransaction false
            if (capture.state != SingleCaptureState.LocalGrace) return@withTransaction false
            val normalizedComment = comment?.trim()?.takeIf(String::isNotEmpty)
            CaptureContentAssembler.assembleSingle(
                SingleCaptureContent(
                    captureType = capture.captureType,
                    rawText = capture.rawText,
                    comment = normalizedComment,
                    sourceUrl = capture.sourceUrl,
                    titleHint = capture.titleHint
                )
            )
            singleCaptureDao.updateCommentIfState(captureId, normalizedComment) == 1
        }

    suspend fun extendSingleCaptureGrace(captureId: String, deadlineAt: Long): Boolean =
        singleCaptureDao.updateGraceDeadlineIfState(captureId, deadlineAt) == 1

    suspend fun undoSingleCapture(captureId: String): Boolean = database.withTransaction {
        if (singleCaptureDao.deleteIfState(captureId) != 1) return@withTransaction false
        tagRefDao.deleteForOrigin(CaptureOriginType.SingleCapture, captureId)
        true
    }

    suspend fun getSingleCapture(captureId: String): SingleCaptureEntity? =
        singleCaptureDao.getById(captureId)

    fun observeTagSummaries() = tagDao.observeSummaries()

    suspend fun getAvailableTags(): List<CaptureTagEntity> = tagDao.getAll()

    suspend fun getQuickTags(limit: Int = 4): List<CaptureTagEntity> = tagDao.getQuick(limit)

    suspend fun getTagSnapshots(
        originType: CaptureOriginType,
        originId: String
    ): List<CaptureTagRefEntity> = tagRefDao.getForOrigin(originType, originId)

    suspend fun toggleTagForSingleCapture(
        captureId: String,
        tagId: String,
        now: Long
    ): TagToggleResult = database.withTransaction {
        val capture = singleCaptureDao.getById(captureId)
            ?: return@withTransaction TagToggleResult.NotEditable
        if (capture.state != SingleCaptureState.LocalGrace) {
            return@withTransaction TagToggleResult.NotEditable
        }
        val existing = tagRefDao.getForOrigin(CaptureOriginType.SingleCapture, captureId)
        val selected = existing.firstOrNull { it.tagId == tagId }
        val added = if (selected != null) {
            tagRefDao.deleteTag(CaptureOriginType.SingleCapture, captureId, tagId)
            false
        } else {
            if (existing.size >= MAX_TAGS_PER_CAPTURE) return@withTransaction TagToggleResult.LimitReached
            val tag = tagDao.getById(tagId) ?: return@withTransaction TagToggleResult.TagMissing
            tagRefDao.insert(
                CaptureTagRefEntity(
                    originType = CaptureOriginType.SingleCapture,
                    originId = captureId,
                    tagId = tag.tagId,
                    tagNameSnapshot = tag.name,
                    addedAt = now
                )
            )
            true
        }
        val deadlineAt = now + DEFAULT_SINGLE_CAPTURE_GRACE_MILLIS
        check(singleCaptureDao.updateGraceDeadlineIfState(captureId, deadlineAt) == 1) {
            "捕获记录状态已变化"
        }
        TagToggleResult.Updated(added, deadlineAt)
    }

    suspend fun toggleTagForReadingSession(
        sessionId: String,
        tagId: String,
        now: Long
    ): TagToggleResult = database.withTransaction {
        val session = readingSessionDao.getSession(sessionId)
            ?: return@withTransaction TagToggleResult.NotEditable
        if (!session.state.isEditable()) return@withTransaction TagToggleResult.NotEditable
        val existing = tagRefDao.getForOrigin(CaptureOriginType.ReadingSession, sessionId)
        val selected = existing.firstOrNull { it.tagId == tagId }
        val added = if (selected != null) {
            tagRefDao.deleteTag(CaptureOriginType.ReadingSession, sessionId, tagId)
            false
        } else {
            if (existing.size >= MAX_TAGS_PER_CAPTURE) return@withTransaction TagToggleResult.LimitReached
            val tag = tagDao.getById(tagId) ?: return@withTransaction TagToggleResult.TagMissing
            tagRefDao.insert(
                CaptureTagRefEntity(
                    originType = CaptureOriginType.ReadingSession,
                    originId = sessionId,
                    tagId = tag.tagId,
                    tagNameSnapshot = tag.name,
                    addedAt = now
                )
            )
            true
        }
        TagToggleResult.Updated(added, session.inactivityDeadlineAt)
    }

    suspend fun createTag(name: String, now: Long = System.currentTimeMillis()): CaptureTagEntity {
        val normalized = name.normalizedTagName()
        val tag = CaptureTagEntity(
            tagId = "tag_${localIdGenerator().replace("-", "")}",
            name = name.trim(),
            normalizedName = normalized,
            isPinned = false,
            sortOrder = (tagDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = now,
            updatedAt = now
        )
        tagDao.insert(tag)
        return tag
    }

    suspend fun renameTag(tagId: String, name: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = name.normalizedTagName()
        return tagDao.rename(tagId, name.trim(), normalized, now) == 1
    }

    suspend fun deleteTag(tagId: String): Boolean = tagDao.delete(tagId) == 1

    suspend fun setTagPinned(tagId: String, pinned: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        tagDao.setPinned(tagId, pinned, now) == 1

    suspend fun freezeSingleCapture(captureId: String): String = database.withTransaction {
        val capture = requireNotNull(singleCaptureDao.getById(captureId)) { "捕获记录不存在" }
        require(capture.state == SingleCaptureState.LocalGrace) { "捕获记录已经冻结或放弃" }

        freezeSingleCaptureLocked(capture).clientId
    }

    suspend fun freezeSingleCaptureIfDue(captureId: String, now: Long): SingleFreezeResult =
        database.withTransaction {
            val capture = singleCaptureDao.getById(captureId) ?: return@withTransaction SingleFreezeResult.Noop
            if (capture.state != SingleCaptureState.LocalGrace) return@withTransaction SingleFreezeResult.Noop
            if (capture.graceDeadlineAt > now) {
                return@withTransaction SingleFreezeResult.NotDue(capture.graceDeadlineAt)
            }
            freezeSingleCaptureLocked(capture)
        }

    private suspend fun freezeSingleCaptureLocked(capture: SingleCaptureEntity): SingleFreezeResult.Frozen {
        val captureId = capture.captureId

        val content = CaptureContentAssembler.assembleSingle(
            SingleCaptureContent(
                captureType = capture.captureType,
                rawText = capture.rawText,
                comment = capture.comment,
                sourceUrl = capture.sourceUrl,
                titleHint = capture.titleHint
            )
        )
        val payload = payloadFactory.create(
            content = content,
            createdAtMillis = capture.receivedAt,
            platform = capture.captureType.toClientPlatform(),
            userTags = tagRefDao.getForOrigin(CaptureOriginType.SingleCapture, captureId)
                .map { CaptureUserTag(it.tagId, it.tagNameSnapshot) }
        )
        check(
            singleCaptureDao.updateStateIfCurrent(
                captureId = captureId,
                expectedState = SingleCaptureState.LocalGrace,
                nextState = SingleCaptureState.Frozen
            ) == 1
        ) { "捕获记录状态已变化" }
        outboundRequestDao.insert(
            payload.toEntity(
                originType = CaptureOriginType.SingleCapture,
                originId = captureId
            )
        )
        return SingleFreezeResult.Frozen(
            clientId = payload.clientId,
            capture = capture,
            tagNames = tagRefDao.getForOrigin(CaptureOriginType.SingleCapture, captureId)
                .map { it.tagNameSnapshot }
        )
    }

    suspend fun startReadingSession(input: NewReadingSession): String {
        val sessionId = localIdGenerator()
        readingSessionDao.insertSession(
            ReadingSessionEntity(
                sessionId = sessionId,
                sourceUrl = input.sourceUrl,
                titleHint = input.titleHint,
                sourcePackage = input.sourcePackage,
                rawShareText = input.rawShareText,
                startedAt = input.startedAt,
                lastActivityAt = input.startedAt,
                inactivityDeadlineAt = input.startedAt + DEFAULT_READING_SESSION_INACTIVITY_MILLIS
            )
        )
        return sessionId
    }

    suspend fun convertSingleCaptureToReadingSession(
        captureId: String,
        startedAt: Long
    ): String = database.withTransaction {
        val capture = requireNotNull(singleCaptureDao.getById(captureId)) { "捕获记录不存在" }
        require(capture.state == SingleCaptureState.LocalGrace) { "捕获记录已经进入冻结流程" }
        val sessionId = localIdGenerator()
        readingSessionDao.insertSession(
            ReadingSessionEntity(
                sessionId = sessionId,
                sourceUrl = capture.sourceUrl,
                titleHint = capture.titleHint,
                sourcePackage = capture.sourcePackage,
                rawShareText = capture.rawText,
                startedAt = startedAt,
                lastActivityAt = startedAt,
                inactivityDeadlineAt = startedAt + DEFAULT_READING_SESSION_INACTIVITY_MILLIS
            )
        )
        tagRefDao.getForOrigin(CaptureOriginType.SingleCapture, captureId).forEach { ref ->
            tagRefDao.insert(
                ref.copy(originType = CaptureOriginType.ReadingSession, originId = sessionId)
            )
        }
        check(singleCaptureDao.deleteIfState(captureId) == 1) { "捕获记录状态已变化" }
        tagRefDao.deleteForOrigin(CaptureOriginType.SingleCapture, captureId)
        sessionId
    }

    suspend fun bindSingleCaptureToReadingSession(
        captureId: String,
        sessionId: String,
        boundAt: Long
    ): Boolean = database.withTransaction {
        val capture = singleCaptureDao.getById(captureId) ?: return@withTransaction false
        if (capture.state != SingleCaptureState.LocalGrace) return@withTransaction false
        val updated = readingSessionDao.bindSourceIfUnbound(
            sessionId = sessionId,
            sourceUrl = capture.sourceUrl,
            titleHint = capture.titleHint,
            sourcePackage = capture.sourcePackage,
            rawShareText = capture.rawText,
            lastActivityAt = boundAt,
            inactivityDeadlineAt = boundAt + DEFAULT_READING_SESSION_INACTIVITY_MILLIS
        )
        if (updated != 1) return@withTransaction false
        tagRefDao.getForOrigin(CaptureOriginType.SingleCapture, captureId).forEach { ref ->
            tagRefDao.insert(
                ref.copy(originType = CaptureOriginType.ReadingSession, originId = sessionId)
            )
        }
        check(singleCaptureDao.deleteIfState(captureId) == 1) { "捕获记录状态已变化" }
        tagRefDao.deleteForOrigin(CaptureOriginType.SingleCapture, captureId)
        true
    }

    suspend fun getLatestEditableReadingSession(): ReadingSessionEntity? =
        readingSessionDao.getLatestEditableSession()

    suspend fun getReadingSession(sessionId: String): ReadingSessionEntity? =
        readingSessionDao.getSession(sessionId)

    suspend fun getReadingBlocks(sessionId: String): List<ReadingBlockEntity> =
        readingSessionDao.getBlocks(sessionId)

    suspend fun appendReadingBlock(
        sessionId: String,
        type: ReadingBlockType,
        content: String,
        createdAt: Long,
        tagIds: Set<String> = emptySet()
    ): String = database.withTransaction {
        val session = requireNotNull(readingSessionDao.getSession(sessionId)) { "阅读摘录不存在" }
        require(session.state.isEditable()) { "阅读摘录已经冻结或放弃" }
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "内容块不能为空" }

        val blockId = localIdGenerator()
        readingSessionDao.insertBlock(
            ReadingBlockEntity(
                blockId = blockId,
                sessionId = sessionId,
                position = readingSessionDao.getLastPosition(sessionId) + 1,
                type = type,
                content = normalizedContent,
                createdAt = createdAt
            )
        )
        check(
            readingSessionDao.touchEditableSession(
                sessionId = sessionId,
                lastActivityAt = createdAt,
                inactivityDeadlineAt = createdAt + DEFAULT_READING_SESSION_INACTIVITY_MILLIS
            ) == 1
        ) { "阅读摘录状态已变化" }
        insertTagRefs(
            originType = CaptureOriginType.ReadingSession,
            originId = sessionId,
            tagIds = tagIds,
            addedAt = createdAt
        )
        blockId
    }

    private suspend fun insertTagRefs(
        originType: CaptureOriginType,
        originId: String,
        tagIds: Set<String>,
        addedAt: Long
    ) {
        if (tagIds.isEmpty()) return
        val existingIds = tagRefDao.getForOrigin(originType, originId).mapTo(mutableSetOf()) { it.tagId }
        val missingIds = tagIds - existingIds
        require(existingIds.size + missingIds.size <= MAX_TAGS_PER_CAPTURE) {
            "一条内容最多添加 $MAX_TAGS_PER_CAPTURE 个标签"
        }
        missingIds.forEach { tagId ->
            val tag = requireNotNull(tagDao.getById(tagId)) { "标签不存在" }
            tagRefDao.insert(
                CaptureTagRefEntity(
                    originType = originType,
                    originId = originId,
                    tagId = tag.tagId,
                    tagNameSnapshot = tag.name,
                    addedAt = addedAt
                )
            )
        }
    }

    suspend fun undoLastReadingBlock(
        sessionId: String,
        activityAt: Long = System.currentTimeMillis()
    ): Boolean = database.withTransaction {
        if (readingSessionDao.deleteLastBlockIfEditable(sessionId) != 1) {
            return@withTransaction false
        }
        check(
            readingSessionDao.touchEditableSession(
                sessionId = sessionId,
                lastActivityAt = activityAt,
                inactivityDeadlineAt = activityAt + DEFAULT_READING_SESSION_INACTIVITY_MILLIS
            ) == 1
        ) { "阅读摘录状态已变化" }
        true
    }

    suspend fun abandonReadingSession(sessionId: String): Boolean = database.withTransaction {
        if (readingSessionDao.deleteIfEditable(sessionId) != 1) return@withTransaction false
        tagRefDao.deleteForOrigin(CaptureOriginType.ReadingSession, sessionId)
        true
    }

    suspend fun markReadingSessionAwaitingFinishIfDue(sessionId: String, now: Long): Boolean =
        readingSessionDao.markAwaitingFinishIfDue(sessionId, now) == 1

    suspend fun completeReadingSession(sessionId: String): String = database.withTransaction {
        val session = requireNotNull(readingSessionDao.getSession(sessionId)) { "阅读摘录不存在" }
        require(session.state.isEditable()) { "阅读摘录已经冻结或放弃" }
        val blocks = readingSessionDao.getBlocks(sessionId)
        require(blocks.isNotEmpty()) { "阅读摘录没有可提交内容" }

        val content = CaptureContentAssembler.assembleReadingSession(
            ReadingSessionContent(
                blocks = blocks.map { block ->
                    ReadingContentBlock(
                        position = block.position,
                        type = block.type,
                        content = block.content
                    )
                },
                sourceUrl = session.sourceUrl,
                titleHint = session.titleHint,
                rawShareText = session.rawShareText
            )
        )
        val payload = payloadFactory.create(
            content = content,
            createdAtMillis = session.startedAt,
            platform = CaptureClientPlatform.AndroidReadingSession,
            userTags = tagRefDao.getForOrigin(CaptureOriginType.ReadingSession, sessionId)
                .map { CaptureUserTag(it.tagId, it.tagNameSnapshot) }
        )
        check(
            readingSessionDao.updateStateIfCurrent(
                sessionId = sessionId,
                expectedStates = listOf(
                    ReadingSessionState.Active,
                    ReadingSessionState.AwaitingFinish
                ),
                nextState = ReadingSessionState.Frozen
            ) == 1
        ) { "阅读摘录状态已变化" }
        outboundRequestDao.insert(
            payload.toEntity(
                originType = CaptureOriginType.ReadingSession,
                originId = sessionId
            )
        )
        payload.clientId
    }

    suspend fun finishOrAbandonReadingSession(sessionId: String): ReadingSessionFinishResult =
        database.withTransaction {
            val session = requireNotNull(readingSessionDao.getSession(sessionId)) { "阅读摘录不存在" }
            require(session.state.isEditable()) { "阅读摘录已经冻结或放弃" }
            if (readingSessionDao.getBlocks(sessionId).isEmpty()) {
                check(readingSessionDao.deleteIfEditable(sessionId) == 1) { "阅读摘录状态已变化" }
                tagRefDao.deleteForOrigin(CaptureOriginType.ReadingSession, sessionId)
                ReadingSessionFinishResult.Abandoned
            } else {
                ReadingSessionFinishResult.Completed(completeReadingSession(sessionId))
            }
        }

    suspend fun prepareOutboundQueue(now: Long): OutboundQueueMaintenance =
        database.withTransaction {
            val recovered = outboundRequestDao.recoverStaleSending(
                staleBefore = now - DEFAULT_SENDING_LEASE_MILLIS,
                nextRetryAt = now,
                lastError = "发送中断，已恢复到重试队列"
            )
            val expired = outboundRequestDao.failExpiredAutomaticRequests(
                expiredBefore = now - DEFAULT_OUTBOUND_RETENTION_MILLIS,
                lastError = "待发送时间超过 30 天，请人工处理"
            )
            OutboundQueueMaintenance(recovered = recovered, expired = expired)
        }

    suspend fun getOutboundRequest(clientId: String): OutboundCaptureRequestEntity? =
        outboundRequestDao.getByClientId(clientId)

    suspend fun claimOutboundRequest(clientId: String, now: Long): OutboundCaptureRequestEntity? =
        database.withTransaction {
            val candidate = outboundRequestDao.getClaimableByClientId(clientId, now)
                ?: return@withTransaction null
            if (outboundRequestDao.claimForSending(clientId, now) != 1) {
                return@withTransaction null
            }
            candidate.copy(
                state = OutboundRequestState.Sending,
                attemptCount = candidate.attemptCount + 1,
                nextRetryAt = null,
                sendStartedAt = now,
                lastError = null
            )
        }

    suspend fun markOutboundAccepted(
        clientId: String,
        acceptedAt: Long = System.currentTimeMillis()
    ): Boolean = database.withTransaction {
        val request = outboundRequestDao.getByClientId(clientId) ?: return@withTransaction false
        if (request.state != OutboundRequestState.Sending) return@withTransaction false
        if (outboundRequestDao.deleteByClientIdIfState(clientId) != 1) return@withTransaction false
        retentionDao.upsert(request.toRetention(acceptedAt, "synced"))
        true
    }

    suspend fun markOutboundRetry(
        clientId: String,
        nextRetryAt: Long,
        error: String
    ): Boolean = outboundRequestDao.finishAttemptIfSending(
        clientId = clientId,
        nextState = OutboundRequestState.RetryWait,
        nextRetryAt = nextRetryAt,
        lastError = error.toSafeStoredError()
    ) == 1

    suspend fun markOutboundAuthRequired(clientId: String, error: String): Boolean =
        outboundRequestDao.finishAttemptIfSending(
            clientId = clientId,
            nextState = OutboundRequestState.AuthRequired,
            nextRetryAt = null,
            lastError = error.toSafeStoredError()
        ) == 1

    suspend fun markOutboundFailed(clientId: String, error: String): Boolean =
        outboundRequestDao.finishAttemptIfSending(
            clientId = clientId,
            nextState = OutboundRequestState.Failed,
            nextRetryAt = null,
            lastError = error.toSafeStoredError()
        ) == 1

    suspend fun returnOutboundToPending(clientId: String): Boolean =
        outboundRequestDao.returnUnsentToPending(clientId) == 1

    suspend fun resetAuthRequiredRequests(): Int = outboundRequestDao.resetAuthRequired()

    suspend fun restartFailedOutboundRequest(clientId: String): Boolean =
        outboundRequestDao.restartFailed(clientId) == 1

    suspend fun deleteFailedOutboundRequest(
        clientId: String,
        deletedAt: Long = System.currentTimeMillis()
    ): Boolean = database.withTransaction {
        val request = outboundRequestDao.getByClientId(clientId) ?: return@withTransaction false
        if (request.state != OutboundRequestState.Failed) return@withTransaction false
        if (
            outboundRequestDao.deleteByClientIdIfState(
                clientId,
                OutboundRequestState.Failed
            ) != 1
        ) return@withTransaction false
        retentionDao.upsert(request.toRetention(deletedAt, "sync_task_deleted"))
        true
    }

    suspend fun cleanupExpiredLocalContent(now: Long): LocalCleanupResult =
        database.withTransaction {
            var deletedSingles = 0
            var deletedSessions = 0
            var skippedLinked = 0
            var skippedProtected = 0
            retentionDao.getDue(now).forEach { retention ->
                if (outboundRequestDao.countByOrigin(retention.originType, retention.originId) > 0) {
                    skippedLinked += 1
                    return@forEach
                }
                val originDeleted = when (retention.originType) {
                    CaptureOriginType.SingleCapture -> {
                        val origin = singleCaptureDao.getById(retention.originId)
                        if (origin != null && origin.state != SingleCaptureState.Frozen) {
                            skippedProtected += 1
                            return@forEach
                        }
                        singleCaptureDao.deleteFrozenById(retention.originId).also {
                            deletedSingles += it
                        }
                    }
                    CaptureOriginType.ReadingSession -> {
                        val origin = readingSessionDao.getSession(retention.originId)
                        if (origin != null && origin.state != ReadingSessionState.Frozen) {
                            skippedProtected += 1
                            return@forEach
                        }
                        readingSessionDao.deleteFrozenById(retention.originId).also {
                            deletedSessions += it
                        }
                    }
                }
                if (originDeleted == 1 || (
                        retention.originType == CaptureOriginType.SingleCapture &&
                            singleCaptureDao.getById(retention.originId) == null
                        ) || (
                        retention.originType == CaptureOriginType.ReadingSession &&
                            readingSessionDao.getSession(retention.originId) == null
                        )
                ) {
                    tagRefDao.deleteForOrigin(retention.originType, retention.originId)
                    retentionDao.delete(retention.originType, retention.originId)
                }
            }
            LocalCleanupResult(deletedSingles, deletedSessions, skippedLinked, skippedProtected)
        }

    private fun CaptureType.toClientPlatform(): CaptureClientPlatform = when (this) {
        CaptureType.Article,
        CaptureType.SharedText,
        CaptureType.ManualText -> CaptureClientPlatform.AndroidShare
        CaptureType.Excerpt -> CaptureClientPlatform.AndroidProcessText
    }

    private fun ReadingSessionState.isEditable(): Boolean =
        this == ReadingSessionState.Active || this == ReadingSessionState.AwaitingFinish
}

internal data class OutboundQueueMaintenance(val recovered: Int, val expired: Int)

internal data class LocalCleanupResult(
    val deletedSingles: Int,
    val deletedSessions: Int,
    val skippedLinked: Int,
    val skippedProtected: Int
)

internal sealed interface TagToggleResult {
    data class Updated(val added: Boolean, val deadlineAt: Long) : TagToggleResult
    data object LimitReached : TagToggleResult
    data object NotEditable : TagToggleResult
    data object TagMissing : TagToggleResult
}

internal sealed interface SingleFreezeResult {
    data class Frozen(
        val clientId: String,
        val capture: SingleCaptureEntity,
        val tagNames: List<String>
    ) : SingleFreezeResult
    data class NotDue(val deadlineAt: Long) : SingleFreezeResult
    data object Noop : SingleFreezeResult
}

internal sealed interface ReadingSessionFinishResult {
    data class Completed(val clientId: String) : ReadingSessionFinishResult
    data object Abandoned : ReadingSessionFinishResult
}

private fun String.normalizedTagName(): String {
    val normalized = trim().lowercase()
    require(normalized.isNotEmpty()) { "标签名称不能为空" }
    require(normalized.codePointCount(0, normalized.length) <= 12) { "标签名称最多 12 个字" }
    return normalized
}

private fun String.toSafeStoredError(): String =
    replace(Regex("[\\r\\n]+"), " ").take(500).ifBlank { "未知发送错误" }

private fun com.bing.androidvoiceflow.capture.domain.FrozenCapturePayload.toEntity(
    originType: CaptureOriginType,
    originId: String
): OutboundCaptureRequestEntity = OutboundCaptureRequestEntity(
    clientId = clientId,
    originType = originType,
    originId = originId,
    content = content,
    payloadJson = payloadJson,
    createdAt = createdAtMillis,
    clientPlatform = clientPlatform.storageValue
)

private fun OutboundCaptureRequestEntity.toRetention(
    retainedAt: Long,
    reason: String
): CaptureRetentionEntity = CaptureRetentionEntity(
    originType = originType,
    originId = originId,
    retainedAt = retainedAt,
    deleteAfter = retainedAt + DEFAULT_OUTBOUND_RETENTION_MILLIS,
    reason = reason
)
