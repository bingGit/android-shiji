package com.bing.androidvoiceflow.capture.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bing.androidvoiceflow.capture.domain.CapturePayloadFactory
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState
import com.bing.androidvoiceflow.capture.network.CaptureApi
import com.bing.androidvoiceflow.capture.network.CaptureApiResult
import com.bing.androidvoiceflow.capture.network.CaptureSubmissionRunner
import com.bing.androidvoiceflow.capture.network.SubmissionRunResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CaptureDatabaseIntegrationTest {
    private lateinit var database: CaptureDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CaptureDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun singleCaptureFreezesIntoExactlyOneImmutableRequest() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("capture-1"),
            clientId = "cap_single01"
        )

        val captureId = repository.saveSingleCapture(
            NewSingleCapture(
                captureType = CaptureType.Article,
                rawText = "文章 https://example.com/article",
                sourceUrl = "https://example.com/article",
                titleHint = "测试文章",
                sourcePackage = "test.reader",
                receivedAt = 1_000L
            )
        )
        assertTrue(repository.updateSingleCaptureComment(captureId, "我的想法"))

        val clientId = repository.freezeSingleCapture(captureId)
        val capture = database.singleCaptureDao().getById(captureId)
        val requests = database.outboundCaptureRequestDao().getAll()

        assertEquals("cap_single01", clientId)
        assertEquals(SingleCaptureState.Frozen, capture?.state)
        assertEquals(1, requests.size)
        assertEquals("cap_single01", requests.single().clientId)
        assertTrue(requests.single().content.contains("## 我的想法"))
        assertTrue(requests.single().content.contains("## 来源"))
        assertTrue(runCatching { repository.freezeSingleCapture(captureId) }.isFailure)
        assertEquals(1, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun tagsResetGraceAndFreezeAsImmutablePayloadSnapshots() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("capture-tags"),
            clientId = "cap_tags0001"
        )
        val captureId = repository.saveSingleCapture(sampleSingleCapture(1_000L))

        val result = repository.toggleTagForSingleCapture(captureId, "tag_todo", 5_000L)
        assertEquals(TagToggleResult.Updated(true, 15_000L), result)
        assertEquals(15_000L, repository.getSingleCapture(captureId)?.graceDeadlineAt)

        val clientId = repository.freezeSingleCapture(captureId)
        val payload = JSONObject(requireNotNull(repository.getOutboundRequest(clientId)).payloadJson)
        val tags = payload.getJSONArray("user_tags")
        assertEquals(1, tags.length())
        assertEquals("tag_todo", tags.getJSONObject(0).getString("id"))
        assertEquals("待办", tags.getJSONObject(0).getString("name"))

        repository.renameTag("tag_todo", "稍后处理", 20_000L)
        assertEquals("待办", tags.getJSONObject(0).getString("name"))
    }

    @Test
    fun manualCaptureSavesSelectedTagsAtomically() = runTest {
        database.captureTagDao().insert(
            CaptureTagEntity("tag_todo", "待办", "待办", true, 0, 1_000L, 1_000L)
        )
        database.captureTagDao().insert(
            CaptureTagEntity("tag_life", "生活", "生活", true, 1, 1_000L, 1_000L)
        )
        val repository = repositoryWithIds(
            localIds = listOf("manual-tagged"),
            clientId = "cap_manualtags"
        )

        val captureId = repository.saveSingleCapture(
            sampleSingleCapture(1_000L),
            tagIds = setOf("tag_todo", "tag_life")
        )

        val tags = repository.getTagSnapshots(CaptureOriginType.SingleCapture, captureId)
        assertEquals(setOf("tag_todo", "tag_life"), tags.mapTo(mutableSetOf()) { it.tagId })
    }

    @Test
    fun staleFreezeWorkerCannotBypassExtendedTagDeadline() = runTest {
        val repository = repositoryWithIds(listOf("capture-race"), "cap_tagrace1")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(1_000L))
        repository.toggleTagForSingleCapture(captureId, "tag_work", 5_000L)

        assertEquals(SingleFreezeResult.NotDue(15_000L), repository.freezeSingleCaptureIfDue(captureId, 11_000L))
        assertTrue(database.outboundCaptureRequestDao().getAll().isEmpty())

        val frozen = repository.freezeSingleCaptureIfDue(captureId, 15_000L)
        assertTrue(frozen is SingleFreezeResult.Frozen)
        assertEquals(1, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun convertingSingleCaptureTransfersTagsToReadingSessionPayload() = runTest {
        val repository = repositoryWithIds(
            listOf("capture-convert", "session-convert", "block-convert"),
            "cap_tagread1"
        )
        val captureId = repository.saveSingleCapture(sampleSingleCapture(1_000L))
        repository.toggleTagForSingleCapture(captureId, "tag_life", 2_000L)
        val sessionId = repository.convertSingleCaptureToReadingSession(captureId, 3_000L)
        repository.appendReadingBlock(sessionId, ReadingBlockType.Excerpt, "摘录正文", 4_000L)

        val clientId = repository.completeReadingSession(sessionId)
        val tags = JSONObject(requireNotNull(repository.getOutboundRequest(clientId)).payloadJson)
            .getJSONArray("user_tags")
        assertEquals(1, tags.length())
        assertEquals("tag_life", tags.getJSONObject(0).getString("id"))
        assertEquals("生活", tags.getJSONObject(0).getString("name"))
    }

    @Test
    fun activeReadingSessionAcceptsTagsAndSubmitsThemWithContent() = runTest {
        val repository = repositoryWithIds(
            listOf("tagged-session", "tagged-block"),
            "cap_sessiontag"
        )
        val sessionId = repository.startReadingSession(
            NewReadingSession(null, "阅读摘录", "test.reader", null, 1_000L)
        )
        repository.appendReadingBlock(sessionId, ReadingBlockType.Excerpt, "摘录正文", 2_000L)

        assertTrue(
            repository.toggleTagForReadingSession(sessionId, "tag_work", 3_000L) is
                TagToggleResult.Updated
        )
        val clientId = repository.completeReadingSession(sessionId)
        val tags = JSONObject(requireNotNull(repository.getOutboundRequest(clientId)).payloadJson)
            .getJSONArray("user_tags")

        assertEquals(1, tags.length())
        assertEquals("tag_work", tags.getJSONObject(0).getString("id"))
        assertEquals("工作", tags.getJSONObject(0).getString("name"))
    }

    @Test
    fun readingSessionPersistsOrderedBlocksAndFreezesOnce() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("session-1", "block-1", "block-2", "block-3"),
            clientId = "cap_session01"
        )
        val sessionId = repository.startReadingSession(
            NewReadingSession(
                sourceUrl = "https://example.com/read",
                titleHint = "阅读测试",
                sourcePackage = "test.reader",
                rawShareText = "阅读测试 https://example.com/read",
                startedAt = 2_000L
            )
        )
        repository.appendReadingBlock(sessionId, ReadingBlockType.Excerpt, "摘录一", 2_100L)
        repository.appendReadingBlock(sessionId, ReadingBlockType.Comment, "想法一", 2_200L)
        repository.appendReadingBlock(sessionId, ReadingBlockType.Excerpt, "摘录二", 2_300L)

        val clientId = repository.completeReadingSession(sessionId)
        val session = database.readingSessionDao().getSession(sessionId)
        val blocks = database.readingSessionDao().getBlocks(sessionId)
        val request = database.outboundCaptureRequestDao().getAll().single()

        assertEquals("cap_session01", clientId)
        assertEquals(ReadingSessionState.Frozen, session?.state)
        assertEquals(listOf(1L, 2L, 3L), blocks.map(ReadingBlockEntity::position))
        assertTrue(request.content.indexOf("### 摘录 1") < request.content.indexOf("### 我的想法 1"))
        assertTrue(request.content.indexOf("### 我的想法 1") < request.content.indexOf("### 摘录 2"))
        assertTrue(runCatching { repository.completeReadingSession(sessionId) }.isFailure)
        assertEquals(1, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun duplicateClientIdRollsBackSecondOriginFreeze() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("capture-1", "capture-2"),
            clientId = "cap_duplicate"
        )
        val firstId = repository.saveSingleCapture(sampleSingleCapture(receivedAt = 1_000L))
        val secondId = repository.saveSingleCapture(sampleSingleCapture(receivedAt = 2_000L))

        repository.freezeSingleCapture(firstId)
        assertTrue(runCatching { repository.freezeSingleCapture(secondId) }.isFailure)

        assertEquals(
            SingleCaptureState.LocalGrace,
            database.singleCaptureDao().getById(secondId)?.state
        )
        assertEquals(1, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun convertingSingleCapturePreservesSourceAndRemovesGraceRecord() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("capture-convert", "session-convert"),
            clientId = "cap_convert01"
        )
        val captureId = repository.saveSingleCapture(
            NewSingleCapture(
                captureType = CaptureType.Article,
                rawText = "来源文字 https://example.com/source",
                sourceUrl = "https://example.com/source",
                titleHint = "来源标题",
                sourcePackage = "test.reader",
                receivedAt = 4_000L
            )
        )

        val sessionId = repository.convertSingleCaptureToReadingSession(captureId, 4_100L)
        val session = database.readingSessionDao().getSession(sessionId)

        assertEquals(null, database.singleCaptureDao().getById(captureId))
        assertEquals("来源文字 https://example.com/source", session?.rawShareText)
        assertEquals("https://example.com/source", session?.sourceUrl)
        assertEquals("来源标题", session?.titleHint)
        assertEquals(0, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun awaitingFinishOnlyMarksSessionAfterDeadline() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("session-awaiting"),
            clientId = "cap_awaiting1"
        )
        val sessionId = repository.startReadingSession(
            NewReadingSession(null, null, "test.reader", null, 10_000L)
        )
        val deadline = requireNotNull(repository.getReadingSession(sessionId)).inactivityDeadlineAt

        assertTrue(!repository.markReadingSessionAwaitingFinishIfDue(sessionId, deadline - 1))
        assertEquals(ReadingSessionState.Active, repository.getReadingSession(sessionId)?.state)
        assertTrue(repository.markReadingSessionAwaitingFinishIfDue(sessionId, deadline))
        assertEquals(ReadingSessionState.AwaitingFinish, repository.getReadingSession(sessionId)?.state)
    }

    @Test
    fun sourceBindingOnlyConsumesSingleCaptureWhenSessionIsUnbound() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("session-unbound", "capture-source", "session-bound", "capture-kept"),
            clientId = "cap_binding01"
        )
        val unboundSession = repository.startReadingSession(
            NewReadingSession(null, null, "test.reader", null, 20_000L)
        )
        val sourceCapture = repository.saveSingleCapture(
            NewSingleCapture(
                CaptureType.Article,
                "文章来源 https://example.com/source",
                "https://example.com/source",
                "文章来源",
                "test.reader",
                20_100L
            )
        )

        assertTrue(
            repository.bindSingleCaptureToReadingSession(
                sourceCapture,
                unboundSession,
                20_200L
            )
        )
        assertEquals(null, repository.getSingleCapture(sourceCapture))
        assertEquals("https://example.com/source", repository.getReadingSession(unboundSession)?.sourceUrl)

        val boundSession = repository.startReadingSession(
            NewReadingSession(
                "https://example.com/already",
                "已有来源",
                "test.reader",
                "已有来源文字",
                21_000L
            )
        )
        val keptCapture = repository.saveSingleCapture(sampleSingleCapture(21_100L))
        assertTrue(
            !repository.bindSingleCaptureToReadingSession(
                keptCapture,
                boundSession,
                21_200L
            )
        )
        assertTrue(repository.getSingleCapture(keptCapture) != null)
    }

    @Test
    fun oversizedCommentIsRejectedWithoutChangingGraceCapture() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("capture-overflow"),
            clientId = "cap_overflow1"
        )
        val captureId = repository.saveSingleCapture(
            NewSingleCapture(
                CaptureType.SharedText,
                "文".repeat(80_000),
                null,
                null,
                "test.reader",
                30_000L
            )
        )

        assertTrue(
            runCatching {
                repository.updateSingleCaptureComment(captureId, "想".repeat(20_000))
            }.isFailure
        )
        val capture = repository.getSingleCapture(captureId)
        assertEquals(SingleCaptureState.LocalGrace, capture?.state)
        assertEquals(null, capture?.comment)
    }

    @Test
    fun undoReactivatesAwaitingSessionAndAbandonDeletesBlocks() = runTest {
        val repository = repositoryWithIds(
            localIds = listOf("session-lifecycle", "block-lifecycle"),
            clientId = "cap_lifecycle"
        )
        val sessionId = repository.startReadingSession(
            NewReadingSession(null, null, "test.reader", null, 40_000L)
        )
        repository.appendReadingBlock(
            sessionId,
            ReadingBlockType.Excerpt,
            "待撤销内容",
            40_100L
        )
        val oldDeadline = requireNotNull(repository.getReadingSession(sessionId)).inactivityDeadlineAt
        assertTrue(repository.markReadingSessionAwaitingFinishIfDue(sessionId, oldDeadline))

        assertTrue(repository.undoLastReadingBlock(sessionId, oldDeadline + 1_000L))
        val reactivated = requireNotNull(repository.getReadingSession(sessionId))
        assertEquals(ReadingSessionState.Active, reactivated.state)
        assertTrue(reactivated.inactivityDeadlineAt > oldDeadline)

        repository.appendReadingBlock(
            sessionId,
            ReadingBlockType.Excerpt,
            "放弃时级联删除",
            oldDeadline + 2_000L
        )
        assertTrue(repository.abandonReadingSession(sessionId))
        assertEquals(null, repository.getReadingSession(sessionId))
        assertTrue(repository.getReadingBlocks(sessionId).isEmpty())
    }

    @Test
    fun fileDatabaseKeepsSessionAndBlocksAfterReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "capture-acceptance-reopen.db"
        context.deleteDatabase(databaseName)
        val firstDatabase = Room.databaseBuilder(
            context,
            CaptureDatabase::class.java,
            databaseName
        ).build()

        try {
            val repository = CaptureRepository(
                database = firstDatabase,
                payloadFactory = payloadFactory("cap_reopen01"),
                localIdGenerator = idSequence(listOf("session-reopen", "block-reopen"))
            )
            val sessionId = repository.startReadingSession(
                NewReadingSession(null, null, "test.reader", null, 3_000L)
            )
            repository.appendReadingBlock(
                sessionId,
                ReadingBlockType.Excerpt,
                "关闭数据库后仍应存在",
                3_100L
            )
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(
            context,
            CaptureDatabase::class.java,
            databaseName
        ).build()
        try {
            val sessions = reopenedDatabase.readingSessionDao().getAllSessions()
            val blocks = reopenedDatabase.readingSessionDao().getBlocks("session-reopen")
            assertEquals(1, sessions.size)
            assertEquals(1, blocks.size)
            assertEquals("关闭数据库后仍应存在", blocks.single().content)
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun outboundRequestRetriesSameFrozenPayloadAndDeletesOnlyAfterAcceptance() = runTest {
        val repository = repositoryWithIds(listOf("capture-send"), "cap_send0001")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(1_000_000L))
        val clientId = repository.freezeSingleCapture(captureId)
        val originalPayload = requireNotNull(repository.getOutboundRequest(clientId)).payloadJson

        val firstClaim = requireNotNull(repository.claimOutboundRequest(clientId, 1_001_000L))
        assertEquals(1, firstClaim.attemptCount)
        assertTrue(repository.markOutboundRetry(clientId, 1_100_000L, "HTTP 503"))
        assertEquals(null, repository.claimOutboundRequest(clientId, 1_099_999L))

        val secondClaim = requireNotNull(repository.claimOutboundRequest(clientId, 1_100_000L))
        assertEquals(2, secondClaim.attemptCount)
        assertEquals(originalPayload, secondClaim.payloadJson)
        assertEquals("cap_send0001", secondClaim.clientId)
        assertTrue(repository.markOutboundAccepted(clientId))
        assertEquals(null, repository.getOutboundRequest(clientId))
    }

    @Test
    fun authRequiredWaitsUntilCredentialsAreReset() = runTest {
        val repository = repositoryWithIds(listOf("capture-auth"), "cap_auth0001")
        val clientId = repository.freezeSingleCapture(
            repository.saveSingleCapture(sampleSingleCapture(2_000_000L))
        )
        requireNotNull(repository.claimOutboundRequest(clientId, 2_001_000L))
        assertTrue(repository.markOutboundAuthRequired(clientId, "认证失败"))
        assertEquals(OutboundRequestState.AuthRequired, repository.getOutboundRequest(clientId)?.state)
        assertEquals(null, repository.claimOutboundRequest(clientId, 2_002_000L))

        assertEquals(1, repository.resetAuthRequiredRequests())
        assertEquals(OutboundRequestState.Pending, repository.getOutboundRequest(clientId)?.state)
        assertTrue(repository.claimOutboundRequest(clientId, 2_003_000L) != null)
    }

    @Test
    fun maintenanceRecoversStaleLeaseAndStopsRequestsOlderThanRetentionWindow() = runTest {
        val repository = repositoryWithIds(
            listOf("capture-stale", "capture-expired"),
            "cap_stale001"
        )
        val staleClientId = repository.freezeSingleCapture(
            repository.saveSingleCapture(sampleSingleCapture(3_000_000L))
        )
        requireNotNull(repository.claimOutboundRequest(staleClientId, 3_001_000L))
        val recoveredAt = 3_001_000L + DEFAULT_SENDING_LEASE_MILLIS
        val recovered = repository.prepareOutboundQueue(recoveredAt)
        assertEquals(1, recovered.recovered)
        assertEquals(OutboundRequestState.RetryWait, repository.getOutboundRequest(staleClientId)?.state)

        assertTrue(repository.markOutboundAccepted(
            requireNotNull(repository.claimOutboundRequest(staleClientId, recoveredAt)).clientId
        ))
        val expiredClientId = repository.freezeSingleCapture(
            repository.saveSingleCapture(sampleSingleCapture(4_000_000L))
        )
        val expired = repository.prepareOutboundQueue(
            4_000_000L + DEFAULT_OUTBOUND_RETENTION_MILLIS
        )
        assertEquals(1, expired.expired)
        assertEquals(OutboundRequestState.Failed, repository.getOutboundRequest(expiredClientId)?.state)
    }

    @Test
    fun missingConfigurationLeavesRequestPendingWithoutCountingAnAttempt() = runTest {
        val repository = repositoryWithIds(listOf("capture-unconfigured"), "cap_noconfig")
        val clientId = repository.freezeSingleCapture(
            repository.saveSingleCapture(sampleSingleCapture(5_000_000L))
        )
        val result = CaptureSubmissionRunner(
            repository = repository,
            api = CaptureApi { _, _ -> CaptureApiResult.NotConfigured },
            clock = { 5_001_000L }
        ).run(clientId)

        assertEquals(SubmissionRunResult.NotConfigured, result)
        val request = requireNotNull(repository.getOutboundRequest(clientId))
        assertEquals(OutboundRequestState.Pending, request.state)
        assertEquals(0, request.attemptCount)
    }

    @Test
    fun concurrentSendersCanOnlyClaimRequestOnce() = runTest {
        val repository = repositoryWithIds(listOf("capture-race"), "cap_race0001")
        val clientId = repository.freezeSingleCapture(
            repository.saveSingleCapture(sampleSingleCapture(6_000_000L))
        )

        val claims = listOf(
            async(Dispatchers.Default) { repository.claimOutboundRequest(clientId, 6_001_000L) },
            async(Dispatchers.Default) { repository.claimOutboundRequest(clientId, 6_001_000L) }
        ).awaitAll()

        assertEquals(1, claims.count { it != null })
        assertEquals(1, repository.getOutboundRequest(clientId)?.attemptCount)
    }

    @Test
    fun manualNoteUsesSingleCaptureGraceAndFreezesIntoNoteContent() = runTest {
        val repository = repositoryWithIds(listOf("manual-capture"), "cap_manual01")
        val captureId = repository.saveSingleCapture(
            NewSingleCapture(
                captureType = CaptureType.ManualText,
                rawText = "主动输入的拾记",
                sourceUrl = null,
                titleHint = null,
                sourcePackage = null,
                receivedAt = 7_000_000L
            )
        )

        assertEquals(SingleCaptureState.LocalGrace, repository.getSingleCapture(captureId)?.state)
        val clientId = repository.freezeSingleCapture(captureId)
        val request = requireNotNull(repository.getOutboundRequest(clientId))

        assertTrue(request.content.contains("## 拾记"))
        assertTrue(request.content.contains("主动输入的拾记"))
        assertTrue(!request.content.contains("## 分享内容"))
    }

    @Test
    fun manualThoughtCanAppendToCurrentReadingSessionWithoutCompletingIt() = runTest {
        val repository = repositoryWithIds(
            listOf("manual-session", "manual-comment"),
            "cap_manual02"
        )
        val sessionId = repository.startReadingSession(
            NewReadingSession(null, null, "test.reader", null, 8_000_000L)
        )

        repository.appendReadingBlock(
            sessionId = sessionId,
            type = ReadingBlockType.Comment,
            content = "阅读时主动补充的判断",
            createdAt = 8_001_000L
        )

        val session = requireNotNull(repository.getReadingSession(sessionId))
        val block = repository.getReadingBlocks(sessionId).single()
        assertEquals(ReadingSessionState.Active, session.state)
        assertEquals(ReadingBlockType.Comment, block.type)
        assertEquals("阅读时主动补充的判断", block.content)
        assertTrue(database.outboundCaptureRequestDao().getAll().isEmpty())
    }

    @Test
    fun acceptedContentIsRetainedForThirtyDaysThenDeleted() = runTest {
        val repository = repositoryWithIds(listOf("retained-single"), "cap_retained1")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(9_000_000L))
        val clientId = repository.freezeSingleCapture(captureId)
        requireNotNull(repository.claimOutboundRequest(clientId, 9_001_000L))

        val acceptedAt = 9_002_000L
        assertTrue(repository.markOutboundAccepted(clientId, acceptedAt))
        assertEquals(null, repository.getOutboundRequest(clientId))
        assertTrue(repository.getSingleCapture(captureId) != null)

        repository.cleanupExpiredLocalContent(acceptedAt + DEFAULT_OUTBOUND_RETENTION_MILLIS - 1)
        assertTrue(repository.getSingleCapture(captureId) != null)

        val cleanup = repository.cleanupExpiredLocalContent(
            acceptedAt + DEFAULT_OUTBOUND_RETENTION_MILLIS
        )
        assertEquals(1, cleanup.deletedSingles)
        assertEquals(null, repository.getSingleCapture(captureId))
    }

    @Test
    fun deletingFailedTaskKeepsOriginForThirtyDaysAndRestartReusesFrozenRequest() = runTest {
        val repository = repositoryWithIds(listOf("failed-single"), "cap_failed01")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(10_000_000L))
        val clientId = repository.freezeSingleCapture(captureId)
        val original = requireNotNull(repository.getOutboundRequest(clientId))
        requireNotNull(repository.claimOutboundRequest(clientId, 10_001_000L))
        assertTrue(repository.markOutboundFailed(clientId, "HTTP 422"))

        assertTrue(repository.restartFailedOutboundRequest(clientId))
        val restarted = requireNotNull(repository.getOutboundRequest(clientId))
        assertEquals(OutboundRequestState.Pending, restarted.state)
        assertEquals(original.clientId, restarted.clientId)
        assertEquals(original.payloadJson, restarted.payloadJson)

        requireNotNull(repository.claimOutboundRequest(clientId, 10_002_000L))
        assertTrue(repository.markOutboundFailed(clientId, "HTTP 422"))
        val deletedAt = 10_003_000L
        assertTrue(repository.deleteFailedOutboundRequest(clientId, deletedAt))
        assertEquals(null, repository.getOutboundRequest(clientId))
        assertTrue(repository.getSingleCapture(captureId) != null)

        repository.cleanupExpiredLocalContent(deletedAt + DEFAULT_OUTBOUND_RETENTION_MILLIS)
        assertEquals(null, repository.getSingleCapture(captureId))
    }

    @Test
    fun cleanupSkipsOriginWhileAnyOutboundRequestStillReferencesIt() = runTest {
        val repository = repositoryWithIds(listOf("linked-single"), "cap_linked01")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(11_000_000L))
        repository.freezeSingleCapture(captureId)
        database.captureRetentionDao().upsert(
            CaptureRetentionEntity(
                originType = CaptureOriginType.SingleCapture,
                originId = captureId,
                retainedAt = 11_001_000L,
                deleteAfter = 11_001_000L,
                reason = "test"
            )
        )

        val cleanup = repository.cleanupExpiredLocalContent(11_001_000L)
        assertEquals(1, cleanup.skippedLinked)
        assertTrue(repository.getSingleCapture(captureId) != null)
        assertEquals(1, database.outboundCaptureRequestDao().getAll().size)
    }

    @Test
    fun thirtyDayExpiryDoesNotFailRequestInsideActiveSendingLease() = runTest {
        val repository = repositoryWithIds(listOf("leased-single"), "cap_leased01")
        val captureId = repository.saveSingleCapture(sampleSingleCapture(1_000L))
        val clientId = repository.freezeSingleCapture(captureId)
        val now = 1_000L + DEFAULT_OUTBOUND_RETENTION_MILLIS + 60_000L
        requireNotNull(repository.claimOutboundRequest(clientId, now - 60_000L))

        repository.prepareOutboundQueue(now)

        val request = requireNotNull(repository.getOutboundRequest(clientId))
        assertEquals(OutboundRequestState.Sending, request.state)
    }

    private fun repositoryWithIds(
        localIds: List<String>,
        clientId: String
    ): CaptureRepository = CaptureRepository(
        database = database,
        payloadFactory = payloadFactory(clientId),
        localIdGenerator = idSequence(localIds)
    )

    private fun payloadFactory(clientId: String) = CapturePayloadFactory(
        appVersion = "0.1.0-test",
        zoneId = ZoneId.of("Asia/Shanghai"),
        clientIdGenerator = { clientId }
    )

    private fun idSequence(values: List<String>): () -> String {
        val ids = ArrayDeque(values)
        return { ids.removeFirst() }
    }

    private fun sampleSingleCapture(receivedAt: Long) = NewSingleCapture(
        captureType = CaptureType.SharedText,
        rawText = "测试内容 $receivedAt",
        sourceUrl = null,
        titleHint = null,
        sourcePackage = "test.reader",
        receivedAt = receivedAt
    )
}
