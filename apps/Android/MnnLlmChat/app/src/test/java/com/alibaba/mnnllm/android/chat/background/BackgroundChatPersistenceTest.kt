// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import android.app.Application
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class BackgroundChatPersistenceTest {
    @Test fun detachedTaskWritesUserAndAssistantOnceToCapturedConversation() = runBlocking {
        val db = ChatDataManager.getInstance(RuntimeEnvironment.getApplication())
        val conversation = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val entered = CountDownLatch(1); val returned = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = ChatGenerationCoordinator<Any>(scope, { true }, { _, emit ->
            entered.countDown(); check(returned.await(5, TimeUnit.SECONDS))
            emit(ChatGenerationCoordinator.Output("reply", "reply")); emptyMap()
        }, { job ->
            db.addOrUpdateSession(job.request.conversationId, "model")
            db.addChatDataChecked(job.request.conversationId, ChatDataItem("time", 0, job.request.text))
        }, { job, state -> db.addChatDataChecked(job.request.conversationId, ChatDataItem("time", 1, state.output.raw)) }, {})
        val a = coordinator.admit(ChatGenerationCoordinator.Request(conversation, "model", "Model", null, "question", "time"), Any())
        coordinator.start(a.job.id); assertTrue(entered.await(5, TimeUnit.SECONDS))
        coordinator.detachObserver(a)
        db.addOrUpdateSession(other, "another model")
        returned.countDown(); withTimeout(5000) { a.job.completion.await() }
        assertEquals(listOf("question", "reply"), db.getChatDataBySession(conversation).map { it.text })
        assertTrue(db.getChatDataBySession(other).isEmpty())
        coordinator.start(a.job.id)
        assertEquals(2, db.getChatDataBySession(conversation).size)
        scope.cancel()
    }
    @Test fun concurrentHistoryReadsCannotCloseServiceWriteTransaction() {
        val db = ChatDataManager.getInstance(RuntimeEnvironment.getApplication())
        val conversation = UUID.randomUUID().toString()
        db.addOrUpdateSession(conversation, "model")
        val error = AtomicReference<Throwable?>()
        val writer = thread {
            try { repeat(20) { db.addChatDataChecked(conversation, ChatDataItem("time", 1, "reply $it")) } }
            catch (t: Throwable) { error.set(t) }
        }
        val reader = thread {
            try { repeat(20) { db.allSessions; db.getChatDataBySession(conversation) } }
            catch (t: Throwable) { error.set(t) }
        }
        writer.join(); reader.join()
        assertNull(error.get()); assertEquals(20, db.getChatDataBySession(conversation).size)
    }
    @Test fun checkedWriterRejectsEmptyOrMissingConversationInsteadOfReportingSuccess() {
        val db = ChatDataManager.getInstance(RuntimeEnvironment.getApplication())
        assertThrows(IllegalStateException::class.java) { db.addChatDataChecked(null, ChatDataItem("time", 1, "reply")) }
        assertThrows(IllegalStateException::class.java) { db.addChatDataChecked("chat", ChatDataItem("time", 1, "")) }
    }
}
