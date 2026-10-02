// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ResidentChatRuntimeTest {
    private class Session {
        var conversation = ""
        var history = emptyList<String>()
        var releases = 0
        var detached = 0
        @Volatile var cancelled = false
        var calls = 0
    }
    private class Fixture {
        var available = true
        var loads = 0
        var live = 0
        var failRelease = false
        val store = ResidentChatRuntime<Session>({ available }, { it.cancelled = true }, { it.detached++ }, {
            check(!failRelease) { "cleanup failed" }
            it.releases++; live--
        })
        fun open(key: String = "model/config1", conversation: String = "new", history: List<String> = emptyList()): ResidentChatRuntime.Attachment<Session> =
            store.acquire(key, { assertEquals(0, live); loads++; live++; Session() }) { session, _ ->
                session.cancelled = false; session.conversation = conversation; session.history = history
            }
    }
    @Test fun backRetainsWeightsAndReentryCanGenerateWithoutAnotherLoad() {
        val f = Fixture(); val a = f.open()
        f.store.detach(a.session, a.epoch, true)
        assertEquals(0, a.session.releases); assertEquals(1, a.session.detached)
        val b = f.open()
        assertSame(a.session, b.session); assertEquals(1, f.loads)
        f.store.withAttachment(b.session, b.epoch) { assertFalse(b.session.cancelled); b.session.calls++ }
        assertEquals(1, b.session.calls)
    }
    @Test fun staleDestroyCancelAndMutationsCannotTouchNewAttachment() {
        val f = Fixture(); val a = f.open(); val b = f.open()
        f.store.detach(a.session, a.epoch, false)
        assertFalse(b.session.cancelled); assertEquals(0, b.session.releases)
        for (mutation in listOf("reset", "audio", "settings")) {
            assertThrows(IllegalStateException::class.java) {
                f.store.withAttachment(a.session, a.epoch) { a.session.conversation = mutation }
            }
        }
        assertTrue(f.store.isCurrent(b.session, b.epoch)); assertEquals("new", b.session.conversation)
    }
    @Test fun explicitHistoryAndNewConversationReplacePreviousHistory() {
        val f = Fixture(); val a = f.open(conversation = "a", history = listOf("secret A"))
        val b = f.open(conversation = "b", history = listOf("only B"))
        assertSame(a.session, b.session); assertEquals(listOf("only B"), b.session.history)
        val c = f.open(); assertEquals(emptyList<String>(), c.session.history); assertEquals(1, f.loads)
    }
    @Test fun offPathAndExplicitUnloadReleaseExactlyOnce() {
        val f = Fixture(); val a = f.open()
        f.store.detach(a.session, a.epoch, false); f.store.detach(a.session, a.epoch, false)
        assertEquals(1, a.session.releases); assertNull(f.store.current())
        val b = f.open(); assertTrue(f.store.unload()); assertFalse(f.store.unload())
        assertEquals(1, b.session.releases); assertEquals(0, f.live)
    }
    @Test fun switchAndChangedConfigDrainBeforeAllocatingOneReplacement() {
        val f = Fixture(); val a = f.open(); val b = f.open("other/config1")
        assertEquals(1, a.session.releases); val c = f.open("other/config2")
        assertEquals(1, b.session.releases); assertEquals(1, f.live); assertEquals(3, f.loads)
        assertTrue(f.store.isCurrent(c.session, c.epoch))
    }
    @Test fun unloadCancelsButWaitsForGenerationToReturn() {
        val f = Fixture(); val a = f.open(); val started = CountDownLatch(1); val returned = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val generation = thread {
            f.store.withAttachment(a.session, a.epoch) { started.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS)) }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val unload = thread { f.store.unload(); finished.countDown() }
        while (!a.session.cancelled) Thread.yield()
        assertEquals(0, a.session.releases); assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
        returned.countDown(); generation.join(); unload.join()
        assertEquals(1, a.session.releases); assertNull(f.store.current())
    }
    @Test fun unloadDuringLoadDoesNotPublishAttachmentAndWaitsForSafeReturn() {
        val f = Fixture(); val started = CountDownLatch(1); val returned = CountDownLatch(1)
        val errors = AtomicInteger()
        val loading = thread {
            try { f.store.acquire("model", { Session().also { f.live++ } }) { _, _ ->
                started.countDown(); assertTrue(returned.await(5, TimeUnit.SECONDS))
            } } catch (_: IllegalStateException) { errors.incrementAndGet() }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS)); val session = f.store.current()!!
        val unloading = thread { f.store.unload() }
        while (!session.cancelled) Thread.yield()
        assertEquals(0, session.releases); returned.countDown(); loading.join(); unloading.join()
        assertEquals(1, errors.get()); assertEquals(1, session.releases); assertNull(f.store.current())
    }
    @Test fun failedCleanupKeepsResidentAndBlocksSecondAllocation() {
        val f = Fixture(); val a = f.open(); f.failRelease = true
        assertThrows(IllegalStateException::class.java) { f.store.unload() }
        assertThrows(IllegalStateException::class.java) { f.open("different") }
        assertSame(a.session, f.store.current()); assertEquals(1, f.loads)
    }
    @Test fun apiOwnershipRejectsChatActionsAndOldUiCannotReleaseApi() {
        val f = Fixture(); val a = f.open(); f.available = false
        f.store.detach(a.session, a.epoch, false)
        assertThrows(IllegalStateException::class.java) { f.open() }
        assertThrows(IllegalStateException::class.java) { f.store.unload() }
        assertEquals(0, a.session.releases)
        f.store.forgetReleased(); f.available = true
        assertFalse(f.store.isCurrent(a.session, a.epoch))
    }
    @Test fun olderAcquirePausedBeforeLockCannotReplaceNewerPublishedModel() {
        val paused = CountDownLatch(1); val resume = CountDownLatch(1)
        val errors = AtomicInteger(); val allocations = AtomicInteger()
        val oldCallerAlive = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = ResidentChatRuntime<Session>({ true }, { it.cancelled = true }, {}, { it.releases++ }, {
            if (Thread.currentThread().name == "old-acquire") { paused.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        })
        val old = thread(name = "old-acquire") {
            try { store.acquire("old", { allocations.incrementAndGet(); Session() }, isWanted = { oldCallerAlive.get() }, forceReload = true) { _, _ -> } }
            catch (_: IllegalStateException) { errors.incrementAndGet() }
        }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        val newer = store.acquire("new", { allocations.incrementAndGet(); Session() }) { _, _ -> }
        oldCallerAlive.set(false)
        resume.countDown(); old.join()
        assertEquals(0, newer.session.detached); assertEquals(0, newer.session.releases)
        assertEquals(1, allocations.get()); assertEquals(1, errors.get())
        assertTrue(store.isCurrent(newer.session, newer.epoch)); assertFalse(newer.session.cancelled)
    }

    @Test fun uiSettingMutationRejectsBusyRuntimeWithoutWaitingForNative() {
        val f = Fixture(); val a = f.open(); val started = CountDownLatch(1); val returned = CountDownLatch(1)
        val generation = thread { f.store.withAttachment(a.session, a.epoch) {
            started.countDown(); check(returned.await(5, TimeUnit.SECONDS))
        } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse(f.store.tryWithAttachment(a.session, a.epoch) { fail("Cannot mutate while generating") })
        returned.countDown(); generation.join()
        assertTrue(f.store.tryWithAttachment(a.session, a.epoch) { a.session.conversation = "updated" })
        assertEquals("updated", a.session.conversation)
    }

    @Test fun closedCallerBeforeAdmissionCannotCancelAnExistingChat() {
        val f = Fixture(); val active = f.open()
        assertThrows(IllegalStateException::class.java) {
            f.store.acquire("stale", { fail("Closed UI cannot allocate"); Session() }, isWanted = { false }, forceReload = true) { _, _ -> }
        }
        assertTrue(f.store.isCurrent(active.session, active.epoch)); assertFalse(active.session.cancelled)
        assertEquals(1, f.loads)
    }

    @Test fun admittedForceReloadReleasesSameKeyBeforeCreatingReplacement() {
        val f = Fixture(); val a = f.open()
        val next = f.store.acquire("model/config1", {
            assertEquals(0, f.live); f.live++; f.loads++; Session()
        }, forceReload = true) { _, _ -> }
        assertEquals(1, a.session.releases); assertNotSame(a.session, next.session)
        assertEquals(2, f.loads); assertEquals(1, f.live)
    }

    @Test fun oldQueuedGenerationCannotRunAfterResetOnSameNativeAttachment() {
        val f = Fixture(); val a = f.open(); val callbacks = ChatCallbackGuard()
        val old = callbacks.next("old-chat")
        val admitted = CountDownLatch(1); val enterNative = CountDownLatch(1)
        val skipped = AtomicInteger()
        val request = thread {
            admitted.countDown(); check(enterNative.await(5, TimeUnit.SECONDS))
            f.store.withAttachment(a.session, a.epoch) {
                if (!callbacks.isCurrent(old)) skipped.incrementAndGet() else a.session.calls++
            }
        }
        assertTrue(admitted.await(5, TimeUnit.SECONDS))
        val reset = callbacks.beginReset()!!
        f.store.withAttachment(a.session, a.epoch) { a.session.conversation = "new-chat" }
        callbacks.completeReset(reset) {}
        enterNative.countDown(); request.join()
        assertEquals(1, skipped.get()); assertEquals(0, a.session.calls)
        assertEquals("new-chat", a.session.conversation)
    }

    private fun abandonedWaiter(retain: Boolean) {
        val paused = CountDownLatch(1); val resume = CountDownLatch(1)
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val errors = AtomicInteger()
        val store = ResidentChatRuntime<Session>({ true }, { it.cancelled = true }, { it.detached++ },
            { it.releases++ }, beforeAcquireLock = {
                if (Thread.currentThread().name == "abandoned-waiter") { paused.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }, retainOnAbandon = { retain })
        val old = store.acquire("old", { Session() }) { _, _ -> }
        val waiter = thread(name = "abandoned-waiter") {
            try { store.acquire("next", { fail("Closed waiter must not allocate"); Session() }, isWanted = { alive.get() }) { _, _ -> } }
            catch (_: IllegalStateException) { errors.incrementAndGet() }
        }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        store.detach(old.session, old.epoch, retain) // Already revoked by the waiter; late Activity cannot clean it.
        alive.set(false); resume.countDown(); waiter.join()
        assertEquals(1, errors.get())
        if (retain) {
            assertEquals(1, old.session.detached); assertEquals(0, old.session.releases)
            assertSame(old.session, store.current())
        } else {
            assertEquals(1, old.session.releases); assertNull(store.current())
        }
    }
    @Test fun abandonedCurrentWaiterWithOffPreferenceReleasesOnce() = abandonedWaiter(false)
    @Test fun abandonedCurrentWaiterWithOnPreferenceClearsUiAndKeepsWeights() = abandonedWaiter(true)

}
