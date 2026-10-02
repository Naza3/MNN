// Modified by MNN Chat API contributors, 2026: shared exclusive native-runtime lease.
package com.alibaba.mnnllm.android.llm

import android.util.Log
import com.alibaba.mls.api.ApplicationProvider
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatService.Companion.provide
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import com.google.gson.Gson
import java.util.HashMap

class SanaSession(
    private val modelId: String,
    override var sessionId: String,
    private val configPath: String,
    private var savedHistory: List<ChatDataItem>? = null
) : ChatSession {

    override var supportOmni: Boolean = false
    override val debugInfo: String = ""

    @Volatile private var nativePtr: Long = 0
    private var releaseFailed = false
    private val generationCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun isModelLoaded(): Boolean = nativePtr != 0L
    override fun cancelGeneration() { generationCancelled.set(true) }
    @Synchronized override fun attachConversation(id: String, history: List<ChatDataItem>?) {
        ownerGate.checkAccess(ownerLease)
        provide().removeSession(sessionId)
        sessionId = id
        savedHistory = history?.toList()
        generationCancelled.set(false)
    }
    @Synchronized override fun detachUi() { /* Wait for any uninterruptible native call to return. */ }

    private val ownerGate = com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership.gate
    private lateinit var ownerLease: com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnerGate.Lease
    init { synchronized(ownerGate) { ownerLease = ownerGate.acquireChat { release() } } }
    @Volatile
    private var releaseRequested = false
    @Volatile
    private var generating = false

    @Synchronized
    override fun load() {
        ownerGate.checkAccess(ownerLease)
        if (nativePtr != 0L) return
        try {
        Log.d(TAG, "SanaSession load() called, configPath: $configPath")
        val config = try {
            ModelConfig.loadConfig(modelId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load ModelConfig for $modelId, using defaults: ${e.message}")
            null
        }
        val configMap = HashMap<String, Any>().apply {
            put("diffusion_memory_mode", config?.diffusionMemoryMode ?: "0")
            put("backend_type", config?.backendType ?: "opencl")
            put("image_width", config?.imageWidth ?: 512)
            put("image_height", config?.imageHeight ?: 512)
            put("grid_size", config?.gridSize ?: 1)
        }
        nativePtr = initNative(
            configPath,
            Gson().toJson(configMap)
        )
        check(nativePtr != 0L) { "Native model initialization failed" }
        Log.d(TAG, "SanaSession load() nativePtr initialized: $nativePtr")
        if (releaseRequested) {
            release()
        }
        } catch (error: Throwable) {
            try { release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun generate(
        prompt: String,
        params: Map<String, Any>,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any> {
        synchronized(this) {
            ownerGate.checkAccess(ownerLease)
            if (nativePtr == 0L) {
                Log.e(TAG, "nativePtr is 0, cannot generate")
                return HashMap<String, Any>().apply {
                    put("error", true)
                    put("message", "Native session not initialized")
                }
            }
            generating = true
            val output = params["output"] as String
            val imageInput = params["imageInput"] as? String ?: ""
            val steps = params["iterNum"] as Int
            val seed = params["randomSeed"] as Int
            val useCfg = params["useCfg"] as? Boolean ?: true
            val cfgScale = (params["cfgScale"] as? Number)?.toFloat() ?: 4.5f
            val result = generateNative(
                nativePtr,
                prompt,
                imageInput,
                output,
                steps,
                seed,
                useCfg,
                cfgScale,
                object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean = ownerLease.cancelled.get() || generationCancelled.get() || progressListener.onProgress(progress)
                }
            ) ?: HashMap<String, Any>().apply {
                put("error", true)
                put("message", "Native generation returned null")
            }

            // Check success flag from native
            if (result["success"] == false) {
                Log.e(TAG, "Native generation failed: ${result["message"]}")
            }

            generating = false
            if (releaseRequested) {
                releaseInner()
            }
            return result
        }
    }

    @Synchronized
    override fun reset(): String {
        ownerGate.checkAccess(ownerLease)
        return System.currentTimeMillis().toString().also { sessionId = it }
    }

    @Synchronized
    override fun release() {
        ownerLease.cancelled.set(true)
        releaseInner()
        ownerGate.released(ownerLease)
    }

    private fun releaseInner() {
        if (nativePtr != 0L) {
            check(!releaseFailed) { "Previous native cleanup was not confirmed" }
            try { releaseNative(nativePtr) } catch (error: Throwable) { releaseFailed = true; throw error }
            nativePtr = 0
            provide().removeSession(sessionId)
        }
    }

    override fun setKeepHistory(keepHistory: Boolean) {
        // Sana session does not support history
    }

    override fun setEnableAudioOutput(enable: Boolean) {
        // Not used
    }

    override fun getHistory(): List<ChatDataItem>? = savedHistory

    override fun setHistory(history: List<ChatDataItem>?) {
        savedHistory = history
    }

    override fun updateThinking(thinking: Boolean) {
    }

    private external fun initNative(resourcePath: String, configJson: String): Long
    private external fun releaseNative(instanceId: Long)
    private external fun generateNative(
        instanceId: Long,
        prompt: String,
        imagePath: String,
        outputPath: String,
        steps: Int,
        seed: Int,
        useCfg: Boolean,
        cfgScale: Float,
        progressListener: GenerateProgressListener
    ): HashMap<String, Any>?

    companion object {
        const val TAG = "SanaSession"
        init {
            System.loadLibrary("mnnllmapp")
        }
    }
}
