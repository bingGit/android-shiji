package com.bing.androidvoiceflow.capture.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val clientIdPattern = Regex("cap_[A-Za-z0-9_-]{8,100}")

internal data class FrozenCapturePayload(
    val clientId: String,
    val content: String,
    val createdAtMillis: Long,
    val clientPlatform: CaptureClientPlatform,
    val payloadJson: String
)

internal data class CaptureUserTag(val id: String, val name: String)

internal class CapturePayloadFactory(
    private val appVersion: String,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clientIdGenerator: () -> String = {
        "cap_${UUID.randomUUID().toString().replace("-", "")}"
    }
) {
    fun create(
        content: String,
        createdAtMillis: Long,
        platform: CaptureClientPlatform,
        userTags: List<CaptureUserTag> = emptyList()
    ): FrozenCapturePayload {
        require(content.isNotBlank()) { "冻结内容不能为空" }
        require(content.codePointCount(0, content.length) <= MAX_ASSEMBLED_CONTENT_CODE_POINTS) {
            "冻结内容超过服务端限制"
        }

        val clientId = clientIdGenerator()
        require(clientIdPattern.matches(clientId)) { "client_id 格式无效" }
        val createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(createdAtMillis).atZone(zoneId)
        )
        val payloadJson = JSONObject()
            .put("client_id", clientId)
            .put("content", content)
            .put("created_at", createdAt)
            .put(
                "user_tags",
                JSONArray().apply {
                    userTags.forEach { tag ->
                        put(JSONObject().put("id", tag.id).put("name", tag.name))
                    }
                }
            )
            .put(
                "client",
                JSONObject()
                    .put("platform", platform.storageValue)
                    .put("app_version", appVersion)
            )
            .toString()

        return FrozenCapturePayload(
            clientId = clientId,
            content = content,
            createdAtMillis = createdAtMillis,
            clientPlatform = platform,
            payloadJson = payloadJson
        )
    }
}
