package com.bing.androidvoiceflow.capture.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class CapturePayloadFactoryTest {
    @Test
    fun `payload contains stable client id content client metadata and offset time`() {
        val factory = CapturePayloadFactory(
            appVersion = "0.1.0",
            zoneId = ZoneId.of("Asia/Shanghai"),
            clientIdGenerator = { "cap_12345678" }
        )

        val frozen = factory.create(
            content = "正文内容",
            createdAtMillis = 0L,
            platform = CaptureClientPlatform.AndroidReadingSession
        )
        val payload = JSONObject(frozen.payloadJson)

        assertEquals("cap_12345678", frozen.clientId)
        assertEquals("cap_12345678", payload.getString("client_id"))
        assertEquals("正文内容", payload.getString("content"))
        assertEquals("1970-01-01T08:00:00+08:00", payload.getString("created_at"))
        assertEquals(
            "android-reading-session",
            payload.getJSONObject("client").getString("platform")
        )
        assertEquals("0.1.0", payload.getJSONObject("client").getString("app_version"))
        assertEquals(0, payload.getJSONArray("user_tags").length())
    }

    @Test
    fun `payload includes user tag snapshots in the same request`() {
        val payload = CapturePayloadFactory(
            appVersion = "0.1.0",
            zoneId = ZoneId.of("Asia/Shanghai"),
            clientIdGenerator = { "cap_12345678" }
        ).create(
            content = "正文内容",
            createdAtMillis = 0L,
            platform = CaptureClientPlatform.AndroidShare,
            userTags = listOf(
                CaptureUserTag("tag_todo", "待办"),
                CaptureUserTag("tag_work", "工作")
            )
        )

        val tags = JSONObject(payload.payloadJson).getJSONArray("user_tags")
        assertEquals(2, tags.length())
        assertEquals("tag_todo", tags.getJSONObject(0).getString("id"))
        assertEquals("待办", tags.getJSONObject(0).getString("name"))
        assertEquals("tag_work", tags.getJSONObject(1).getString("id"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload rejects invalid client id`() {
        CapturePayloadFactory(
            appVersion = "0.1.0",
            clientIdGenerator = { "invalid" }
        ).create(
            content = "正文",
            createdAtMillis = 0L,
            platform = CaptureClientPlatform.AndroidShare
        )
    }
}
