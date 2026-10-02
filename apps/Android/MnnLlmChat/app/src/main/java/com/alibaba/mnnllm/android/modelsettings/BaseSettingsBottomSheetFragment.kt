// Modified by MNN Chat API contributors, 2026: settings callbacks belong to their UI attachment.
// Created by ruoyi.sjd on 2025/4/29.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.modelsettings

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.utils.BaseBottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Base class for model settings bottom sheet fragments.
 * Provides common functionality for loading/saving config and UI setup.
 */
abstract class BaseSettingsBottomSheetFragment : BaseBottomSheetDialogFragment() {

    protected lateinit var loadedConfig: ModelConfig
    private var _modelId: String = ""
    protected val modelId: String get() = _modelId
    protected lateinit var currentConfig: ModelConfig
    private var _configPath: String? = null
    protected val configPath: String? get() = _configPath
    protected var needRecreateActivity = false
    protected var onSettingsDoneListener: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedModelId = savedInstanceState?.getString(KEY_MODEL_ID)
        val argModelId = arguments?.getString(KEY_MODEL_ID)
        _modelId = savedModelId ?: argModelId ?: _modelId

        _configPath = if (savedInstanceState?.containsKey(KEY_CONFIG_PATH) == true) {
            savedInstanceState.getString(KEY_CONFIG_PATH)
        } else {
            arguments?.getString(KEY_CONFIG_PATH) ?: _configPath
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog: Dialog? = dialog
        if (dialog != null) {
            val bottomSheet: FrameLayout? = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                bottomSheet.post {
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                behavior.skipCollapsed = false
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Load config off main thread to avoid ANR (file I/O)
        lifecycleScope.launch {
            loadSettingsAsync()
            setupUI()
            refreshUIFromConfig()
            setupActionButtons()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODEL_ID, _modelId)
        outState.putString(KEY_CONFIG_PATH, _configPath)
    }

    /**
     * Load settings from config files (runs on IO dispatcher, then updates on Main)
     */
    private suspend fun loadSettingsAsync() {
        val config = withContext(Dispatchers.IO) {
            val defaultConfigFile = resolveConfigFilePath(_modelId, _configPath, ModelConfig::getDefaultConfigFile)
            if (defaultConfigFile.isNullOrBlank()) {
                Log.w(TAG, "Missing config path for modelId=$_modelId, fallback to default config")
                ModelConfig.defaultConfig.deepCopy()
            } else {
                ModelConfig.loadMergedConfig(
                    defaultConfigFile,
                    ModelConfig.getExtraConfigFile(_modelId)
                ) ?: ModelConfig.defaultConfig.deepCopy()
            }
        }
        loadedConfig = config
        currentConfig = loadedConfig.deepCopy()
    }

    /**
     * Load settings from config files (synchronous, for internal use from coroutine)
     */
    protected open fun loadSettings() {
        val defaultConfigFile = resolveConfigFilePath(_modelId, _configPath, ModelConfig::getDefaultConfigFile)
        loadedConfig = if (defaultConfigFile.isNullOrBlank()) {
            Log.w(TAG, "Missing config path for modelId=$_modelId, fallback to default config")
            ModelConfig.defaultConfig.deepCopy()
        } else {
            ModelConfig.loadMergedConfig(
                defaultConfigFile,
                ModelConfig.getExtraConfigFile(_modelId)
            ) ?: ModelConfig.defaultConfig.deepCopy()
        }
        currentConfig = loadedConfig.deepCopy()
    }

    /**
     * Setup UI components - to be implemented by subclasses
     */
    protected abstract fun setupUI()

    /**
     * Refresh UI from currentConfig after load. Override to populate EditTexts etc.
     * Called after loadSettingsAsync and setupUI so saved values (e.g. system prompt) are displayed.
     */
    protected open fun refreshUIFromConfig() {}

    /**
     * Setup action buttons (Cancel, Save, Reset)
     */
    protected abstract fun setupActionButtons()

    /**
     * Save settings to config file
     */
    protected abstract fun saveSettings()

    /**
     * Reset settings to defaults. Deletes custom_config.json so base config.json is used,
     * then reloads. Ensures default system prompt and other defaults are restored.
     */
    private var runtimeSession: com.alibaba.mnnllm.android.llm.ChatSession? = null
    private var runtimeEpoch: Long? = null

    fun setRuntimeAttachment(session: com.alibaba.mnnllm.android.llm.ChatSession?, epoch: Long?) {
        runtimeSession = session
        runtimeEpoch = epoch
        if (!isAdded) arguments = (arguments ?: Bundle()).apply { putBoolean("requires_runtime_attachment", session != null) }
    }

    protected fun isRuntimeAttachmentCurrent(): Boolean = (runtimeSession == null &&
        arguments?.getBoolean("requires_runtime_attachment", false) != true) ||
        com.alibaba.mnnllm.api.openai.di.ServiceLocator.getLlmRuntimeController()
            .isChatAttachmentCurrent(runtimeSession, runtimeEpoch)

    protected fun runForRuntimeAttachment(action: () -> Unit): Boolean {
        val session = runtimeSession
        if (session == null) {
            if (!isRuntimeAttachmentCurrent()) return false
            action(); return true
        }
        val controller = com.alibaba.mnnllm.api.openai.di.ServiceLocator.getLlmRuntimeController()
        if (!controller.isChatAttachmentCurrent(session, runtimeEpoch)) return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val applied = controller.tryWithChatAttachment(session, runtimeEpoch, action)
            if (!applied && isAdded) android.widget.Toast.makeText(requireContext(),
                com.alibaba.mnnllm.android.R.string.model_settings_busy, android.widget.Toast.LENGTH_LONG).show()
            return applied
        }
        return try { controller.withChatAttachment(session, runtimeEpoch, action); true }
        catch (_: IllegalStateException) { false }
    }

    protected open fun resetSettingsToDefaults() {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runForRuntimeAttachment { ModelConfig.deleteExtraConfig(_modelId) }
            }
            if (!applied || !isRuntimeAttachmentCurrent()) return@launch
            loadSettingsAsync()
            if (isRuntimeAttachmentCurrent()) onAfterSettingsReset()
        }
    }

    /**
     * Called after settings are reset. Override in subclasses to update UI.
     */
    protected open fun onAfterSettingsReset() {}

    fun setModelId(modelId: String) {
        this._modelId = modelId
        if (!isAdded) {
            val args = (arguments ?: Bundle()).apply {
                putString(KEY_MODEL_ID, modelId)
            }
            arguments = args
        }
    }

    fun setConfigPath(configPath: String?) {
        this._configPath = configPath
        if (!isAdded) {
            val args = (arguments ?: Bundle()).apply {
                putString(KEY_CONFIG_PATH, configPath)
            }
            arguments = args
        }
    }

    fun addOnSettingsDoneListener(listener: (Boolean) -> Unit) {
        onSettingsDoneListener = listener
    }

    companion object {
        private const val TAG = "BaseSettingsBottomSheet"
        private const val KEY_MODEL_ID = "settings_model_id"
        private const val KEY_CONFIG_PATH = "settings_config_path"

        internal fun resolveConfigFilePath(
            modelId: String,
            configPath: String?,
            defaultConfigProvider: (String) -> String?
        ): String? {
            if (!configPath.isNullOrBlank()) {
                return configPath
            }
            if (modelId.isBlank()) {
                return null
            }
            return defaultConfigProvider(modelId)
        }
    }
}
