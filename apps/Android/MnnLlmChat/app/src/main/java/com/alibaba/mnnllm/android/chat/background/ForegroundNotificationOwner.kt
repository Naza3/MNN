// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

/** One notification identity across Chat/API. Revocation demotes the old FGS before promotion. */
class ForegroundNotificationOwner {
    data class Token internal constructor(val epoch: Long)
    private var epoch = 0L
    private var current: Token? = null
    private var revoke: (() -> Unit)? = null
    @Synchronized fun claim(onRevoked: () -> Unit): Token {
        revoke?.invoke()
        return Token(++epoch).also { current = it; revoke = onRevoked }
    }
    @Synchronized fun owns(token: Token?): Boolean = token != null && current == token
    @Synchronized fun update(token: Token?, action: () -> Unit): Boolean {
        if (!owns(token)) return false
        action(); return true
    }
    @Synchronized fun release(token: Token?, remove: () -> Unit): Boolean {
        if (!owns(token)) return false
        current = null; revoke = null
        remove(); return true
    }
    companion object {
        const val NOTIFICATION_ID = 1001
        // Reuse the existing API channel, including the user's importance/block choice.
        const val CHANNEL_ID = "local_api_service"
        val shared = ForegroundNotificationOwner()
    }
}
