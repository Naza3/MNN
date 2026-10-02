// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime
import org.junit.Assert.*
import org.junit.Test

class ChatCallbackGuardTest {
    @Test fun delayedFinishFromBeforeSwitchCannotSaveToNewConversation() {
        val guard = ChatCallbackGuard(); val a = guard.next("chat-A")
        val writes = mutableListOf<String?>()
        val delayedFinish = { guard.runIfCurrent(a) { writes += a.conversationId } }
        guard.invalidate() // Reset, switch, or destroyed Activity.
        val b = guard.next("chat-B")
        assertFalse(delayedFinish())
        assertTrue(guard.runIfCurrent(b) { writes += b.conversationId })
        assertEquals(listOf("chat-B"), writes)
    }
    @Test fun aLaterRequestInSameChatRejectsAnOlderQueuedFinish() {
        val guard = ChatCallbackGuard(); val first = guard.next("chat-A"); val second = guard.next("chat-A")
        assertFalse(guard.runIfCurrent(first) { fail("Old finish must not edit the new response") })
        assertTrue(guard.runIfCurrent(second) {})
    }
    @Test fun sendBetweenNativeResetReturnAndUiPublicationCannotKeepOldDatabaseId() {
        val guard = ChatCallbackGuard()
        var publishedId = "old-db-chat"
        val reset = guard.beginReset()!!
        val nativeReturned = java.util.concurrent.CountDownLatch(1)
        val allowPublication = java.util.concurrent.CountDownLatch(1)
        val native = kotlin.concurrent.thread {
            val nativeNewId = "new-db-chat" // Fake native reset has completed, UI publication is delayed.
            nativeReturned.countDown()
            check(allowPublication.await(5, java.util.concurrent.TimeUnit.SECONDS))
            guard.completeReset(reset) { publishedId = nativeNewId }
        }
        assertTrue(nativeReturned.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(guard.tryNext { publishedId }) // Send is non-blockingly rejected, not admitted with old ID.
        assertNull(guard.beginReset())
        allowPublication.countDown(); native.join()
        val accepted = guard.tryNext { publishedId }!!
        assertEquals("new-db-chat", accepted.conversationId)
    }
    @Test fun lateResetPublicationCannotOverwriteSwitchedConversation() {
        val guard = ChatCallbackGuard(); var id = "old"
        val reset = guard.beginReset()!!
        guard.invalidate(); id = "switched"
        assertFalse(guard.completeReset(reset) { id = "late-reset" })
        assertEquals("switched", guard.tryNext { id }!!.conversationId)
    }

}
