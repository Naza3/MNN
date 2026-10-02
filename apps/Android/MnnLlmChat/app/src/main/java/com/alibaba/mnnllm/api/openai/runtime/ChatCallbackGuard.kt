// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

/** A delayed generation callback belongs to one conversation/request, even on the same attachment. */
class ChatCallbackGuard {
    data class Token internal constructor(val epoch: Long, val conversationId: String?)
    private var epoch = 0L
    private var resetting = false
    @Synchronized fun next(conversationId: String?): Token {
        check(!resetting) { "Conversation reset is still in progress" }
        return Token(++epoch, conversationId)
    }
    /** Read the ID inside the same lock that publishes reset completion. */
    @Synchronized fun tryNext(conversationId: () -> String?): Token? =
        if (resetting) null else Token(++epoch, conversationId())
    @Synchronized fun beginReset(): Token? {
        if (resetting) return null
        resetting = true
        return Token(++epoch, null)
    }
    @Synchronized fun completeReset(token: Token, publish: () -> Unit): Boolean {
        if (token.epoch != epoch || !resetting) return false
        try { publish() } finally { resetting = false }
        return true
    }
    @Synchronized fun invalidate() { epoch++; resetting = false }
    @Synchronized fun isCurrent(token: Token): Boolean = token.epoch == epoch
    @Synchronized fun runIfCurrent(token: Token, action: () -> Unit): Boolean {
        if (token.epoch != epoch) return false
        action()
        return true
    }
}
