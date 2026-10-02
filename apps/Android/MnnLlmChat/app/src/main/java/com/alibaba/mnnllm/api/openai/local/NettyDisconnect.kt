// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import io.ktor.server.application.*
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.util.AttributeKey
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import java.util.concurrent.atomic.AtomicBoolean

private val channelKey = AttributeKey<Channel>("mnn.localapi.netty.channel")

/** Ktor 3.1.3: capture the actual engine call before Routing wraps it. Attributes are forwarded. */
fun Application.captureNettyChannel() {
    intercept(ApplicationCallPipeline.Setup) {
        val engineCall = context as? NettyApplicationCall
        if (engineCall != null) engineCall.attributes.put(channelKey, engineCall.context.channel())
        proceed()
    }
}

/** A TCP close triggers cancellation even during non-streaming JNI prefill. No reflection/no Job assumption. */
fun watchNettyDisconnect(call: ApplicationCall, onDisconnect: () -> Unit): AutoCloseable {
    val channel = checkNotNull(call.attributes.getOrNull(channelKey)) { "Netty disconnect monitoring is required" }
    val armed = AtomicBoolean(true)
    val future = channel.closeFuture()
    val listener = ChannelFutureListener { if (armed.getAndSet(false)) onDisconnect() }
    future.addListener(listener) // also fires when already closed, avoiding a check-then-register race
    return AutoCloseable { armed.set(false); future.removeListener(listener) }
}
