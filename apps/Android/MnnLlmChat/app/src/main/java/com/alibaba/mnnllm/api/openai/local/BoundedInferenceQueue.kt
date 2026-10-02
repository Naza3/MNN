// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class RequestCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

data class LocalPrompt(val messages: List<Pair<String, String>>, val maxTokens: Int, val stream: Boolean)
data class InferenceResult(val text: String, val promptTokens: Int = 0, val completionTokens: Int = 0)
fun interface LocalInferenceBackend {
    /** Synchronous native call. Must return only when native inference has actually ended. */
    fun generate(prompt: LocalPrompt, onToken: (String?) -> Boolean): InferenceResult
}
class QueueFullException : IllegalStateException("One request is active and one is already waiting")
class QueueClosedException : IllegalStateException("Local API is not ready")

/** Exactly one native worker and at most two admitted requests, in FIFO order. */
class BoundedInferenceQueue(private val backend: LocalInferenceBackend,
                            dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    class Ticket internal constructor(val prompt: LocalPrompt, val cancellation: RequestCancellation) {
        val tokens = Channel<String>(64)
        val result = CompletableDeferred<InferenceResult>()
        internal fun cancel() {
            cancellation.cancel()
            tokens.cancel()
            result.cancel()
        }
    }
    private val lock = Any()
    private val pending = ArrayDeque<Ticket>()
    private var active: Ticket? = null
    private var closed = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = scope.launch {
        try {
            for (ignored in wake) {
                while (true) {
                    val ticket = synchronized(lock) {
                        if (pending.isEmpty()) null else pending.removeFirst().also { active = it }
                    } ?: break
                    try {
                        if (!ticket.cancellation.isCancelled()) {
                            val output = backend.generate(ticket.prompt) { token ->
                                if (ticket.cancellation.isCancelled()) true
                                else if (token != null && ticket.prompt.stream && !ticket.tokens.trySend(token).isSuccess) {
                                    // Bounded backpressure: cancel a slow/disconnected reader, never drop tokens.
                                    ticket.cancel()
                                    true
                                } else false
                            }
                            if (!ticket.cancellation.isCancelled()) ticket.result.complete(output)
                            else ticket.cancel()
                        } else ticket.cancel()
                    } catch (e: Throwable) {
                        ticket.result.completeExceptionally(e)
                    } finally {
                        ticket.tokens.close()
                        synchronized(lock) { if (active === ticket) active = null }
                    }
                }
                if (synchronized(lock) { closed && pending.isEmpty() && active == null }) break
            }
        } finally { wake.close() }
    }
    fun submit(prompt: LocalPrompt, cancellation: RequestCancellation = RequestCancellation()): Ticket = synchronized(lock) {
        if (closed) throw QueueClosedException()
        if (cancellation.isCancelled()) return@synchronized Ticket(prompt, cancellation).also { it.cancel() }
        if ((if (active == null) 0 else 1) + pending.size >= 2) throw QueueFullException()
        Ticket(prompt, cancellation).also { pending.addLast(it); wake.trySend(Unit) }
    }
    fun cancel(ticket: Ticket) {
        synchronized(lock) { pending.remove(ticket) }
        ticket.cancel()
    }
    fun status(): Pair<Int, Int> = synchronized(lock) { (if (active == null) 0 else 1) to pending.size }
    fun stopAccepting() {
        synchronized(lock) {
            closed = true
            active?.cancel()
            pending.forEach { it.cancel() }
            pending.clear()
        }
        wake.trySend(Unit)
    }
    suspend fun shutdownAndJoin() {
        stopAccepting()
        // Never cancel the worker before native load/prefill/decode returns.
        withContext(NonCancellable) { worker.join() }
        scope.cancel()
    }
}
