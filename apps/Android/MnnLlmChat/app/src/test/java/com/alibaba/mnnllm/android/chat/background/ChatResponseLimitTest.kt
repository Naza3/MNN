package com.alibaba.mnnllm.android.chat.background

import org.junit.Assert.*
import org.junit.Test

class ChatResponseLimitTest {
    @Test fun neverAccumulatesMoreThanTheLimit() {
        val limit = ChatResponseLimit(5)
        assertEquals("abc", limit.accept("abc")); assertFalse(limit.reached)
        assertEquals("de", limit.accept("defghi")); assertTrue(limit.reached)
        assertEquals("", limit.accept("more")); assertNull(limit.accept(null))
    }
    @Test fun truncationDoesNotLeaveAnUnpairedSurrogate() {
        val limit = ChatResponseLimit(2)
        assertEquals("x", limit.accept("x\uD83D\uDE00")); assertTrue(limit.reached)
    }
}
