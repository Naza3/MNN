// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import org.junit.Assert.*
import org.junit.Test

class ForegroundNotificationOwnerTest {
    @Test fun handoffDemotesChatBeforeApiPromotionAndRejectsOldUpdatesRemoval() {
        val owner = ForegroundNotificationOwner(); val events = mutableListOf<String>()
        val chat = owner.claim { events += "chat-detach" }
        owner.update(chat) { events += "chat-promote" }
        val api = owner.claim { events += "api-detach" }
        owner.update(api) { events += "api-promote" }
        assertFalse(owner.update(chat) { fail("Old chat update overwrites API") })
        assertFalse(owner.release(chat) { fail("Old chat cleanup removes API") })
        assertEquals(listOf("chat-promote", "chat-detach", "api-promote"), events)
        assertTrue(owner.release(api) { events += "api-remove" })
        assertFalse(owner.owns(api))
    }
    @Test fun releasedOwnerCannotRemoveNextServiceOrReplayItsStop() {
        val owner = ForegroundNotificationOwner(); val a = owner.claim {}
        assertTrue(owner.release(a) {})
        val b = owner.claim {}
        assertFalse(owner.release(a) { fail() }); assertTrue(owner.owns(b))
    }
    @Test fun absentProcessOwnershipCannotUpdateOrRemoveANotification() {
        val owner = ForegroundNotificationOwner()
        assertFalse(owner.update(null) { fail() }); assertFalse(owner.release(null) { fail() })
    }
}
