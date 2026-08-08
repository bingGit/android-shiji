package com.bing.androidvoiceflow.capture.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class CaptureApiClientTest {
    @Test
    fun `http client builds service paths and uses basic auth`() = runBlocking {
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/capture/api/captures") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val response = """{"status":"accepted","client_id":"cap_http123","duplicate":false}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(202, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/capture/api/health") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }

        try {
            val payload = """{"client_id":"cap_http123","content":"原始内容"}"""
            val api = OkHttpCaptureApi(
                configProvider = object : CaptureServiceConfigProvider {
                    override fun load() = CaptureServiceConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}/capture",
                        username = "test-user",
                        password = "test-pass"
                    )
                }
            )

            val result = api.submit("cap_http123", payload)

            assertTrue(result is CaptureApiResult.Accepted)
            assertEquals(Credentials.basic("test-user", "test-pass"), authorization.get())
            assertEquals(payload, requestBody.get())
            assertEquals(CaptureHealthResult.Healthy, api.checkHealth())
            assertEquals(Credentials.basic("test-user", "test-pass"), authorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `accepted response requires matching protocol fields`() {
        val duplicate = classifyCaptureApiResponse(
            statusCode = 202,
            responseBody = """{"status":"accepted","client_id":"cap_expected","duplicate":true}""",
            expectedClientId = "cap_expected"
        )
        val mismatch = classifyCaptureApiResponse(
            statusCode = 202,
            responseBody = """{"status":"accepted","client_id":"cap_other01"}""",
            expectedClientId = "cap_expected"
        )
        val invalidJson = classifyCaptureApiResponse(202, "not-json", "cap_expected")
        val unexpectedSuccess = classifyCaptureApiResponse(200, "{}", "cap_expected")

        assertTrue(duplicate is CaptureApiResult.Accepted)
        assertTrue((duplicate as CaptureApiResult.Accepted).duplicate)
        assertTrue(mismatch is CaptureApiResult.PermanentFailure)
        assertTrue(invalidJson is CaptureApiResult.PermanentFailure)
        assertTrue(unexpectedSuccess is CaptureApiResult.PermanentFailure)
    }

    @Test
    fun `response classes separate auth retry and permanent failures`() {
        assertTrue(
            classifyCaptureApiResponse(401, "", "cap_expected") is
                CaptureApiResult.AuthRequired
        )
        val rateLimited = classifyCaptureApiResponse(
            statusCode = 429,
            responseBody = "",
            expectedClientId = "cap_expected",
            retryAfterMillis = 90_000L
        )
        assertTrue(rateLimited is CaptureApiResult.Retryable)
        assertEquals(90_000L, (rateLimited as CaptureApiResult.Retryable).retryAfterMillis)
        assertTrue(
            classifyCaptureApiResponse(503, "", "cap_expected") is
                CaptureApiResult.Retryable
        )
        assertTrue(
            classifyCaptureApiResponse(422, "", "cap_expected") is
                CaptureApiResult.PermanentFailure
        )
    }

    @Test
    fun `retry delay is exponential and capped`() {
        assertEquals(30_000L, retryDelayMillis(1))
        assertEquals(60_000L, retryDelayMillis(2))
        assertEquals(6L * 60L * 60L * 1_000L, retryDelayMillis(100))
    }
}
