// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local generation ownership. No Activity, Android service, or mutable UI objects live here. */
class ChatGenerationCoordinator<S : Any>(
    private val scope: CoroutineScope,
    private val isCurrent: (S) -> Boolean,
    private val execute: (Job<S>, (Output) -> Unit) -> Map<String, Any>,
    private val saveUser: (Job<S>) -> Unit,
    private val saveAssistant: (Job<S>, Snapshot) -> Unit,
    private val detach: (S) -> Unit,
    private val sameSession: (S, S) -> Boolean = { a, b -> a === b },
    private val onAttachment: (S) -> Unit = {}
) {
    data class Request(val conversationId: String, val modelId: String, val modelName: String,
        val configPath: String?, val text: String, val time: String?)
    data class Output(val raw: String = "", val display: String = "", val thinking: String = "", val thinkTime: Long = -1)
    enum class Phase { STARTING, GENERATING, STOPPING, COMPLETED, STOPPED, FAILED;
        val active get() = this == STARTING || this == GENERATING || this == STOPPING
    }
    data class Snapshot(val phase: Phase = Phase.STARTING, val output: Output = Output(),
        val result: Map<String, Any> = emptyMap(), val saved: Boolean = false)
    class Job<S : Any> internal constructor(val request: Request, val session: S) {
        val id: String = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean(false)
        internal val mutableState = MutableStateFlow(Snapshot())
        val state: StateFlow<Snapshot> = mutableState
        val completion = CompletableDeferred<Map<String, Any>>()
        internal var observer: Long? = null
        internal var started = false
        internal var detaching = false
    }
    data class Attachment<S : Any>(val job: Job<S>, val observer: Long)
    private val lock = Any()
    private val transitions = ReentrantLock()
    private var transitionCount = 0
    private var sequence = 0L
    private var current: Job<S>? = null
    private val mutableJobs = MutableStateFlow<Job<S>?>(null)
    val jobs: StateFlow<Job<S>?> = mutableJobs

    fun admit(request: Request, session: S): Attachment<S> = synchronized(lock) {
        check(transitionCount == 0) { "The model is changing" }
        check(current?.state?.value?.phase?.active != true) { "A chat response is still finishing" }
        check(current?.detaching != true || current?.session?.let { !sameSession(it, session) } == true) { "The old chat is detaching" }
        check(isCurrent(session)) { "This chat no longer owns the model" }
        val job = Job(request, session)
        current = job
        mutableJobs.value = job
        Attachment(job, ++sequence).also { job.observer = it.observer; onAttachment(job.session) }
    }

    /** Same model + explicit conversation only; opening another history must never steal this job. */
    fun attach(modelId: String, conversationId: String?): Attachment<S>? = synchronized(lock) {
        val job = current ?: return null
        if (transitionCount != 0 || job.detaching || !isCurrent(job.session) || job.request.modelId != modelId ||
            (conversationId != null && job.request.conversationId != conversationId)) return null
        // A model-list entry without a conversation resumes an in-flight response, not an idle old chat.
        if (conversationId == null && !job.state.value.phase.active) return null
        Attachment(job, ++sequence).also { job.observer = it.observer; onAttachment(job.session) }
    }
    fun owns(attachment: Attachment<S>): Boolean = synchronized(lock) {
        current === attachment.job && attachment.job.observer == attachment.observer &&
            !attachment.job.detaching && isCurrent(attachment.job.session)
    }
    /** Give a finished native attachment back to foreground-only UI, without resetting/freeing it. */
    fun retire(attachment: Attachment<S>): Boolean = synchronized(lock) {
        if (!owns(attachment) || attachment.job.state.value.phase.active) return false
        current = null
        mutableJobs.value = null
        true
    }
    fun find(id: String?): Job<S>? = synchronized(lock) { current?.takeIf { it.id == id } }
    fun active(): Job<S>? = synchronized(lock) { current?.takeIf { it.state.value.phase.active } }

    /** Called only after startForeground succeeds. Duplicate service starts cannot run twice. */
    fun start(id: String): Unit = synchronized(lock) {
        val job = current?.takeIf { it.id == id && !it.started && it.state.value.phase.active } ?: return
        job.started = true
        scope.launch { run(job) }
    }

    /** Startup failures must not leave a slot, phantom reply or retained service behind. */
    fun failStartup(id: String): Unit = synchronized(lock) {
        val job = current?.takeIf { it.id == id && !it.started && it.state.value.phase.active } ?: return
        job.started = true
        scope.launch { finish(job, Snapshot(Phase.FAILED, result = mapOf("error" to true,
            "message" to "Unable to start background chat"))) }
    }

    fun stop(id: String, observer: Long? = null): Boolean = synchronized(lock) {
        val job = current?.takeIf { it.id == id && it.state.value.phase.active } ?: return false
        if (observer != null && job.observer != observer) return false
        job.cancelled.set(true)
        job.mutableState.value = job.state.value.copy(phase = Phase.STOPPING)
        if (!job.started) { job.started = true; scope.launch { run(job) } }
        true
    }

    /** Block admission through drain AND the actual runtime mutation; never hold [lock] in native. */
    fun <T> transition(isWanted: () -> Boolean = { true }, action: () -> T): T {
        synchronized(lock) { check(isWanted()) { "Chat was closed" }; transitionCount++ }
        try {
            return transitions.withLock {
                check(isWanted()) { "Chat was closed" }
                cancelAndDrain()
                check(isWanted()) { "Chat was closed" }
                action()
            }
        } finally { synchronized(lock) { transitionCount-- } }
    }

    /** IO only. API takeover, unload and model switch wait for persistence as well as native return. */
    fun cancelAndDrain() {
        val job = synchronized(lock) { current?.takeIf { it.state.value.phase.active } } ?: return
        stop(job.id)
        runBlocking { job.completion.await() }
    }

    fun detachObserver(attachment: Attachment<S>) {
        val release = synchronized(lock) {
            val job = attachment.job
            if (current !== job || job.observer != attachment.observer) return
            job.observer = null
            markDetached(job)
        }
        if (release) scope.launch { detachSafely(attachment.job) }
    }

    private fun run(job: Job<S>) {
        var userSaved = false
        var output = Output()
        var result: Map<String, Any>
        try {
            if (job.cancelled.get()) {
                finish(job, Snapshot(Phase.STOPPED))
                return
            }
            saveUser(job)
            userSaved = true
            synchronized(lock) {
                job.mutableState.value = job.state.value.copy(phase = if (job.cancelled.get()) Phase.STOPPING else Phase.GENERATING)
            }
            result = if (job.cancelled.get()) emptyMap() else execute(job) { next ->
                output = next
                synchronized(lock) { job.mutableState.value = job.state.value.copy(output = next) }
            }.toMap()
        } catch (_: Exception) {
            result = mapOf("error" to true, "message" to "Chat generation failed")
        }
        var final = Snapshot(if (result["error"] == true) Phase.FAILED else if (job.cancelled.get()) Phase.STOPPED else Phase.COMPLETED,
            output, result + ("response" to output.raw))
        if (userSaved) {
            try {
                saveAssistant(job, final)
                final = final.copy(saved = true)
            } catch (_: Exception) {
                final = final.copy(phase = Phase.FAILED, result = final.result + mapOf("error" to true,
                    "message" to "Could not save the chat response"))
            }
        }
        finish(job, final)
    }

    private fun finish(job: Job<S>, snapshot: Snapshot) {
        val release = synchronized(lock) {
            job.mutableState.value = snapshot // Publication follows the sole database write.
            markDetached(job)
        }
        if (release) detachSafely(job)
        job.completion.complete(snapshot.result)
    }
    private fun markDetached(job: Job<S>): Boolean {
        if (current !== job || job.observer != null || job.detaching || job.state.value.phase.active) return false
        job.detaching = true // Reattach cannot race with an already scheduled lease detach.
        return true
    }
    private fun detachSafely(job: Job<S>) {
        try { detach(job.session) } catch (_: Exception) { /* Runtime retains its failed-cleanup reservation. */ }
    }
}
