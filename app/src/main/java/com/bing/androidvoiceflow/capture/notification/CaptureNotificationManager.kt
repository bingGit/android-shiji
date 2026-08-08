package com.bing.androidvoiceflow.capture.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.bing.androidvoiceflow.R
import com.bing.androidvoiceflow.capture.data.SingleCaptureEntity
import com.bing.androidvoiceflow.capture.data.CaptureRepository
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.ui.CaptureCommentActivity
import com.bing.androidvoiceflow.capture.ui.CaptureInboxActivity
import com.bing.androidvoiceflow.capture.ui.CaptureTagPickerActivity
import com.bing.androidvoiceflow.capture.ui.StartReadingActivity

internal object CaptureNotificationManager {
    private const val CHANNEL_ACTIVE = "reading_capture_active"
    private const val CHANNEL_STATUS = "reading_capture_status"
    private const val SINGLE_NOTIFICATION_SEED = 10_000
    private const val SESSION_NOTIFICATION_SEED = 20_000

    fun canNotify(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOfNotNull(
            manager.getNotificationChannel(CHANNEL_ACTIVE),
            manager.getNotificationChannel(CHANNEL_STATUS)
        )
        return channels.isEmpty() || channels.all { it.importance != NotificationManager.IMPORTANCE_NONE }
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ACTIVE,
                    "阅读捕获进行中",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "撤销、补充想法和完成阅读摘录"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_STATUS,
                    "阅读捕获状态",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "本地保存和待发送状态"
                    setShowBadge(false)
                }
            )
        )
    }

    fun showSingleGrace(
        context: Context,
        capture: SingleCaptureEntity,
        selectedTagNames: List<String> = emptyList(),
        quickTagNames: List<String> = listOf("待办", "灵感", "工作", "生活")
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_capture_tile)
            .setContentTitle("拾记 · 等待提交")
            .setContentText(capture.titleHint ?: capture.rawText.take(64))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(capture.titleHint ?: capture.rawText.take(160))
                        append("\n\n")
                        if (selectedTagNames.isEmpty()) {
                            append("可选标签或补充想法，随后一次性提交")
                        } else {
                            append("标签 · ")
                            append(selectedTagNames.joinToString("、"))
                        }
                    }
                )
            )
            .setSubText("10 秒")
            .setContentIntent(inboxIntent(context))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(
                tagAction(
                    context,
                    capture.captureId,
                    CaptureNotificationContract.TARGET_SINGLE,
                    selectedTagNames,
                    quickTagNames
                )
            )
            .addAction(
                0,
                "补一句",
                commentIntent(
                    context,
                    CaptureNotificationContract.TARGET_SINGLE,
                    capture.captureId
                )
            )
            .addAction(
                0,
                "开始摘录",
                startReadingIntent(context, capture.captureId)
            )
        notifyIfAllowed(context, singleNotificationId(capture.captureId), builder.build())
    }

    fun showSingleFrozen(
        context: Context,
        capture: SingleCaptureEntity,
        selectedTagNames: List<String>
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_capture_tile)
            .setContentTitle("拾记 · 已提交")
            .setContentText(capture.titleHint ?: capture.rawText.take(64))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(capture.titleHint ?: capture.rawText.take(160))
                        if (selectedTagNames.isNotEmpty()) {
                            append("\n\n标签 · ")
                            append(selectedTagNames.joinToString("、"))
                        }
                        append(" · 服务端处理中")
                    }
                )
            )
            .setSubText("已提交")
            .setContentIntent(inboxIntent(context))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(context, singleNotificationId(capture.captureId), notification)
    }

    fun showReadingSession(
        context: Context,
        sessionId: String,
        blockCount: Int,
        titleHint: String?,
        awaitingFinish: Boolean = false,
        hasContent: Boolean = blockCount > 0,
        selectedTagNames: List<String> = emptyList(),
        quickTagNames: List<String> = listOf("待办", "工作", "生活", "灵感")
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val title = if (awaitingFinish) {
            "有 $blockCount 段内容待完成"
        } else {
            "正在摘录 · 已收集 $blockCount 段"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_capture_tile)
            .setContentTitle(title)
            .setContentText(titleHint ?: "未命名阅读摘录")
            .setSubText(
                if (selectedTagNames.isEmpty()) null
                else selectedTagNames.joinToString(" · ") { "#$it" }
            )
            .setContentIntent(inboxIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(!awaitingFinish)
            .addAction(
                tagAction(
                    context,
                    sessionId,
                    CaptureNotificationContract.TARGET_SESSION,
                    selectedTagNames,
                    quickTagNames
                )
            )
            .addAction(
                0,
                "补想法",
                commentIntent(context, CaptureNotificationContract.TARGET_SESSION, sessionId)
            )
        if (hasContent) {
            builder.addAction(
                0,
                "完成",
                broadcastIntent(
                    context,
                    CaptureNotificationContract.ACTION_COMPLETE_SESSION,
                    sessionId
                )
            )
        }
        notifyIfAllowed(context, sessionNotificationId(sessionId), builder.build())
    }

    suspend fun refreshReadingSession(
        context: Context,
        repository: CaptureRepository,
        sessionId: String,
        awaitingFinish: Boolean = false
    ) {
        val session = repository.getReadingSession(sessionId) ?: return
        if (session.state == ReadingSessionState.Frozen) {
            showSessionCompleted(context, sessionId)
            return
        }
        if (session.state !in setOf(ReadingSessionState.Active, ReadingSessionState.AwaitingFinish)) return
        val blocks = repository.getReadingBlocks(sessionId)
        showReadingSession(
            context = context,
            sessionId = sessionId,
            blockCount = blocks.count { it.type == ReadingBlockType.Excerpt },
            titleHint = session.titleHint,
            awaitingFinish = awaitingFinish || session.state == ReadingSessionState.AwaitingFinish,
            hasContent = blocks.isNotEmpty(),
            selectedTagNames = repository.getTagSnapshots(
                CaptureOriginType.ReadingSession,
                sessionId
            ).map { it.tagNameSnapshot },
            quickTagNames = repository.getQuickTags().map { it.name }
        )
        if (repository.getReadingSession(sessionId)?.state == ReadingSessionState.Frozen) {
            showSessionCompleted(context, sessionId)
        }
    }

    fun showSessionCompleted(context: Context, sessionId: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_capture_tile)
            .setContentTitle("阅读摘录已完成")
            .setContentText("已生成一条待发送请求")
            .setContentIntent(inboxIntent(context))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(context, sessionNotificationId(sessionId), notification)
    }

    fun cancelSingle(context: Context, captureId: String) {
        NotificationManagerCompat.from(context).cancel(singleNotificationId(captureId))
    }

    fun cancelSession(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(sessionNotificationId(sessionId))
    }

    private fun inboxIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        30_001,
        Intent(context, CaptureInboxActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun commentIntent(context: Context, target: String, recordId: String): PendingIntent {
        val intent = Intent(context, CaptureCommentActivity::class.java)
            .putExtra(CaptureNotificationContract.EXTRA_COMMENT_TARGET, target)
            .putExtra(CaptureNotificationContract.EXTRA_RECORD_ID, recordId)
        return PendingIntent.getActivity(
            context,
            requestCode("comment:$target", recordId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startReadingIntent(context: Context, captureId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode("start-reading", captureId),
            StartReadingActivity.createIntent(context, captureId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun tagAction(
        context: Context,
        captureId: String,
        target: String,
        selectedTagNames: List<String>,
        quickTagNames: List<String>
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(CaptureNotificationContract.EXTRA_TAG_CHOICE)
            .setLabel("选择标签")
            .setChoices((quickTagNames + CaptureNotificationContract.CHOICE_MORE_TAGS).toTypedArray())
            .setAllowFreeFormInput(false)
            .build()
        val intent = CaptureTagPickerActivity.createNotificationIntent(context, captureId, target)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode(CaptureNotificationContract.ACTION_SELECT_TAG, captureId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(
            0,
            if (selectedTagNames.isEmpty()) "加标签" else "继续加标签",
            pendingIntent
        ).addRemoteInput(remoteInput).build()
    }

    private fun broadcastIntent(context: Context, action: String, recordId: String): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java)
            .setAction(action)
            .putExtra(CaptureNotificationContract.EXTRA_RECORD_ID, recordId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, recordId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun singleNotificationId(id: String) = SINGLE_NOTIFICATION_SEED xor id.hashCode()

    private fun sessionNotificationId(id: String) = SESSION_NOTIFICATION_SEED xor id.hashCode()

    private fun requestCode(action: String, id: String) = ("$action:$id".hashCode() and Int.MAX_VALUE)

    @SuppressLint("MissingPermission")
    private fun notifyIfAllowed(context: Context, notificationId: Int, notification: Notification) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}
