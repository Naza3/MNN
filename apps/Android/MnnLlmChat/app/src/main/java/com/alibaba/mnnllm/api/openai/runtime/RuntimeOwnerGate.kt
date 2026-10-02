// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide exclusive native-runtime lease. Never hold this monitor while entering JNI. */
class RuntimeOwnerGate {
    enum class State { CHAT, STARTING_API, READY_API, STOPPING_API, CLEANUP_FAILED }
    enum class Owner { CHAT, API }
    class Lease internal constructor(val owner: Owner, val epoch: Long, internal val drain: () -> Unit) {
        val cancelled = AtomicBoolean(false)
    }
    private var epoch = 0L
    private var resident: Lease? = null
    @Volatile var state = State.CHAT
        private set
    @Synchronized fun acquireChat(drain: () -> Unit): Lease {
        check(state == State.CHAT && resident == null) { "Runtime is reserved; stop the local API or current task first" }
        return Lease(Owner.CHAT, ++epoch, drain).also { resident = it }
    }
    @Synchronized fun beginApi(): Long? {
        if (state != State.CHAT) return null
        state = State.STARTING_API
        resident?.cancelled?.set(true)
        return ++epoch
    }
    fun drainResident(apiEpoch: Long) {
        val previous = synchronized(this) {
            check(epoch == apiEpoch && state != State.CHAT)
            resident
        }
        previous?.cancelled?.set(true)
        previous?.drain?.invoke() // waits for native load/prefill/decode, on a worker thread
        synchronized(this) { check(resident == null) { "Native runtime cleanup was not confirmed" } }
    }
    @Synchronized fun acquireApi(apiEpoch: Long, drain: () -> Unit): Lease {
        check(epoch == apiEpoch && state == State.STARTING_API && resident == null)
        return Lease(Owner.API, apiEpoch, drain).also { resident = it }
    }
    @Synchronized fun markReady(apiEpoch: Long): Boolean {
        if (epoch != apiEpoch || state != State.STARTING_API || resident?.owner != Owner.API) return false
        state = State.READY_API
        return true
    }
    @Synchronized fun requestStop(apiEpoch: Long): Boolean {
        if (epoch != apiEpoch || state == State.CHAT) return false
        state = State.STOPPING_API
        resident?.cancelled?.set(true)
        return true
    }
    @Synchronized fun finishStop(apiEpoch: Long) {
        check(epoch == apiEpoch && resident == null) { "Cannot unlock chat before native cleanup" }
        state = State.CHAT
    }
    @Synchronized fun failCleanup(apiEpoch: Long) {
        if (epoch == apiEpoch) state = State.CLEANUP_FAILED
    }
    @Synchronized fun checkAccess(lease: Lease) {
        check(resident === lease && !lease.cancelled.get() &&
            ((lease.owner == Owner.CHAT && state == State.CHAT) ||
                (lease.owner == Owner.API && (state == State.STARTING_API || state == State.READY_API)))) {
            "This runtime lease is no longer active"
        }
    }
    @Synchronized fun released(lease: Lease) {
        if (resident === lease) resident = null // a late old Activity can never release a newer lease
    }
    @Synchronized fun allows(lease: Lease): Boolean = resident === lease && !lease.cancelled.get() &&
        ((lease.owner == Owner.CHAT && state == State.CHAT) ||
            (lease.owner == Owner.API && (state == State.STARTING_API || state == State.READY_API)))
    @Synchronized fun owns(lease: Lease): Boolean = resident === lease
    @Synchronized fun <T> whileApiStopped(action: () -> T): T {
        check(state == State.CHAT) { "Stop the API and wait for cleanup before changing configuration" }
        return action()
    }
    fun isApiReserved(): Boolean = state != State.CHAT
}

object RuntimeOwnership {
    val gate = RuntimeOwnerGate()
}
