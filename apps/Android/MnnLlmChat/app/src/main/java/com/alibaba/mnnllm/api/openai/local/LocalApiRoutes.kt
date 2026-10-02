// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import com.alibaba.mnnllm.api.openai.service.ApiServerConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Registers only the bounded, text-only local API. Legacy media/messages/test-page routes are not exposed. */
fun Application.localApiModule(queue: BoundedInferenceQueue, modelId: String,
                               keyProvider: () -> String, ready: () -> Boolean = { true },
                               disconnect: (ApplicationCall, () -> Unit) -> AutoCloseable = ::watchNettyDisconnect) {
    captureNettyChannel()
    install(SSE)
    routing {
        intercept(ApplicationCallPipeline.Call) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val auth = call.request.headers[HttpHeaders.Authorization]
            val token = auth?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
            if (!ApiServerConfig.matches(token, keyProvider())) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                call.apiError(HttpStatusCode.Unauthorized, "A valid Bearer API key is required")
                finish()
            }
        }
        get("/v1/models") {
            call.respondJson(buildJsonObject {
                put("object", "list")
                putJsonArray("data") { add(buildJsonObject { put("id", modelId); put("object", "model"); put("owned_by", "local") }) }
            })
        }
        get("/v1/queue/status") {
            val (active, queued) = queue.status()
            call.respondJson(buildJsonObject { put("active", active); put("queued", queued); put("capacity", 2); put("ready", ready()) })
        }
        post("/v1/chat/completions") {
            if (!ready()) { call.apiError(HttpStatusCode.ServiceUnavailable, "Local API is not ready"); return@post }
            val prompt = try { parseLocalPrompt(call.readLimitedBody(), modelId) }
            catch (e: Exception) { call.apiError(HttpStatusCode.BadRequest, "Invalid request: ${e.message?.take(140)}"); return@post }
            val cancellation = RequestCancellation()
            var ticket: BoundedInferenceQueue.Ticket? = null
            val published = AtomicReference<BoundedInferenceQueue.Ticket?>()
            val connection = disconnect(call) { cancellation.cancel(); published.get()?.let(queue::cancel) }
            try {
                ticket = queue.submit(prompt, cancellation)
                val current = ticket!!
                published.set(current)
                if (cancellation.isCancelled()) queue.cancel(current)
                val id = "chatcmpl-${UUID.randomUUID()}"
                val created = System.currentTimeMillis() / 1000
                if (prompt.stream) {
                    call.respond(SSEServerContent(call) {
                        try {
                            send(ServerSentEvent(data = chunk(id, created, modelId, "", null, true).toString()))
                            for (token in current.tokens) {
                                send(ServerSentEvent(data = chunk(id, created, modelId, token, null).toString()))
                            }
                            val result = current.result.await()
                            val finish = if (result.completionTokens >= prompt.maxTokens) "length" else "stop"
                            send(ServerSentEvent(data = chunk(id, created, modelId, "", finish).toString()))
                            send(ServerSentEvent(data = "[DONE]"))
                        } finally {
                            // A failed socket write must close the bounded producer channel and request JNI stop.
                            queue.cancel(current)
                            connection.close()
                        }
                    })
                } else {
                    val result = current.result.await()
                    call.respondJson(buildJsonObject {
                        put("id", id); put("object", "chat.completion"); put("created", created); put("model", modelId)
                        putJsonArray("choices") { add(buildJsonObject {
                            put("index", 0)
                            putJsonObject("message") { put("role", "assistant"); put("content", result.text) }
                            put("finish_reason", if (result.completionTokens >= prompt.maxTokens) "length" else "stop")
                        }) }
                        putJsonObject("usage") {
                            put("prompt_tokens", result.promptTokens); put("completion_tokens", result.completionTokens)
                            put("total_tokens", result.promptTokens + result.completionTokens)
                        }
                    })
                }
            } catch (e: QueueFullException) {
                call.response.headers.append(HttpHeaders.RetryAfter, "1")
                call.apiError(HttpStatusCode.TooManyRequests, "One request is active and one is waiting")
            } catch (e: QueueClosedException) {
                call.apiError(HttpStatusCode.ServiceUnavailable, "Local API is stopping")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!call.response.isCommitted) call.apiError(HttpStatusCode.InternalServerError, "Local inference failed")
            } finally {
                ticket?.let(queue::cancel)
                connection.close()
            }
        }
    }
}

internal fun parseLocalPrompt(body: String, modelId: String): LocalPrompt {
    val request = Json.parseToJsonElement(body).jsonObject
    val model = request["model"]?.jsonPrimitive?.contentOrNull
    require(model == null || model == modelId || model == "mnn-local") { "Requested model is not loaded" }
    val unsupported = listOf("tools", "tool_choice", "functions", "function_call", "response_format", "temperature", "top_p", "stop", "logprobs", "frequency_penalty", "presence_penalty", "n")
    require(unsupported.none { request[it] != null && request[it] != JsonNull }) { "Unsupported generation option" }
    val tokens = request["max_tokens"]?.jsonPrimitive?.int ?: 512
    require(tokens in 1..2048) { "max_tokens must be 1..2048" }
    val messages = request["messages"]?.jsonArray ?: error("messages are required")
    require(messages.size in 1..64) { "Provide 1..64 messages" }
    val history = messages.map { element ->
        val message = element.jsonObject
        val role = message["role"]?.jsonPrimitive?.content
        require(role in listOf("system", "user", "assistant")) { "Only system, user and assistant roles are supported" }
        val content = message["content"] as? JsonPrimitive
        require(content != null && content.isString) { "Only text content is supported; media, paths and URLs are not fetched" }
        require(!Regex("<\\s*/?\\s*(img|image|audio|video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(content.content)) { "Media markup is not supported" }
        role!! to content.content
    }
    require(!Regex("<\\s*/?\\s*(img|image|audio|video)\\b", RegexOption.IGNORE_CASE).containsMatchIn(history.joinToString("") { it.second })) { "Media markup is not supported" }
    require(history.any { it.first == "user" }) { "A user message is required" }
    require(history.sumOf { it.second.length } <= 32768) { "Message text exceeds 32768 characters" }
    return LocalPrompt(history, tokens, request["stream"]?.jsonPrimitive?.boolean ?: false)
}

private suspend fun ApplicationCall.readLimitedBody(): String {
    val input = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val n = input.readAvailable(buffer, 0, buffer.size)
        if (n == -1) break
        require(output.size() + n <= 65536) { "Request exceeds 64 KiB" }
        output.write(buffer, 0, n)
    }
    return output.toString("UTF-8")
}
private fun chunk(id: String, created: Long, model: String, text: String, finish: String?, role: Boolean = false) = buildJsonObject {
    put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
    putJsonArray("choices") { add(buildJsonObject {
        put("index", 0)
        putJsonObject("delta") { if (role) put("role", "assistant"); if (text.isNotEmpty()) put("content", text) }
        put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
    }) }
}
private suspend fun ApplicationCall.respondJson(value: JsonElement) = respondText(value.toString(), ContentType.Application.Json)
private suspend fun ApplicationCall.apiError(status: HttpStatusCode, message: String) = respondText(
    buildJsonObject { putJsonObject("error") { put("message", message); put("type", "local_api_error") } }.toString(),
    ContentType.Application.Json, status)
