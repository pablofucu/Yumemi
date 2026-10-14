package com.pablo.yumemi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.settings.HostRules
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.SettingsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val client: HermesClient,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrl = settings.baseUrl.value,
            apiKey = settings.apiKey.value,
            insecureWarning = HostRules.isInsecure(settings.baseUrl.value),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Extra (not in Contract.kt): true = POST /v1/chat/completions transport, false = sessions API. */
    val useChatCompletions: StateFlow<Boolean> = settings.useChatCompletions

    private var testJob: Job? = null

    fun onBaseUrlChange(v: String) {
        testJob?.cancel()
        _uiState.update {
            it.copy(
                baseUrl = v,
                insecureWarning = HostRules.isInsecure(v),
                testResult = ConnectionState.Unknown,
                saved = false,
            )
        }
    }

    fun onApiKeyChange(v: String) {
        testJob?.cancel()
        _uiState.update { it.copy(apiKey = v, testResult = ConnectionState.Unknown, saved = false) }
    }

    /** Extra (not in Contract.kt). */
    fun onTransportChange(useChatCompletions: Boolean) {
        settings.setUseChatCompletions(useChatCompletions)
    }

    fun testConnection() {
        val state = _uiState.value
        val base = HostRules.normalizeBaseUrl(state.baseUrl)
        if (base.isEmpty()) {
            _uiState.update { it.copy(testResult = ConnectionState.NotConfigured) }
            return
        }
        testJob?.cancel()
        _uiState.update { it.copy(testResult = ConnectionState.Checking) }
        testJob = viewModelScope.launch {
            val result = client.testConnection(base, state.apiKey.trim())
            _uiState.update { it.copy(testResult = result) }
        }
    }

    fun save() {
        val s = _uiState.value
        settings.save(s.baseUrl, s.apiKey)
        _uiState.update {
            it.copy(
                baseUrl = settings.baseUrl.value,
                apiKey = settings.apiKey.value,
                insecureWarning = HostRules.isInsecure(settings.baseUrl.value),
                saved = true,
            )
        }
    }
}
