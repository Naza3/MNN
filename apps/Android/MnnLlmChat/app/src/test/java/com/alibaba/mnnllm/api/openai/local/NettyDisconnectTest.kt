// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HTTP/1.1 sockets, not testApplication: verifies Netty channel-close cancellation during JNI-like prefill. */
class NettyDisconnectTest {
    @Test fun nonStreamSocketCloseCancelsNativeRequest() = verifyDisconnect(false)
    @Test fun sseSocketCloseCancelsNativeRequest() = verifyDisconnect(true)
    private fun verifyDisconnect(stream: Boolean) = runBlocking {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val worker = BoundedInferenceQueue(LocalInferenceBackend { _, token ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (token(null)) { cancelled.countDown(); return@LocalInferenceBackend InferenceResult("") }
                Thread.sleep(5)
            }
            throw IllegalStateException("Socket close did not cancel inference")
        })
        val server = embeddedServer(Netty, configure = { connector { host = "127.0.0.1"; port = 0 }; enableHttp2 = false }) {
            localApiModule(worker, "test-model", { "ephemeral-socket-test-key" })
        }
        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            val socket = Socket("127.0.0.1", port)
            val body = """{"messages":[{"role":"user","content":"hello"}],"stream":$stream}"""
            val request = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ephemeral-socket-test-key\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
            socket.getOutputStream().write(request.toByteArray()); socket.getOutputStream().flush()
            assertTrue("Request did not reach fake native backend", entered.await(5, TimeUnit.SECONDS))
            socket.close()
            assertTrue("Closing the real socket must request native stop", cancelled.await(5, TimeUnit.SECONDS))
        } finally {
            worker.stopAccepting()
            server.stopSuspend(0, 1000)
            withTimeout(5000) { worker.shutdownAndJoin() }
        }
    }
}
