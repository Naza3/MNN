// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeOwnerGateTest {
    @Test fun takeoverCancelsAndDrainsBeforeApiAllocation() {
        val gate = RuntimeOwnerGate()
        lateinit var chat: RuntimeOwnerGate.Lease
        var released = false
        chat = gate.acquireChat { released = true; gate.released(chat) }
        val epoch = gate.beginApi()!!
        assertTrue(chat.cancelled.get())
        assertNull(gate.beginApi()) // repeated start does not acquire another epoch
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
        assertThrows(IllegalStateException::class.java) { gate.acquireApi(epoch) {} }
        gate.drainResident(epoch)
        assertTrue(released)
        lateinit var api: RuntimeOwnerGate.Lease
        api = gate.acquireApi(epoch) { gate.released(api) }
        assertTrue(gate.markReady(epoch))
        gate.released(chat) // late Activity destruction cannot release the new API
        assertTrue(gate.owns(api))
        assertThrows(IllegalStateException::class.java) { gate.checkAccess(chat) }
        gate.requestStop(epoch)
        assertThrows(IllegalStateException::class.java) { gate.finishStop(epoch) }
        gate.drainResident(epoch)
        gate.finishStop(epoch)
        val next = gate.acquireChat {}
        gate.released(api)
        assertTrue(gate.owns(next))
    }
    @Test fun stopDuringStartingNeverLoadsApiAndKeepsChatBlockedUntilDrain() {
        val gate = RuntimeOwnerGate()
        lateinit var chat: RuntimeOwnerGate.Lease
        chat = gate.acquireChat { gate.released(chat) }
        val epoch = gate.beginApi()!!
        assertTrue(gate.requestStop(epoch))
        assertFalse(gate.markReady(epoch))
        assertThrows(IllegalStateException::class.java) { gate.acquireApi(epoch) {} }
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
        gate.drainResident(epoch)
        gate.finishStop(epoch)
        assertFalse(gate.isApiReserved())
    }
    @Test fun failedCleanupNeverPermitsASecondRuntime() {
        val gate = RuntimeOwnerGate()
        gate.acquireChat { throw IllegalStateException("fake native failure") }
        val epoch = gate.beginApi()!!
        assertThrows(IllegalStateException::class.java) { gate.drainResident(epoch) }
        gate.failCleanup(epoch)
        assertThrows(IllegalStateException::class.java) { gate.acquireChat {} }
        assertNull(gate.beginApi())
    }
    @Test fun staleStopCannotChangeNewerChatEpoch() {
        val gate = RuntimeOwnerGate()
        val epoch = gate.beginApi()!!
        gate.requestStop(epoch); gate.drainResident(epoch); gate.finishStop(epoch)
        val chat = gate.acquireChat {}
        assertFalse(gate.requestStop(epoch))
        assertTrue(gate.allows(chat))
    }
}
