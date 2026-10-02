// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ChatGenerationCoordinatorTest {
    private class Session(val native: Any = Any(), val epoch: Long = 1) {
        @Volatile var current = true
        var detached = 0
    }
    private class Fixture {
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val saved = mutableListOf<String>()
        val userSaves = AtomicInteger()
        val assistantSaves = AtomicInteger()
        val nativeCalls = AtomicInteger()
        val actionRevision = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var failSave = false
        var detachBarrier: CountDownLatch? = null
        val coordinator = ChatGenerationCoordinator<Session>(scope, { it.current }, { job, emit ->
            nativeCalls.incrementAndGet()
            emit(ChatGenerationCoordinator.Output("partial", "partial"))
            entered.countDown()
            check(returned.await(5, TimeUnit.SECONDS))
            if (!job.cancelled.get()) emit(ChatGenerationCoordinator.Output("complete", "complete"))
            mapOf("decode_len" to 2L)
        }, { userSaves.incrementAndGet() }, { job, snapshot ->
            if (failSave) error("commit failed")
            assistantSaves.incrementAndGet(); synchronized(saved) { saved += "${job.request.conversationId}:${snapshot.output.raw}" }
        }, { session ->
            detachBarrier?.let { check(it.await(5, TimeUnit.SECONDS)) }
            session.detached++; session.current = false
        }, sameSession = { a, b -> a.native === b.native && a.epoch == b.epoch },
            onAttachment = { actionRevision.incrementAndGet() })
        fun admit(session: Session = Session(), conversation: String = "chat-A") = coordinator.admit(
            ChatGenerationCoordinator.Request(conversation, "model", "Model", "/config.json", "hello", "time"), session)
        fun start(a: ChatGenerationCoordinator.Attachment<Session>) { coordinator.start(a.job.id); assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        fun finish(a: ChatGenerationCoordinator.Attachment<Session>) = runBlocking {
            returned.countDown(); withTimeout(5000) { a.job.completion.await() }
        }
    }
    @Test fun homeDestroyAndRecreateKeepSameNativeJobAndSaveExactlyOnce() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        f.coordinator.detachObserver(a)
        assertFalse(a.job.cancelled.get()); assertEquals(0, a.job.session.detached)
        val b = f.coordinator.attach("model", "chat-A")!!
        assertSame(a.job, b.job); assertSame(a.job.session, b.job.session)
        f.coordinator.detachObserver(a) // Delayed old Activity destruction.
        assertTrue(f.coordinator.owns(b)); assertFalse(f.coordinator.stop(a.job.id, a.observer))
        f.coordinator.start(b.job.id) // Duplicate start Intent.
        f.finish(b)
        assertEquals(1, f.nativeCalls.get()); assertEquals(1, f.userSaves.get()); assertEquals(1, f.assistantSaves.get())
        assertEquals(listOf("chat-A:complete"), f.saved)
        assertEquals(0, b.job.session.detached)
    }
    @Test fun detachedCompletionPersistsOriginalConversationThenReleasesOnce() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.coordinator.detachObserver(a)
        f.finish(a)
        assertEquals(listOf("chat-A:complete"), f.saved)
        assertEquals(1, a.job.session.detached)
        assertNull(f.coordinator.attach("model", "chat-A"))
        assertTrue(a.job.state.value.saved)
    }
    @Test fun completedReattachUsesTerminalSnapshotWithoutASecondSave() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.finish(a)
        val b = f.coordinator.attach("model", "chat-A")!!
        assertTrue(b.job.state.value.saved); assertEquals("complete", b.job.state.value.output.raw)
        assertEquals(1, f.assistantSaves.get()); assertFalse(f.coordinator.owns(a))
    }
    @Test fun oldStopAndOldObserverCannotAffectNextJobOnSameSession() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.finish(a)
        val b = f.admit(a.job.session)
        assertFalse(f.coordinator.stop(a.job.id)); f.coordinator.detachObserver(a)
        assertTrue(f.coordinator.owns(b)); assertFalse(b.job.cancelled.get())
        f.coordinator.start(b.job.id); f.finish(b)
        assertEquals(2, f.nativeCalls.get()); assertEquals(2, f.assistantSaves.get())
    }
    @Test fun stopRetainsSlotUntilNativeReturnsAndDoesNotPoisonNextRequest() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        assertTrue(f.coordinator.stop(a.job.id))
        assertEquals(ChatGenerationCoordinator.Phase.STOPPING, a.job.state.value.phase)
        assertThrows(IllegalStateException::class.java) { f.admit(a.job.session) }
        assertFalse(a.job.completion.isCompleted)
        f.finish(a)
        assertEquals(ChatGenerationCoordinator.Phase.STOPPED, a.job.state.value.phase)
        val b = f.admit(a.job.session); assertFalse(b.job.cancelled.get())
        f.coordinator.start(b.job.id); f.finish(b)
        assertEquals(ChatGenerationCoordinator.Phase.COMPLETED, b.job.state.value.phase)
    }
    @Test fun apiTakeoverDrainsNativeAndPersistenceBeforeReleaseAndBlocksNewAdmission() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        val completed = CountDownLatch(1)
        val t = thread {
            f.coordinator.transition {
                assertEquals(1, f.assistantSaves.get())
                a.job.session.current = false
            }
            completed.countDown()
        }
        while (!a.job.cancelled.get()) Thread.yield()
        assertFalse(completed.await(20, TimeUnit.MILLISECONDS))
        assertThrows(IllegalStateException::class.java) { f.admit(a.job.session) }
        assertNull(f.coordinator.attach("model", "chat-A"))
        f.finish(a); assertTrue(completed.await(5, TimeUnit.SECONDS)); t.join()
        assertFalse(f.coordinator.owns(a))
    }
    @Test fun staleDestroyOrUnloadRejectedBeforeItCanCancelNewJob() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        assertThrows(IllegalStateException::class.java) { f.coordinator.transition(isWanted = { false }) { fail() } }
        assertFalse(a.job.cancelled.get()); f.finish(a)
    }
    @Test fun transitionRechecksIdentityAfterWaitingForAnotherTransition() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.finish(a)
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val wanted = java.util.concurrent.atomic.AtomicBoolean(true)
        val first = thread { f.coordinator.transition { held.countDown(); check(release.await(5, TimeUnit.SECONDS)); wanted.set(false) } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val rejected = CountDownLatch(1)
        val second = thread {
            try { f.coordinator.transition(isWanted = { wanted.get() }) { fail("Stale action ran") } }
            catch (_: IllegalStateException) { rejected.countDown() }
        }
        release.countDown(); first.join(); second.join(); assertEquals(0, rejected.count)
    }
    @Test fun startupFailureRunsNoNativeAndSavesNoPhantomMessages() {
        val f = Fixture(); val a = f.admit()
        f.coordinator.failStartup(a.job.id); f.coordinator.start(a.job.id)
        runBlocking { withTimeout(5000) { a.job.completion.await() } }
        assertEquals(0, f.nativeCalls.get()); assertEquals(0, f.userSaves.get()); assertEquals(0, f.assistantSaves.get())
        assertEquals(ChatGenerationCoordinator.Phase.FAILED, a.job.state.value.phase)
        assertFalse(a.job.state.value.saved)
    }
    @Test fun pendingStopDoesNotNeedServiceReplayOrSaveAnEmptyAssistant() {
        val f = Fixture(); val a = f.admit(); f.coordinator.stop(a.job.id)
        runBlocking { withTimeout(5000) { a.job.completion.await() } }
        assertEquals(0, f.nativeCalls.get()); assertEquals(0, f.assistantSaves.get())
        assertEquals(ChatGenerationCoordinator.Phase.STOPPED, a.job.state.value.phase)
    }
    @Test fun commitFailureCannotPublishSavedSuccess() {
        val f = Fixture(); f.failSave = true; val a = f.admit(); f.start(a); f.finish(a)
        assertFalse(a.job.state.value.saved); assertEquals(ChatGenerationCoordinator.Phase.FAILED, a.job.state.value.phase)
        assertEquals("Could not save the chat response", a.job.state.value.result["message"])
    }
    @Test fun retirementBeforeVoiceOrResetPreventsOldSnapshotReattachment() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.finish(a)
        assertTrue(f.coordinator.retire(a)); assertNull(f.coordinator.attach("model", "chat-A"))
        assertEquals(0, a.job.session.detached); assertNull(f.coordinator.jobs.value)
    }
    @Test fun retirementWhileNativeIsActiveIsRejected() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        assertFalse(f.coordinator.retire(a)); assertSame(a.job, f.coordinator.active()); f.finish(a)
    }
    @Test fun matchingNativeEpochCannotEvadePendingDetachWithANewWrapper() {
        val f = Fixture(); val a = f.admit(); f.start(a); f.finish(a)
        val release = CountDownLatch(1); f.detachBarrier = release
        f.coordinator.detachObserver(a)
        val wrapped = Session(a.job.session.native, a.job.session.epoch)
        assertThrows(IllegalStateException::class.java) { f.admit(wrapped) }
        assertNull(f.coordinator.attach("model", "chat-A")); release.countDown()
    }
    @Test fun modelListCanResumeOnlyActiveSameModelAndExplicitHistoryMustMatch() {
        val f = Fixture(); val a = f.admit(); f.start(a)
        assertNull(f.coordinator.attach("other-model", null)); assertNull(f.coordinator.attach("model", "chat-B"))
        assertSame(a.job, f.coordinator.attach("model", null)!!.job)
        f.finish(a); assertNull(f.coordinator.attach("model", null))
    }
    @Test fun admissionAndObserverHandoffInvalidateIdleActionRevision() {
        val f = Fixture(); val a = f.admit(); val initial = f.actionRevision.get()
        f.coordinator.attach("model", "chat-A")
        assertEquals(initial + 1, f.actionRevision.get())
        f.coordinator.stop(a.job.id); runBlocking { a.job.completion.await() }
    }
    @Test fun retentionOnKeepsModelAfterDetachedGenerationButOffUnloadsAfterDrain() {
        for (retain in listOf(true, false)) {
            var releases = 0
            val runtime = com.alibaba.mnnllm.api.openai.runtime.ResidentChatRuntime<Session>(
                { true }, {}, {}, { releases++ })
            val resident = runtime.acquire("model", { Session() }) { _, _ -> }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val entered = CountDownLatch(1); val returned = CountDownLatch(1)
            val core = ChatGenerationCoordinator<Session>(scope, { runtime.isCurrent(it, resident.epoch) }, { job, emit ->
                runtime.withAttachment(job.session, resident.epoch) {
                    entered.countDown(); check(returned.await(5, TimeUnit.SECONDS))
                    emit(ChatGenerationCoordinator.Output("result", "result")); emptyMap()
                }
            }, {}, { _, _ -> }, { runtime.detach(it, resident.epoch, retain) })
            val a = core.admit(ChatGenerationCoordinator.Request("chat", "model", "Model", null, "hello", "time"), resident.session)
            core.start(a.job.id); assertTrue(entered.await(5, TimeUnit.SECONDS))
            core.detachObserver(a)
            assertEquals(0, releases); assertSame(resident.session, runtime.current())
            returned.countDown(); runBlocking { withTimeout(5000) { a.job.completion.await() } }
            if (retain) { assertEquals(0, releases); assertSame(resident.session, runtime.current()) }
            else { assertEquals(1, releases); assertNull(runtime.current()) }
            scope.cancel()
        }
    }

}
