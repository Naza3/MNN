// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnerGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface LocalApiTransport {
    fun start()
    suspend fun stop()
}

/** Testable lifecycle used by the Android service; no UI or Android object owns the native worker. */
class LocalApiLifecycle(
    private val gate: RuntimeOwnerGate,
    private val load: (Long) -> LocalInferenceBackend,
    private val transportFactory: (BoundedInferenceQueue) -> LocalApiTransport,
    private val onState: (RuntimeOwnerGate.State) -> Unit = {}
) {
    private val operation = Mutex()
    @Volatile private var queue: BoundedInferenceQueue? = null
    private var transport: LocalApiTransport? = null
    @Volatile var epoch: Long? = null
        private set
    @Volatile var bootstrapCount = 0
        private set
    fun reserve(): Boolean {
        epoch = gate.beginApi() ?: return false
        notifyState()
        return true
    }
    suspend fun start(): Boolean = operation.withLock {
        val ticket = epoch ?: return@withLock false
        if (gate.state != RuntimeOwnerGate.State.STARTING_API) return@withLock false
        try {
            gate.drainResident(ticket)
            val backend = load(ticket)
            if (gate.state != RuntimeOwnerGate.State.STARTING_API) return@withLock false
            val worker = BoundedInferenceQueue(backend)
            queue = worker
            val connection = transportFactory(worker)
            transport = connection // retain even on partially failed bind
            connection.start()
            check(gate.markReady(ticket))
            bootstrapCount++
            notifyState()
            true
        } catch (e: Throwable) { false }
    }
    fun requestStop() {
        epoch?.let { gate.requestStop(it) }
        queue?.stopAccepting()
        notifyState()
    }
    suspend fun cleanup(): Boolean = operation.withLock {
        val ticket = epoch ?: return@withLock true
        try {
            requestStop()
            transport?.stop()
            transport = null
            queue?.shutdownAndJoin()
            queue = null
            gate.drainResident(ticket)
            gate.finishStop(ticket)
            epoch = null
            notifyState()
            true
        } catch (e: Throwable) {
            gate.failCleanup(ticket)
            notifyState()
            false
        }
    }
    fun isReady(): Boolean = gate.state == RuntimeOwnerGate.State.READY_API
    private fun notifyState() { runCatching { onState(gate.state) } }
}
