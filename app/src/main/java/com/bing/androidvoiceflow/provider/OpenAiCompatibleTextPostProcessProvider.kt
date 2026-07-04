package com.bing.androidvoiceflow.provider

import com.bing.androidvoiceflow.core.ConnectionTestResult
import com.bing.androidvoiceflow.core.ProviderConfig
import com.bing.androidvoiceflow.core.TextPostProcessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiCompatibleTextPostProcessProvider(
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : TextPostProcessProvider {
    suspend fun process(
        text: String,
        config: ProviderConfig,
        actionTitle: String,
        actionInstruction: String
    ): String {
        validateConfig(config)
        require(text.isNotBlank()) { "没有可处理的转写文本" }

        val requestJson = chatCompletionRequest(
            model = config.postProcessModel.trim(),
            systemPrompt = systemPrompt(config, actionTitle, actionInstruction),
            userText = text.trim()
        )

        return executeChatCompletion(config, requestJson).content
    }

    suspend fun testConnection(config: ProviderConfig): ConnectionTestResult {
        return try {
            validateConfig(config)
            val requestJson = chatCompletionRequest(
                model = config.postProcessModel.trim(),
                systemPrompt = "你是一个连接测试助手。只回复 OK。",
                userText = "请回复 OK。",
                maxTokens = 16
            )
            val response = executeChatCompletion(config, requestJson)
            ConnectionTestResult(
                success = true,
                summary = "后处理连接测试成功",
                detail = "Endpoint: ${response.endpoint}\nModel: ${config.postProcessModel}\nResponse: ${response.content.take(80)}"
            )
        } catch (error: Exception) {
            ConnectionTestResult(
                success = false,
                summary = "后处理连接测试失败",
                detail = error.message
            )
        }
    }

    override suspend fun summarize(text: String, config: ProviderConfig): String {
        return process(
            text = text,
            config = config,
            actionTitle = "提炼要点",
            actionInstruction = "把口述内容整理成 3 到 6 条要点，保留具体信息和判断，不添加原文没有的结论。"
        )
    }

    override suspend fun polish(text: String, config: ProviderConfig): String {
        return process(
            text = text,
            config = config,
            actionTitle = "润色表达",
            actionInstruction = "保留原意，去掉口语停顿和重复，让表达更自然、清楚、有节奏。"
        )
    }

    override suspend fun rewrite(text: String, config: ProviderConfig): String {
        return process(
            text = text,
            config = config,
            actionTitle = "整理改写成文",
            actionInstruction = "把碎片口述整理成一段可继续编辑的创作草稿，结构清楚，语气自然。"
        )
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private data class ChatCompletionResponse(
    val content: String,
    val endpoint: String
)

private fun validateConfig(config: ProviderConfig) {
    when {
        config.postProcessApiKey.isBlank() -> error("后处理 API Key 为空")
        config.postProcessBaseUrl.isBlank() -> error("后处理 Base URL 为空")
        config.postProcessModel.isBlank() -> error("后处理文本模型为空")
    }
}

private suspend fun OpenAiCompatibleTextPostProcessProvider.executeChatCompletion(
    config: ProviderConfig,
    requestJson: JSONObject
): ChatCompletionResponse {
    return withContext(Dispatchers.IO) {
        val candidateUrls = config.postProcessBaseUrl.chatCompletionUrlCandidates()
        var lastFailure: String? = null

        for (endpoint in candidateUrls) {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${config.postProcessApiKey}")
                .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return@withContext ChatCompletionResponse(
                        content = body.extractAssistantContent(),
                        endpoint = endpoint
                    )
                }

                lastFailure = body.extractProviderErrorMessage(
                    code = response.code,
                    message = response.message,
                    endpoint = endpoint
                )
                if (response.code != 404) {
                    throw IllegalStateException(lastFailure)
                }
            }
        }

        throw IllegalStateException(
            lastFailure ?: "后处理请求失败：没有可用的 Chat Completions Endpoint"
        )
    }
}

private fun chatCompletionRequest(
    model: String,
    systemPrompt: String,
    userText: String,
    maxTokens: Int? = null
): JSONObject {
    return JSONObject()
        .put("model", model)
        .put("temperature", 0.4)
        .apply {
            if (maxTokens != null) {
                put("max_tokens", maxTokens)
            }
        }
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt)
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userText)
                )
        )
}

private fun systemPrompt(
    config: ProviderConfig,
    actionTitle: String,
    actionInstruction: String
): String {
    return buildString {
        appendLine(config.postProcessPrompt.ifBlank { "你是一个帮助创作者整理语音灵感的中文写作助手。" })
        appendLine()
        appendLine("当前任务：$actionTitle")
        appendLine(actionInstruction)
        appendLine()
        appendLine("要求：")
        appendLine("- 只输出处理后的正文，不解释你的处理过程。")
        appendLine("- 不编造事实，不替用户添加未经表达的具体经历或数据。")
        appendLine("- 保留原文里有价值的观点、例子和语气。")
    }
}

private fun String.chatCompletionUrlCandidates(): List<String> {
    val trimmed = trim().removeSuffix("/")
    if (trimmed.isBlank()) return emptyList()
    val normalized = when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        else -> "https://$trimmed"
    }
    val base = normalized.withoutKnownOpenAiEndpoint()
    val candidates = linkedSetOf<String>()

    if (normalized.endsWith("/chat/completions")) {
        candidates += normalized
    }
    if (!base.endsWith("/v1")) {
        candidates += "$base/v1/chat/completions"
    }
    candidates += "$base/chat/completions"

    return candidates.toList()
}

private fun String.withoutKnownOpenAiEndpoint(): String {
    val knownSuffixes = listOf(
        "/chat/completions",
        "/realtime",
        "/responses",
        "/audio/transcriptions",
        "/audio/speech",
        "/models"
    )
    return knownSuffixes.fold(this) { current, suffix ->
        current.removeSuffix(suffix)
    }.removeSuffix("/")
}

private fun String.extractAssistantContent(): String {
    val json = JSONObject(this)
    val choices = json.optJSONArray("choices") ?: error("后处理模型没有返回 choices")
    val message = choices.optJSONObject(0)?.optJSONObject("message")
    val content = message?.optString("content").orEmpty().trim()
    if (content.isBlank()) error("后处理模型返回了空内容")
    return content
}

private fun String.extractProviderErrorMessage(
    code: Int,
    message: String,
    endpoint: String
): String {
    val providerMessage = runCatching {
        val json = JSONObject(this)
        json.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
    return listOfNotNull(
        "后处理请求失败：HTTP $code $message",
        "Endpoint: $endpoint",
        providerMessage?.let { "服务端返回：$it" }
    ).joinToString("\n")
}
