// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnerGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalApiLifecycleTest {
    private class Transport : LocalApiTransport {
        var starts = 0; var stops = 0
        override fun start() { starts++ }
        override suspend fun stop() { stops++ }
    }
    private fun fakeLoad(gate: RuntimeOwnerGate, epoch: Long): LocalInferenceBackend {
        lateinit var lease: RuntimeOwnerGate.Lease
        lease = gate.acquireApi(epoch) { gate.released(lease) }
        return LocalInferenceBackend { _, _ -> InferenceResult("fake") }
    }
    @Test fun duplicateStartAndRepeatedStopAllocateAndReleaseExactlyOnce() = runBlocking {
        val gate = RuntimeOwnerGate(); val transport = Transport(); var loads = 0
        val lifecycle = LocalApiLifecycle(gate, { epoch -> loads++; fakeLoad(gate, epoch) }, { transport })
        assertTrue(lifecycle.reserve()); assertTrue(lifecycle.start())
        assertFalse(lifecycle.reserve()); assertFalse(lifecycle.start())
        assertEquals(1, loads); assertEquals(1, transport.starts)
        lifecycle.requestStop(); lifecycle.requestStop()
        assertTrue(lifecycle.cleanup()); assertTrue(lifecycle.cleanup())
        assertEquals(1, transport.stops)
        assertFalse(gate.isApiReserved())
        assertNotNull(gate.acquireChat {})
    }
    @Test fun stopDuringNativeLoadWaitsAndDoesNotStartListener() = runBlocking {
        val gate = RuntimeOwnerGate(); val transport = Transport()
        val entered = CountDownLatch(1); val loaded = CountDownLatch(1)
        val lifecycle = LocalApiLifecycle(gate, { epoch ->
            val backend = fakeLoad(gate, epoch)
            entered.countDown(); check(loaded.await(5, TimeUnit.SECONDS)); backend
        }, { transport })
        assertTrue(lifecycle.reserve())
        val start = async(Dispatchers.IO) { lifecycle.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lifecycle.requestStop()
        val cleanup = async(Dispatchers.IO) { lifecycle.cleanup() }
        delay(50)
        assertFalse(cleanup.isCompleted)
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
        loaded.countDown()
        assertFalse(withTimeout(5000) { start.await() })
        assertTrue(withTimeout(5000) { cleanup.await() })
        assertEquals(0, transport.starts)
        assertNotNull(gate.acquireChat {})
    }
    @Test fun loadFailureIsCleanedBeforeChatIsUnlocked() = runBlocking {
        val gate = RuntimeOwnerGate(); var releases = 0
        val lifecycle = LocalApiLifecycle(gate, { epoch ->
            lateinit var lease: RuntimeOwnerGate.Lease
            lease = gate.acquireApi(epoch) { releases++; gate.released(lease) }
            throw IllegalStateException("fake load failure")
        }, { Transport() })
        assertTrue(lifecycle.reserve()); assertFalse(lifecycle.start())
        assertTrue(gate.isApiReserved())
        assertTrue(lifecycle.cleanup())
        assertEquals(1, releases)
        assertFalse(gate.isApiReserved())
    }
    @Test fun cleanupFailureDoesNotUnlockChatOrAllowRetryAllocation(): Unit = runBlocking {
        val gate = RuntimeOwnerGate()
        val lifecycle = LocalApiLifecycle(gate, { epoch ->
            gate.acquireApi(epoch) { throw IllegalStateException("fake release failure") }
            LocalInferenceBackend { _, _ -> InferenceResult("") }
        }, { Transport() })
        assertTrue(lifecycle.reserve()); assertTrue(lifecycle.start())
        assertFalse(lifecycle.cleanup())
        assertEquals(RuntimeOwnerGate.State.CLEANUP_FAILED, gate.state)
        assertFalse(lifecycle.reserve())
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
    }
    @Test fun oldUiDrainHappensBeforeApiLoadAndLateReleaseIsHarmless() = runBlocking {
        val gate = RuntimeOwnerGate(); var oldReleased = false
        lateinit var old: RuntimeOwnerGate.Lease
        old = gate.acquireChat { oldReleased = true; gate.released(old) }
        val lifecycle = LocalApiLifecycle(gate, { epoch ->
            assertTrue(oldReleased)
            fakeLoad(gate, epoch)
        }, { Transport() })
        assertTrue(lifecycle.reserve()); assertTrue(lifecycle.start())
        gate.released(old)
        assertTrue(lifecycle.isReady())
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
        assertTrue(lifecycle.cleanup())
        val newChat = gate.acquireChat {}
        gate.released(old)
        assertTrue(gate.allows(newChat))
    }
}
