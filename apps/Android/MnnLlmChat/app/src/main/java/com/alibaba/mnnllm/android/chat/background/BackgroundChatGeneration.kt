// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mnnllm.android.chat.GenerateResultProcessor
import com.alibaba.mnnllm.android.chat.chatlist.ChatViewHolders
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.utils.PreferenceUtils
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Only application-scoped data is retained. This never holds a presenter or UI callback. */
object BackgroundChatGeneration {
    class Lease(val native: ChatSession, val epoch: Long?, val conversationId: String) {
        @Volatile var history: List<ChatDataItem>? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller get() = ServiceLocator.getLlmRuntimeController()
    private val database get() = ChatDataManager.getInstance(ApplicationProvider.get())
    const val MAX_RESPONSE_CHARS = 1_048_576
    val coordinator = ChatGenerationCoordinator<Lease>(scope,
        isCurrent = { controller.isChatAttachmentCurrent(it.native, it.epoch) && it.native.sessionId == it.conversationId },
        execute = { job, publish ->
            controller.withChatAttachment(job.session.native, job.session.epoch) {
                job.session.native.setKeepHistory(true)
                val processor = GenerateResultProcessor().apply { generateBegin() }
                val limit = ChatResponseLimit(MAX_RESPONSE_CHARS)
                val result = job.session.native.generate(job.request.text, emptyMap(), object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (job.cancelled.get()) return true
                        val accepted = limit.accept(progress)
                        processor.process(accepted)
                        publish(ChatGenerationCoordinator.Output(processor.getRawResult(), processor.getNormalOutput(),
                            processor.getThinkingContent(), processor.thinkTime))
                        return limit.reached
                    }
                })
                processor.process(null)
                publish(ChatGenerationCoordinator.Output(processor.getRawResult(), processor.getNormalOutput(),
                    processor.getThinkingContent(), processor.thinkTime))
                HashMap(result).apply { if (limit.reached) put("response_limit_reached", true) }
            }
        },
        saveUser = { job ->
            val history = database.getChatDataBySession(job.request.conversationId)
            job.session.history = history
            database.addOrUpdateSession(job.request.conversationId, job.request.modelId)
            if (history.isEmpty()) database.updateSessionName(job.request.conversationId, job.request.text.take(100))
            database.addChatDataChecked(job.request.conversationId, userItem(job))
        },
        saveAssistant = { job, snapshot ->
            // A stop before the first output still has a visible, persisted outcome.
            val item = assistantItem(snapshot, job.request.time)
            database.addChatDataChecked(job.request.conversationId, item)
        },
        detach = { lease -> controller.detachCompletedChatSession(lease.native, lease.epoch,
            PreferenceUtils.keepModelLoaded(ApplicationProvider.get())) },
        sameSession = { a, b -> a.native === b.native && a.epoch == b.epoch },
        onAttachment = { ResidentModelStatus.refreshConversation(it.native) }
    )

    fun userItem(job: ChatGenerationCoordinator.Job<Lease>) =
        ChatDataItem(job.request.time, ChatViewHolders.USER, job.request.text)

    fun assistantItem(snapshot: ChatGenerationCoordinator.Snapshot, time: String?): ChatDataItem {
        val fallback = when (snapshot.phase) {
            ChatGenerationCoordinator.Phase.FAILED -> snapshot.result["message"] as? String ?: "Chat generation failed"
            ChatGenerationCoordinator.Phase.STOPPED -> ApplicationProvider.get().getString(com.alibaba.mnnllm.android.R.string.chat_background_stopped)
            else -> ""
        }
        return ChatDataItem(time, ChatViewHolders.ASSISTANT, snapshot.output.raw.ifEmpty { fallback }).apply {
            displayText = snapshot.output.display.ifEmpty { if (snapshot.output.raw.isEmpty()) fallback else "" }
            thinkingText = snapshot.output.thinking
            thinkingFinishedTime = snapshot.output.thinkTime
            loading = snapshot.phase.active
            benchmarkInfo = if (!snapshot.phase.active) ModelUtils.generateBenchMarkString(HashMap(snapshot.result)) else null
        }
    }

    fun history(job: ChatGenerationCoordinator.Job<Lease>): List<ChatDataItem> {
        job.session.history?.let { return it }
        val persisted = database.getChatDataBySession(job.request.conversationId)
        return job.session.history ?: persisted
    }
}
