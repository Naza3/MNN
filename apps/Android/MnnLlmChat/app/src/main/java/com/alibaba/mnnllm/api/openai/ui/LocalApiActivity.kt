// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.ui

import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnerGate
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership
import com.alibaba.mnnllm.api.openai.service.ApiServerConfig
import com.alibaba.mnnllm.api.openai.service.OpenAIService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground UI is only a control surface; leaving it never stops the started Service. */
class LocalApiActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.local_api_title)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 24) }
        val status = TextView(this)
        val modelId = intent.getStringExtra("modelId") ?: OpenAIService.getInstance()?.getCurrentModelId()
        val description = TextView(this)
        layout.addView(description); layout.addView(status)
        val port = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.local_api_port_hint); setText(ApiServerConfig.getPort(this@LocalApiActivity).toString()) }
        layout.addView(port)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { action() }; layout.addView(this) }
        button(getString(R.string.local_api_save_port)) {
            if (RuntimeOwnership.gate.isApiReserved()) toast(getString(R.string.local_api_stop_change_port))
            else try { ApiServerConfig.savePort(this, port.text.toString().toInt()); toast(getString(R.string.local_api_port_saved)) }
            catch (e: Exception) { toast(getString(R.string.local_api_invalid_port)) }
        }
        button(getString(R.string.local_api_copy_key)) {
            val clip = ClipData.newPlainText(getString(R.string.local_api_copy_key), ApiServerConfig.getApiKey(this))
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            toast(getString(R.string.local_api_key_copied))
        }
        button(getString(R.string.local_api_regenerate_key)) {
            if (RuntimeOwnership.gate.isApiReserved()) toast(getString(R.string.local_api_stop_rotate))
            else androidx.appcompat.app.AlertDialog.Builder(this).setMessage(getString(R.string.local_api_replace_key_message))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(getString(R.string.local_api_replace)) { _, _ ->
                    try { ApiServerConfig.regenerateKey(this); toast(getString(R.string.local_api_key_replaced)) }
                    catch (e: IllegalStateException) { toast(getString(R.string.local_api_stop_rotate)) }
                }.show()
        }
        val start = button(getString(R.string.local_api_start)) {
            if (modelId.isNullOrBlank()) toast(getString(R.string.local_api_select_model))
            else {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3201)
                }
                try { OpenAIService.startService(this, modelId) } catch (e: Exception) { toast(getString(R.string.local_api_start_failed)) }
            }
        }
        val stop = button(getString(R.string.local_api_stop)) { OpenAIService.releaseService(this) }
        button(getString(R.string.local_api_back)) { finish() }
        setContentView(ScrollView(this).apply { addView(layout) })
        lifecycleScope.launch {
            while (true) {
                val state = RuntimeOwnership.gate.state
                val label = when (state) {
                    RuntimeOwnerGate.State.CHAT -> R.string.local_api_state_chat
                    RuntimeOwnerGate.State.STARTING_API -> R.string.local_api_state_starting
                    RuntimeOwnerGate.State.READY_API -> R.string.local_api_state_ready
                    RuntimeOwnerGate.State.STOPPING_API -> R.string.local_api_state_stopping
                    RuntimeOwnerGate.State.CLEANUP_FAILED -> R.string.local_api_state_failed
                }
                status.text = getString(R.string.local_api_status, getString(label))
                description.text = getString(R.string.local_api_description) + "\n\n" +
                    getString(R.string.local_api_model_url, modelId ?: getString(R.string.local_api_select_model), ApiServerConfig.getPort(this@LocalApiActivity))
                start.isEnabled = !RuntimeOwnership.gate.isApiReserved() && !modelId.isNullOrBlank()
                stop.isEnabled = RuntimeOwnership.gate.isApiReserved()
                port.isEnabled = !RuntimeOwnership.gate.isApiReserved()
                delay(300)
            }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
