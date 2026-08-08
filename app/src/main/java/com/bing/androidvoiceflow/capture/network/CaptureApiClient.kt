package com.bing.androidvoiceflow.capture.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Credentials
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed interface CaptureApiResult {
    data class Accepted(val duplicate: Boolean) : CaptureApiResult
    data class Retryable(val reason: String, val retryAfterMillis: Long? = null) : CaptureApiResult
    data class AuthRequired(val reason: String) : CaptureApiResult
    data class PermanentFailure(val reason: String) : CaptureApiResult
    data object NotConfigured : CaptureApiResult
}

internal sealed interface CaptureHealthResult {
    data object Healthy : CaptureHealthResult
    data object AuthRequired : CaptureHealthResult
    data class Failed(val reason: String) : CaptureHealthResult
    data object NotConfigured : CaptureHealthResult
}

internal fun interface CaptureApi {
    suspend fun submit(clientId: String, payloadJson: String): CaptureApiResult

    fun isConfigured(): Boolean = true
}

internal class OkHttpCaptureApi(
    private val configProvider: CaptureServiceConfigProvider,
    private val client: OkHttpClient = defaultCaptureHttpClient()
) : CaptureApi {
    override fun isConfigured(): Boolean = configProvider.load() != null

    override suspend fun submit(clientId: String, payloadJson: String): CaptureApiResult {
        val config = configProvider.load() ?: return CaptureApiResult.NotConfigured
        val request = Request.Builder()
            .url(config.createCaptureUrl())
            .header("Authorization", config.basicAuthorization())
            .header("Accept", JSON_MEDIA_TYPE.toString())
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.peekBody(MAX_RESPONSE_BYTES).string()
                    classifyCaptureApiResponse(
                        statusCode = response.code,
                        responseBody = body,
                        expectedClientId = clientId,
                        retryAfterMillis = response.header("Retry-After")?.toRetryAfterMillis()
                    )
                }
            } catch (_: IOException) {
                CaptureApiResult.Retryable("网络连接失败")
            }
        }
    }

    suspend fun checkHealth(): CaptureHealthResult {
        val config = configProvider.load() ?: return CaptureHealthResult.NotConfigured
        val request = Request.Builder()
            .url(config.healthUrl())
            .header("Authorization", config.basicAuthorization())
            .header("Accept", JSON_MEDIA_TYPE.toString())
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> CaptureHealthResult.Healthy
                        response.code == 401 -> CaptureHealthResult.AuthRequired
                        else -> CaptureHealthResult.Failed(
                            "Health API 返回 HTTP ${response.code}"
                        )
                    }
                }
            } catch (_: IOException) {
                CaptureHealthResult.Failed("无法连接 Capture Service")
            }
        }
    }
}

internal fun classifyCaptureApiResponse(
    statusCode: Int,
    responseBody: String,
    expectedClientId: String,
    retryAfterMillis: Long? = null
): CaptureApiResult = when {
    statusCode == 202 -> runCatching {
        val json = JSONObject(responseBody)
        require(json.optString("status") == "accepted") { "status 字段不是 accepted" }
        require(json.optString("client_id") == expectedClientId) { "client_id 与本地请求不一致" }
        CaptureApiResult.Accepted(duplicate = json.optBoolean("duplicate", false))
    }.getOrElse {
        CaptureApiResult.PermanentFailure("Capture API 成功响应不符合协议")
    }

    statusCode == 401 -> CaptureApiResult.AuthRequired("Capture API 认证已失效")
    statusCode == 408 || statusCode == 429 || statusCode in 500..599 ->
        CaptureApiResult.Retryable("Capture API 暂时不可用（HTTP $statusCode）", retryAfterMillis)

    statusCode in 400..499 ->
        CaptureApiResult.PermanentFailure("Capture API 拒绝请求（HTTP $statusCode）")

    else -> CaptureApiResult.PermanentFailure("Capture API 返回意外状态（HTTP $statusCode）")
}

private fun String.toRetryAfterMillis(): Long? = trim().toLongOrNull()
    ?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
    ?.times(1_000L)

private fun defaultCaptureHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private fun CaptureServiceConfig.basicAuthorization(): String =
    Credentials.basic(username, password)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_RESPONSE_BYTES = 64L * 1_024L
private const val MAX_RETRY_AFTER_SECONDS = 6L * 60L * 60L
