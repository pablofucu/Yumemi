package com.pablo.yumemi.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base URL, API key and transport choice. The API key and URL are stored with
 * EncryptedSharedPreferences (AES256, key in the Android Keystore).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_URL, "").orEmpty())
    private val _apiKey = MutableStateFlow(prefs.getString(KEY_KEY, "").orEmpty())
    private val _useChatCompletions = MutableStateFlow(prefs.getString(KEY_TRANSPORT, TRANSPORT_SESSIONS) == TRANSPORT_CHAT)

    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()
    /** false = SESSIONS (default, /api/sessions/{id}/chat/stream); true = CHAT_COMPLETIONS. */
    val useChatCompletions: StateFlow<Boolean> = _useChatCompletions.asStateFlow()

    val isConfigured: Boolean get() = _baseUrl.value.isNotBlank()

    fun save(baseUrl: String, apiKey: String) {
        val url = HostRules.normalizeBaseUrl(baseUrl)
        val key = apiKey.trim()
        prefs.edit().putString(KEY_URL, url).putString(KEY_KEY, key).apply()
        _baseUrl.value = url
        _apiKey.value = key
    }

    fun setUseChatCompletions(value: Boolean) {
        prefs.edit().putString(KEY_TRANSPORT, if (value) TRANSPORT_CHAT else TRANSPORT_SESSIONS).apply()
        _useChatCompletions.value = value
    }

    private companion object {
        const val FILE = "yumemi_secure_settings"
        const val KEY_URL = "base_url"
        const val KEY_KEY = "api_key"
        const val KEY_TRANSPORT = "transport"
        const val TRANSPORT_SESSIONS = "SESSIONS"
        const val TRANSPORT_CHAT = "CHAT_COMPLETIONS"

        fun createPrefs(context: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                build()
            } catch (e: Exception) {
                // Corrupted keystore / prefs (e.g. after restore): start clean once.
                try {
                    context.deleteSharedPreferences(FILE)
                    build()
                } catch (e2: Exception) {
                    // Last resort so the app still opens; values are then stored unencrypted.
                    context.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
                }
            }
        }
    }
}
