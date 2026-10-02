// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import org.junit.Assert.*
import org.junit.Test

class ChatAttachmentGuardTest {
    @Test fun recreatedActivityReusesNativeObjectButInvalidatesOldAttachment() {
        val guard = ChatAttachmentGuard<Any>(); val session = Any()
        val oldActivity = guard.attach(session)
        val recreatedActivity = guard.attach(session)
        assertFalse(guard.isCurrent(session, oldActivity))
        assertTrue(guard.isCurrent(session, recreatedActivity))
    }
    @Test fun onlyCurrentAttachmentCanReleaseAndOnlyOnce() {
        val guard = ChatAttachmentGuard<Any>(); val session = Any(); var releases = 0
        fun release(epoch: Long) {
            if (guard.isCurrent(session, epoch)) { guard.invalidate(); releases++ }
        }
        val oldActivity = guard.attach(session)
        val newActivity = guard.attach(session)
        release(oldActivity) // delayed destroy
        release(oldActivity) // delayed load-failure cleanup
        assertEquals(0, releases)
        release(newActivity); release(newActivity)
        assertEquals(1, releases)
    }
    @Test fun delayedLoadFailureCannotReleaseANewAttachmentOrApiSession() {
        val guard = ChatAttachmentGuard<Any>(); val old = Any(); val next = Any()
        val oldEpoch = guard.attach(old)
        val nextEpoch = guard.attach(next)
        assertFalse(guard.isCurrent(old, oldEpoch))
        assertFalse(guard.isCurrent(next, oldEpoch))
        assertFalse(guard.isCurrent(next, null))
        assertTrue(guard.isCurrent(next, nextEpoch))
        guard.invalidate() // API takeover or actual release
        assertFalse(guard.isCurrent(next, nextEpoch))
    }
}
