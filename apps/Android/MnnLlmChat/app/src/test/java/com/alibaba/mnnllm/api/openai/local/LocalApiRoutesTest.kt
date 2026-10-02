// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import com.alibaba.mnnllm.api.openai.service.ApiServerConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalApiRoutesTest {
    private val key = "test-only-ephemeral-secret-not-persisted"
    private fun queue() = BoundedInferenceQueue(LocalInferenceBackend { _, callback -> callback("hello"); callback(null); InferenceResult("hello", 2, 1) })
    @Test fun allSensitiveRoutesRequireBearerAndRotationTakesEffect() = testApplication {
        val worker = queue(); var current = key
        application { localApiModule(worker, "test-model", { current }, disconnect = { _, _ -> AutoCloseable {} }) }
        try {
            for (path in listOf("/v1/models", "/v1/queue/status")) {
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                assertEquals(HttpStatusCode.Unauthorized, client.get(path) { bearerAuth("wrong") }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header("x-api-key", key) }.status)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/chat/completions") { setBody("{}") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/chat/completions") { bearerAuth("wrong"); setBody("{}") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/models") { bearerAuth(key) }.status)
            current = "rotated-test-only-key"
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models") { bearerAuth(key) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/models") { bearerAuth(current) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/") { bearerAuth(current) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/messages") { bearerAuth(current) }.status)
        } finally { worker.shutdownAndJoin() }
    }
    @Test fun textCompletionAndSseActuallyFinish() = testApplication {
        val worker = queue()
        application { localApiModule(worker, "test-model", { key }, disconnect = { _, _ -> AutoCloseable {} }) }
        try {
            val response = client.post("/v1/chat/completions") {
                bearerAuth(key); contentType(ContentType.Application.Json)
                setBody("""{"model":"test-model","messages":[{"role":"user","content":"hello"}],"max_tokens":8}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"prompt_tokens\":2"))
            val stream = client.post("/v1/chat/completions") {
                bearerAuth(key); contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"hello"}],"stream":true}""")
            }
            val body = stream.bodyAsText()
            assertTrue(body, body.contains("hello")); assertTrue(body, body.contains("[DONE]"))
        } finally { worker.shutdownAndJoin() }
    }
    @Test fun notReadyReturns503BeforeQueueAdmission() = testApplication {
        val worker = queue()
        application { localApiModule(worker, "test-model", { key }, { false }, { _, _ -> AutoCloseable {} }) }
        try {
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/v1/chat/completions") { bearerAuth(key); setBody("{}") }.status)
            assertEquals(0 to 0, worker.status())
        } finally { worker.shutdownAndJoin() }
    }
    @Test fun thirdAdmittedRequestReturns429AndRetryAfter() = testApplication {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = BoundedInferenceQueue(LocalInferenceBackend { _, _ ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); InferenceResult("fake")
        })
        application { localApiModule(worker, "test-model", { key }, disconnect = { _, _ -> AutoCloseable {} }) }
        try {
            val prompt = LocalPrompt(listOf("user" to "hello"), 8, false)
            worker.submit(prompt)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.submit(prompt)
            val response = client.post("/v1/chat/completions") {
                bearerAuth(key); contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"hello"}]}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        } finally { release.countDown(); worker.shutdownAndJoin() }
    }
    @Test fun validationRejectsUnboundedTokensMediaAndCrossMessageMediaTags() {
        val bodies = listOf(
            """{"messages":[{"role":"user","content":"hello"}],"max_tokens":0}""",
            """{"messages":[{"role":"user","content":"hello"}],"max_tokens":2049}""",
            """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"file:///private"}}]}]}""",
            """{"messages":[{"role":"user","content":"<img>/private</img>"}]}""",
            """{"messages":[{"role":"user","content":"<audio>https://example.test/private</audio>"}]}""",
            """{"messages":[{"role":"user","content":"<im"},{"role":"assistant","content":"g>/private</img>"}]}""",
            """{"messages":[{"role":"user","content":"hello"}],"tools":[{}]}"""
        )
        bodies.forEach { assertThrows(IllegalArgumentException::class.java) { parseLocalPrompt(it, "test-model") } }
        assertEquals(8, parseLocalPrompt("""{"messages":[{"role":"user","content":"hello"}],"max_tokens":8}""", "test-model").maxTokens)
    }
    @Test fun endpointIsPermanentlyLoopbackAndPortIsValidated() {
        for (host in listOf("0.0.0.0", "::", "192.168.1.1", "localhost")) {
            assertThrows(IllegalArgumentException::class.java) { ApiServerConfig.validateEndpoint(host, 8080) }
        }
        for (port in listOf(0, -1, 80, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { ApiServerConfig.validateEndpoint("127.0.0.1", port) }
        }
        ApiServerConfig.validateEndpoint("127.0.0.1", 8080)
    }
}
