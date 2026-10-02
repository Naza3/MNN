// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ResidentModelStatusTest {
    private class Session(var loaded: Boolean = true) : ChatSession {
        override val debugInfo = "fake"
        override var sessionId = "conversation-A"
        override val supportOmni = false
        override fun load() { loaded = true }
        override fun isModelLoaded() = loaded
        override fun generate(prompt: String, params: Map<String, Any>, progressListener: GenerateProgressListener) = hashMapOf<String, Any>()
        override fun reset(): String { sessionId = "conversation-B"; return sessionId }
        override fun release() { loaded = false }
        override fun setKeepHistory(keepHistory: Boolean) {}
        override fun setEnableAudioOutput(enable: Boolean) {}
        override fun getHistory(): List<ChatDataItem>? = null
        override fun setHistory(history: List<ChatDataItem>?) {}
        override fun updateThinking(thinking: Boolean) {}
    }
    @After fun clear() {
        ResidentModelStatus.pending.value?.let { ResidentModelStatus.finishLoading(it.token) }
        ResidentModelStatus.clear()
    }
    @Test fun fastLoadCompletionBeforeServiceIntentStillAcceptsOriginalHost() {
        val host = ResidentModelStatus.beginLoading("model", "Model")
        val native = Session()
        ResidentModelStatus.loaded("model", "Model", "/config", native)
        ResidentModelStatus.finishLoading(host.token)
        assertNull(ResidentModelStatus.pending.value)
        assertTrue(ResidentModelStatus.acceptsHost(host.token))
    }
    @Test fun failedOrReleasedModelCannotReplayHostIntent() {
        val host = ResidentModelStatus.beginLoading("model", "Model")
        ResidentModelStatus.finishLoading(host.token)
        assertFalse(ResidentModelStatus.acceptsHost(host.token))
        val native = Session()
        ResidentModelStatus.loaded("model", "Model", "/config", native)
        assertTrue(ResidentModelStatus.acceptsHost(host.token))
        ResidentModelStatus.released(native)
        assertFalse(ResidentModelStatus.acceptsHost(host.token))
    }
    @Test fun oldLoadCompletionCannotClearNewPendingAdmission() {
        val old = ResidentModelStatus.beginLoading("old", "Old")
        val newer = ResidentModelStatus.beginLoading("new", "New")
        ResidentModelStatus.finishLoading(old.token)
        assertEquals(newer, ResidentModelStatus.pending.value)
        assertFalse(ResidentModelStatus.acceptsHost(old.token)); assertTrue(ResidentModelStatus.acceptsHost(newer.token))
    }
    @Test fun resetAndObserverHandoffInvalidatePreviousIdleUnloadAction() {
        val native = Session()
        ResidentModelStatus.loaded("model", "Model", "/config", native)
        val initial = ResidentModelStatus.state.value!!.token
        ResidentModelStatus.refreshConversation(native)
        val reattached = ResidentModelStatus.state.value!!.token
        assertNotEquals(initial, reattached)
        native.reset(); ResidentModelStatus.refreshConversation(native)
        assertNotEquals(reattached, ResidentModelStatus.state.value!!.token)
        assertEquals("conversation-B", ResidentModelStatus.state.value!!.conversationId)
    }
    @Test fun staleReleaseCannotClearNewlyLoadedNativeInstance() {
        val old = Session(); val newer = Session()
        ResidentModelStatus.loaded("model", "Model", "/config", old)
        ResidentModelStatus.loaded("new", "New", "/config2", newer)
        ResidentModelStatus.released(old)
        assertSame(newer, ResidentModelStatus.state.value!!.native)
    }
    @Test fun unloadedNativeCannotBePublishedAsResident() {
        ResidentModelStatus.loaded("model", "Model", "/config", Session(false))
        assertNull(ResidentModelStatus.state.value)
    }
    @Test fun failedCleanupKeepsReservationVisibleWithoutReportingLoadedReady() {
        val native = Session()
        ResidentModelStatus.loaded("model", "Model", "/config", native)
        ResidentModelStatus.cleanupFailed(native)
        assertTrue(ResidentModelStatus.state.value!!.cleanupFailed)
        assertSame(native, ResidentModelStatus.state.value!!.native)
    }

}
