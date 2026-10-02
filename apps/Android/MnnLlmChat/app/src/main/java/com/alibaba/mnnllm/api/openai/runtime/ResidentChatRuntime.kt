// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A single resident model with revocable UI attachments. Native work never runs under [attachments]. */
class ResidentChatRuntime<T : Any>(
    private val available: () -> Boolean,
    private val cancelGeneration: (T) -> Unit,
    private val detachUi: (T) -> Unit,
    private val release: (T) -> Unit,
    private val beforeAcquireLock: () -> Unit = {},
    private val retainOnAbandon: () -> Boolean = { true }
) {
    data class Attachment<T>(val session: T, val epoch: Long)
    private data class Resident<T>(val key: String, val session: T)
    private val operations = ReentrantLock()
    private val attachments = Any()
    private var epoch = 0L
    private var requestSequence = 0L
    private var attached = false
    @Volatile private var resident: Resident<T>? = null
    @Volatile private var cleanupFailed = false

    fun current(): T? = resident?.session
    fun isCurrent(session: T?, token: Long?): Boolean = synchronized(attachments) {
        available() && attached && resident?.session === session && token != null && token == epoch
    }

    /** Signals cancellation without waiting for load/prefill to return. The generation retains its slot. */
    private fun revoke(expected: T? = null, token: Long? = null): Pair<T, Long>? = synchronized(attachments) {
        if (!available()) return null
        val old = resident?.session ?: return null
        if (expected != null && (old !== expected || !attached || token != epoch)) return null
        attached = false
        epoch++
        requestSequence++
        cancelGeneration(old)
        old to epoch
    }

    fun acquire(key: String, create: () -> T, isWanted: () -> Boolean = { true }, forceReload: Boolean = false, prepare: (T, Boolean) -> Unit): Attachment<T> {
        val request = synchronized(attachments) {
            check(available()) { "Runtime is reserved by the local API" }
            check(isWanted()) { "Chat was closed before model admission" }
            revoke()
            ++requestSequence
        }
        beforeAcquireLock()
        return operations.withLock {
            val abandoned = synchronized(attachments) {
                check(available() && requestSequence == request) { "A newer model request replaced this chat" }
                check(!cleanupFailed) { "Native cleanup was not confirmed; restart the app" }
                !isWanted()
            }
            if (abandoned) {
                // This latest request revoked the old UI; its late destroy can no longer clean up.
                // We own operations here, so a newer request/API cannot publish a replacement yet.
                resident?.session?.let { if (retainOnAbandon()) detachUiLocked(it) else releaseLocked(it) }
                error("Chat was closed before model admission")
            }
            val previous = resident
            val reused = !forceReload && previous?.key == key
            if (previous != null && !reused) releaseLocked(previous.session)
            val session = if (reused) previous!!.session else create().also {
                resident = Resident(key, it)
            }
            val preparationEpoch = synchronized(attachments) { attached = false; ++epoch }
            try {
                check(available() && synchronized(attachments) { requestSequence == request }) { "Chat loading was cancelled" }
                prepare(session, reused)
            } catch (error: Throwable) {
                try { releaseLocked(session) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
            synchronized(attachments) {
                // An Unload/API takeover during a non-interruptible load owns the pending cleanup.
                check(available() && epoch == preparationEpoch && requestSequence == request) { "Chat attachment was cancelled while loading" }
                attached = true
                Attachment(session, ++epoch)
            }
        }
    }

    /** The operation and its ownership check are serialized with rebind/switch/unload. */
    fun <R> withAttachment(session: T, token: Long?, action: () -> R): R = operations.withLock {
        check(isCurrent(session, token)) { "This chat is no longer attached to the model" }
        action()
    }

    fun tryWithAttachment(session: T, token: Long?, action: () -> Unit): Boolean {
        if (!isCurrent(session, token) || !operations.tryLock()) return false
        return try {
            if (!isCurrent(session, token)) false else { action(); true }
        } finally { operations.unlock() }
    }

    /** Call on an IO worker. A newer attachment makes a delayed destroy harmless. */
    fun detach(session: T, token: Long?, retain: Boolean) {
        val revoked = revoke(session, token) ?: return
        operations.withLock {
            val stillDetached = synchronized(attachments) {
                resident?.session === session && !attached && epoch == revoked.second && available()
            }
            if (!stillDetached) return
            if (retain) detachUiLocked(session) else releaseLocked(session)
        }
    }

    fun unload(): Boolean {
        check(available()) { "Stop the local API before unloading its model" }
        check(!cleanupFailed) { "Native cleanup was not confirmed; restart the app" }
        val revoked = synchronized(attachments) {
            requestSequence++ // Also cancels an admitted acquire that has not allocated yet.
            revoke()
        } ?: return false
        operations.withLock {
            val unchanged = synchronized(attachments) {
                resident?.session === revoked.first && epoch == revoked.second && !attached && available()
            }
            if (!unchanged) return false
            releaseLocked(revoked.first)
            return true
        }
    }

    /** Used only after the owner gate has confirmed external/API cleanup of the native lease. */
    fun forgetReleased() = operations.withLock {
        synchronized(attachments) { attached = false; epoch++; requestSequence++; resident = null }
    }

    private fun detachUiLocked(session: T) {
        try { detachUi(session) } catch (error: Throwable) { cleanupFailed = true; throw error }
    }

    private fun releaseLocked(session: T) {
        try { release(session) } catch (error: Throwable) {
            cleanupFailed = true // Keep the resident reference; never allocate another instance.
            throw error
        }
        synchronized(attachments) {
            if (resident?.session === session) { resident = null; attached = false; epoch++ }
        }
    }
}
