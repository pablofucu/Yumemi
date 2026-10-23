package com.pablo.yumemi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.settings.HostRules
import com.pablo.yumemi.data.settings.SettingsStore
import com.pablo.yumemi.data.update.AppUpdater
import com.pablo.yumemi.data.update.UpdateInfo
import com.pablo.yumemi.data.update.UpdateState
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
    private val updater: AppUpdater,
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

    /** Extra (not in Contract.kt): feeds URL as typed; empty = derived from the Hermes URL. */
    private val _feedsUrl = MutableStateFlow(settings.feedsUrlOverride.value)
    val feedsUrl: StateFlow<String> = _feedsUrl.asStateFlow()

    fun onFeedsUrlChange(v: String) {
        _feedsUrl.value = v
        _uiState.update { it.copy(saved = false) }
    }

    /** What an empty feeds field means for the URL being edited. */
    fun defaultFeedsUrl(): String = HostRules.defaultFeedsUrl(_uiState.value.baseUrl)

    val updateState: StateFlow<UpdateState> = updater.state
    val appVersion: String = updater.currentVersion

    fun checkUpdate() {
        viewModelScope.launch { updater.check() }
    }

    fun installUpdate(info: UpdateInfo) {
        viewModelScope.launch { updater.downloadAndInstall(info) }
    }

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
        settings.saveFeedsUrl(_feedsUrl.value)
        _feedsUrl.value = settings.feedsUrlOverride.value
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
