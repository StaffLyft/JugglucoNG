package tk.glucodata.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.ClarityStatus
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.R

private const val LOG_ID = "ClaritySettings"

// Native exception text can carry tokens or URLs, so only the type is logged.
internal fun logClarityFailure(tag: String, operation: String, error: Throwable) {
    Log.e(tag, "$operation: ${error.javaClass.name}")
}

internal sealed interface ClarityMessage {
    data class Text(val text: String) : ClarityMessage
    data class Resource(@StringRes val id: Int, val detail: String? = null) : ClarityMessage
}

internal data class ClaritySettingsUiState(
    val status: ClarityStatus? = null,
    val message: ClarityMessage? = null,
)

class ClaritySettingsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ClaritySettingsUiState())
    internal val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(2_000)
            }
        }
    }

    private suspend fun refresh() {
        val status = withContext(Dispatchers.IO) {
            runCatching { Natives.clarityStatus() }
                .onFailure { logClarityFailure(LOG_ID, "Read status", it) }
                .getOrNull()
        } ?: return
        mutableState.update { it.copy(status = status) }
    }

    fun configure(enabled: Boolean, libre3History: Boolean, since: Long) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { Natives.clarityConfigure(enabled, libre3History, false, since) }
                    .onFailure { logClarityFailure(LOG_ID, "Save settings", it) }
                    .getOrNull()
            }
            val message = when {
                error == null -> ClarityMessage.Resource(R.string.clarity_save_settings_error)
                error.isEmpty() -> null
                else -> ClarityMessage.Text(error)
            }
            mutableState.update { it.copy(message = message) }
            refresh()
        }
    }

    fun sendNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Natives.clarityWake() }
                    .onFailure { logClarityFailure(LOG_ID, "Send now", it) }
            }
            refresh()
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }
}
