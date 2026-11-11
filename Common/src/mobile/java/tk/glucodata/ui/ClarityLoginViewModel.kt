package tk.glucodata.ui

import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.R

internal const val CLARITY_LOGIN_LOG_ID = "ClarityLogin"
internal const val CLARITY_CALLBACK_SCHEME = "dexcomg7"
private const val COMPLETION_ATTEMPTED_KEY = "completionAttempted"

// A closed screen can still have a native token exchange in progress.
private val clarityLoginFinishing = AtomicBoolean()

internal enum class ClarityLoginPhase { Setup, Web, Completing }

internal data class ClarityLoginUiState(
    val phase: ClarityLoginPhase = ClarityLoginPhase.Setup,
    val completionAttempted: Boolean = false,
    val loginUrl: String? = null,
    val error: ClarityMessage? = null,
    val signedIn: Boolean = false,
)

private fun isDexcomPage(uri: Uri): Boolean {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return uri.scheme == "https" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "dexcom.com" || host.endsWith(".dexcom.com") ||
            host == "dexcom.eu" || host.endsWith(".dexcom.eu"))
}

class ClarityLoginViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ClarityLoginUiState(completionAttempted = savedState[COMPLETION_ATTEMPTED_KEY] ?: false)
    )
    internal val state = mutableState.asStateFlow()
    private var callbackSeen = false

    fun begin(country: String) {
        if (clarityLoginFinishing.get()) {
            mutableState.update { it.copy(error = ClarityMessage.Resource(R.string.clarity_login_in_progress)) }
            return
        }
        mutableState.update { it.copy(error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Natives.clarityLoginBegin(
                        country,
                        Locale.getDefault().toLanguageTag(),
                        Build.MANUFACTURER,
                        Build.MODEL,
                        Build.VERSION.RELEASE
                    )
                }.onFailure { logClarityFailure(CLARITY_LOGIN_LOG_ID, "Begin sign-in", it) }.getOrNull()
            }
            val startError = ClarityMessage.Resource(R.string.clarity_login_start_error)
            when {
                result == null || result.size != 2 -> mutableState.update { it.copy(error = startError) }
                result[1].isNotEmpty() -> mutableState.update { it.copy(error = ClarityMessage.Text(result[1])) }
                !isDexcomPage(Uri.parse(result[0])) -> mutableState.update { it.copy(error = startError) }
                else -> {
                    callbackSeen = false
                    mutableState.update { it.copy(phase = ClarityLoginPhase.Web, loginUrl = result[0]) }
                }
            }
        }
    }

    fun completePending() = complete("")

    fun navigate(uri: Uri): Boolean {
        if (mutableState.value.phase != ClarityLoginPhase.Web) return true
        if (uri.scheme == CLARITY_CALLBACK_SCHEME) {
            if (!callbackSeen) {
                callbackSeen = true
                complete(uri.toString())
            }
            return true
        }
        if (isDexcomPage(uri)) return false
        Log.e(CLARITY_LOGIN_LOG_ID, "Blocked sign-in navigation outside Dexcom")
        stopWithError(ClarityMessage.Resource(R.string.clarity_login_destination_error))
        return true
    }

    fun fail(text: String) {
        Log.e(CLARITY_LOGIN_LOG_ID, text)
        stopWithError(ClarityMessage.Text(text))
    }

    fun leaveWeb() {
        mutableState.update { it.copy(phase = ClarityLoginPhase.Setup, loginUrl = null) }
    }

    private fun stopWithError(error: ClarityMessage) {
        if (mutableState.value.phase == ClarityLoginPhase.Completing) return
        mutableState.update { it.copy(phase = ClarityLoginPhase.Setup, loginUrl = null, error = error) }
    }

    private fun complete(callback: String) {
        if (!clarityLoginFinishing.compareAndSet(false, true)) {
            Log.e(CLARITY_LOGIN_LOG_ID, "Sign-in completion already running")
            stopWithError(ClarityMessage.Resource(R.string.clarity_login_in_progress))
            return
        }
        savedState[COMPLETION_ATTEMPTED_KEY] = true
        mutableState.update {
            it.copy(phase = ClarityLoginPhase.Completing, completionAttempted = true, loginUrl = null)
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Natives.clarityLoginFinish(callback)
                } catch (e: Exception) {
                    logClarityFailure(CLARITY_LOGIN_LOG_ID, "Complete sign-in", e)
                    null
                } finally {
                    clarityLoginFinishing.set(false)
                }
            }
            mutableState.update {
                when {
                    result == null -> it.copy(
                        phase = ClarityLoginPhase.Setup,
                        error = ClarityMessage.Resource(R.string.clarity_login_complete_error),
                    )
                    result.isEmpty() -> it.copy(signedIn = true)
                    else -> it.copy(
                        phase = ClarityLoginPhase.Setup,
                        error = ClarityMessage.Resource(R.string.clarity_login_retry_error, result),
                    )
                }
            }
        }
    }
}
