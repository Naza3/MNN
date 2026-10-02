// Modified by MNN Chat API contributors, 2026: loopback-only mandatory authentication.
package com.alibaba.mnnllm.api.openai.service

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object ApiServerConfig {
    const val LOOPBACK = "127.0.0.1"
    private const val PREFS_NAME = "local_api_private"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    fun validateEndpoint(host: String, port: Int) {
        require(host == LOOPBACK) { "Only 127.0.0.1 is allowed" }
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
    }
    @Synchronized fun initializeConfig(context: Context) {
        if (prefs(context).getString("key", null).isNullOrBlank()) createAndSaveKey(context)
    }
    fun getPort(context: Context): Int = prefs(context).getInt("port", 8080).also { validateEndpoint(LOOPBACK, it) }
    fun getIpAddress(context: Context): String = LOOPBACK
    fun isCorsEnabled(context: Context): Boolean = false
    fun getCorsOrigins(context: Context): String = ""
    fun isAuthEnabled(context: Context): Boolean = true
    fun useHttpsUrl(context: Context): Boolean = false
    @Synchronized fun getApiKey(context: Context): String {
        initializeConfig(context)
        return prefs(context).getString("key", "")!!
    }
    @Synchronized fun regenerateKey(context: Context): String =
        com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership.gate.whileApiStopped { createAndSaveKey(context) }
    private fun createAndSaveKey(context: Context): String {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        check(prefs(context).edit().putString("key", key).commit()) { "Cannot save API key" }
        return key
    }
    fun matches(candidate: String?, configured: String): Boolean =
        !candidate.isNullOrBlank() && configured.isNotBlank() &&
            MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), configured.toByteArray(Charsets.UTF_8))
    fun savePort(context: Context, port: Int) {
        validateEndpoint(LOOPBACK, port)
        com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership.gate.whileApiStopped {
            check(prefs(context).edit().putInt("port", port).commit())
        }
    }
    // Kept for source compatibility. We never persist weakened host/auth/CORS settings.
    fun saveConfig(context: Context, port: Int, ipAddress: String, corsEnabled: Boolean,
                   corsOrigins: String, authEnabled: Boolean, apiKey: String, useHttpsUrl: Boolean) {
        validateEndpoint(ipAddress, port)
        require(authEnabled && !corsEnabled && !useHttpsUrl)
        savePort(context, port)
    }
    @Synchronized fun resetToDefault(context: Context) = com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership.gate.whileApiStopped {
        savePort(context, 8080); regenerateKey(context); Unit
    }
}
