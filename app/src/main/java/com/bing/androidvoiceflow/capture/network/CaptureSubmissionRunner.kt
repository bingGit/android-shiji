package com.bing.androidvoiceflow.capture.network

import com.bing.androidvoiceflow.capture.data.CaptureRepository
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import kotlin.math.pow

internal sealed interface SubmissionRunResult {
    data object Finished : SubmissionRunResult
    data object Retry : SubmissionRunResult
    data object NotConfigured : SubmissionRunResult
}

internal class CaptureSubmissionRunner(
    private val repository: CaptureRepository,
    private val api: CaptureApi,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun run(clientId: String): SubmissionRunResult {
        val now = clock()
        repository.prepareOutboundQueue(now)
        if (!api.isConfigured()) return SubmissionRunResult.NotConfigured
        val current = repository.getOutboundRequest(clientId) ?: return SubmissionRunResult.Finished
        when (current.state) {
            OutboundRequestState.AuthRequired,
            OutboundRequestState.Failed -> return SubmissionRunResult.Finished
            OutboundRequestState.Sending -> return SubmissionRunResult.Retry
            OutboundRequestState.RetryWait -> {
                if ((current.nextRetryAt ?: Long.MIN_VALUE) > now) return SubmissionRunResult.Retry
            }
            OutboundRequestState.Pending -> Unit
        }

        val claimed = repository.claimOutboundRequest(clientId, now)
            ?: return SubmissionRunResult.Retry
        return when (val result = api.submit(claimed.clientId, claimed.payloadJson)) {
            is CaptureApiResult.Accepted -> {
                check(repository.markOutboundAccepted(claimed.clientId, clock())) { "发送请求状态已变化" }
                SubmissionRunResult.Finished
            }
            is CaptureApiResult.AuthRequired -> {
                check(repository.markOutboundAuthRequired(claimed.clientId, result.reason)) {
                    "发送请求状态已变化"
                }
                SubmissionRunResult.Finished
            }
            is CaptureApiResult.PermanentFailure -> {
                check(repository.markOutboundFailed(claimed.clientId, result.reason)) {
                    "发送请求状态已变化"
                }
                SubmissionRunResult.Finished
            }
            is CaptureApiResult.Retryable -> {
                val retryDelay = maxOf(
                    retryDelayMillis(claimed.attemptCount),
                    result.retryAfterMillis ?: 0L
                )
                check(
                    repository.markOutboundRetry(
                        clientId = claimed.clientId,
                        nextRetryAt = now + retryDelay,
                        error = result.reason
                    )
                ) { "发送请求状态已变化" }
                SubmissionRunResult.Retry
            }
            CaptureApiResult.NotConfigured -> {
                check(repository.returnOutboundToPending(claimed.clientId)) {
                    "发送请求状态已变化"
                }
                SubmissionRunResult.NotConfigured
            }
        }
    }
}

internal fun retryDelayMillis(attemptCount: Int): Long {
    val exponent = (attemptCount - 1).coerceIn(0, 10)
    return (BASE_RETRY_MILLIS * 2.0.pow(exponent)).toLong().coerceAtMost(MAX_RETRY_MILLIS)
}

private const val BASE_RETRY_MILLIS = 30_000L
private const val MAX_RETRY_MILLIS = 6L * 60L * 60L * 1_000L
