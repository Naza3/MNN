// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedInferenceQueueTest {
    private val prompt = LocalPrompt(listOf("user" to "hello"), 8, false)
    @Test fun boundedFifoRunsOneNativeCallAtATime() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val order = mutableListOf<String>(); val active = AtomicInteger(); val peak = AtomicInteger()
        val queue = BoundedInferenceQueue(LocalInferenceBackend { p, _ ->
            val count = active.incrementAndGet(); peak.set(maxOf(peak.get(), count))
            synchronized(order) { order.add(p.messages[0].second) }
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            active.decrementAndGet(); InferenceResult("answer", 1, 1)
        })
        try {
            val first = queue.submit(prompt)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = queue.submit(prompt.copy(messages = listOf("user" to "second")))
            assertEquals(1 to 1, queue.status())
            assertThrows(QueueFullException::class.java) { queue.submit(prompt) }
            repeat(10) { assertEquals(1 to 1, queue.status()) } // observing never dequeues/reorders
            release.countDown()
            withTimeout(5000) { first.result.await(); second.result.await() }
            assertEquals(listOf("hello", "second"), order)
            assertEquals(1, peak.get())
        } finally { release.countDown(); queue.shutdownAndJoin() }
    }
    @Test fun queuedCancellationNeverInvokesNativeAndActiveCancelStopsCooperatively() = runBlocking {
        val entered = CountDownLatch(1); val calls = AtomicInteger()
        val queue = BoundedInferenceQueue(LocalInferenceBackend { _, token ->
            calls.incrementAndGet(); entered.countDown()
            while (!token(null)) Thread.sleep(2)
            InferenceResult("")
        })
        val first = queue.submit(prompt)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = queue.submit(prompt)
        queue.cancel(second)
        assertEquals(1 to 0, queue.status())
        queue.cancel(first)
        withTimeout(5000) { queue.shutdownAndJoin() }
        assertEquals(1, calls.get())
        assertTrue(first.result.isCancelled); assertTrue(second.result.isCancelled)
    }
    @Test fun preCancelledTicketAlwaysCompletesRatherThanHanging() = runBlocking {
        val queue = BoundedInferenceQueue(LocalInferenceBackend { _, _ -> error("must not run") })
        try {
            val cancellation = RequestCancellation().apply { cancel() }
            val ticket = queue.submit(prompt, cancellation)
            withTimeout(5000) { ticket.result.join() }
            assertTrue(ticket.result.isCancelled)
        } finally { queue.shutdownAndJoin() }
    }
    @Test fun alreadyDisconnectedWaiterDoesNotConsumeSlotDuringPrefill() = runBlocking {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val queue = BoundedInferenceQueue(LocalInferenceBackend { _, _ ->
            entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); InferenceResult("")
        })
        try {
            queue.submit(prompt)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancelled = queue.submit(prompt, RequestCancellation().apply { cancel() })
            assertTrue(cancelled.result.isCancelled)
            assertEquals(1 to 0, queue.status())
        } finally { released.countDown(); queue.shutdownAndJoin() }
    }
    @Test fun stoppingDuringUninterruptiblePrefillWaitsBeforeReleasingWorker() = runBlocking {
        val entered = CountDownLatch(1); val prefillReturns = CountDownLatch(1)
        val queue = BoundedInferenceQueue(LocalInferenceBackend { _, token ->
            entered.countDown(); check(prefillReturns.await(5, TimeUnit.SECONDS))
            assertTrue(token("late")); InferenceResult("")
        })
        queue.submit(prompt)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val shutdown = async { queue.shutdownAndJoin() }
        delay(50)
        assertFalse(shutdown.isCompleted)
        assertThrows(QueueClosedException::class.java) { queue.submit(prompt) }
        prefillReturns.countDown()
        withTimeout(5000) { shutdown.await() }
    }
    @Test fun boundedStreamOverflowCancelsInsteadOfDroppingAndSucceeding() = runBlocking {
        val stopped = CountDownLatch(1)
        val queue = BoundedInferenceQueue(LocalInferenceBackend { _, token ->
            repeat(1000) { if (token("x")) { stopped.countDown(); return@LocalInferenceBackend InferenceResult("") } }
            error("Unbounded stream")
        })
        try {
            val ticket = queue.submit(prompt.copy(stream = true))
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { ticket.result.join() }
            assertTrue(ticket.result.isCancelled)
        } finally { queue.shutdownAndJoin() }
    }
}
