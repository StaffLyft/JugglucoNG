package tk.glucodata.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.LocaleManagerCompat
import androidx.navigation.NavController
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.util.findActivity

private const val LOGIN_LOG_ID = "ClarityLogin"
private const val CALLBACK_SCHEME = "dexcomg7"

// A closed screen can still have a native token exchange in progress.
private val clarityLoginFinishing = AtomicBoolean()

private enum class ClarityLoginPhase { Setup, Web, Completing }

private fun isDexcomPage(uri: Uri): Boolean {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return uri.scheme == "https" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "dexcom.com" || host.endsWith(".dexcom.com") ||
            host == "dexcom.eu" || host.endsWith(".dexcom.eu"))
}

private fun systemCountry(context: Context): String =
    LocaleManagerCompat.getSystemLocales(context)[0]?.country.orEmpty().uppercase(Locale.ROOT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClarityLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(ClarityLoginPhase.Setup) }
    var country by rememberSaveable { mutableStateOf(systemCountry(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var completionAttempted by rememberSaveable { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var callbackSeen by remember { mutableStateOf(false) }

    // Dexcom's credential form is on screen: keep it out of screenshots and recents.
    val window = context.findActivity()?.window
    DisposableEffect(window) {
        val secureBefore = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!secureBefore) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val inProgressText = stringResource(R.string.clarity_login_in_progress)
    val startErrorText = stringResource(R.string.clarity_login_start_error)
    val completeErrorText = stringResource(R.string.clarity_login_complete_error)
    val destinationErrorText = stringResource(R.string.clarity_login_destination_error)
    val tlsErrorText = stringResource(R.string.clarity_login_tls_error)
    val loadErrorText = stringResource(R.string.clarity_login_load_error)

    fun fail(text: String) {
        Log.e(LOGIN_LOG_ID, text)
        if (phase == ClarityLoginPhase.Completing) return
        phase = ClarityLoginPhase.Setup
        loginUrl = null
        error = text
    }

    fun complete(callback: String) {
        if (!clarityLoginFinishing.compareAndSet(false, true)) {
            fail(inProgressText)
            return
        }
        phase = ClarityLoginPhase.Completing
        completionAttempted = true
        loginUrl = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Natives.clarityLoginFinish(callback)
                } catch (e: Exception) {
                    logClarityFailure(LOGIN_LOG_ID, "Complete sign-in", e)
                    null
                } finally {
                    clarityLoginFinishing.set(false)
                }
            }
            when {
                result == null -> {
                    phase = ClarityLoginPhase.Setup
                    error = completeErrorText
                }
                result.isEmpty() -> {
                    Toast.makeText(context, R.string.clarity_login_success, Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                }
                else -> {
                    phase = ClarityLoginPhase.Setup
                    error = context.getString(R.string.clarity_login_retry_error, result)
                }
            }
        }
    }

    fun begin() {
        if (clarityLoginFinishing.get()) {
            error = inProgressText
            return
        }
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Natives.clarityLoginBegin(
                        country,
                        Locale.getDefault().toLanguageTag(),
                        Build.MANUFACTURER,
                        Build.MODEL,
                        Build.VERSION.RELEASE
                    )
                }.onFailure { logClarityFailure(LOGIN_LOG_ID, "Begin sign-in", it) }.getOrNull()
            }
            when {
                result == null || result.size != 2 -> error = startErrorText
                result[1].isNotEmpty() -> error = result[1]
                !isDexcomPage(Uri.parse(result[0])) -> error = startErrorText
                else -> {
                    callbackSeen = false
                    loginUrl = result[0]
                    phase = ClarityLoginPhase.Web
                }
            }
        }
    }

    fun navigate(uri: Uri): Boolean {
        if (phase != ClarityLoginPhase.Web) return true
        if (uri.scheme == CALLBACK_SCHEME) {
            if (!callbackSeen) {
                callbackSeen = true
                complete(uri.toString())
            }
            return true
        }
        if (isDexcomPage(uri)) return false
        fail(destinationErrorText)
        return true
    }

    BackHandler(enabled = phase == ClarityLoginPhase.Web) {
        phase = ClarityLoginPhase.Setup
        loginUrl = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clarity_sign_in),
                onNavigateBack = {
                    if (phase == ClarityLoginPhase.Web) {
                        phase = ClarityLoginPhase.Setup
                        loginUrl = null
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    ) { padding ->
        val url = loginUrl
        when {
            phase == ClarityLoginPhase.Web && url != null -> ClarityLoginWebView(
                url = url,
                onNavigate = ::navigate,
                onFailure = ::fail,
                tlsErrorText = tlsErrorText,
                loadErrorText = loadErrorText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            phase == ClarityLoginPhase.Completing -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.clarity_login_completing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.clarity_login_instructions),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = country,
                    onValueChange = { input ->
                        country = input.filter { it in 'A'..'Z' || it in 'a'..'z' }
                            .take(2)
                            .uppercase(Locale.ROOT)
                    },
                    label = { Text(stringResource(R.string.clarity_login_country)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = ::begin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    enabled = country.length == 2,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.clarity_sign_in))
                }
                if (completionAttempted) {
                    OutlinedButton(
                        onClick = { complete("") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Text(stringResource(R.string.clarity_login_finish_pending))
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ClarityLoginWebView(
    url: String,
    onNavigate: (Uri) -> Boolean,
    onFailure: (String) -> Unit,
    tlsErrorText: String,
    loadErrorText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navigate by rememberUpdatedState(onNavigate)
    val failure by rememberUpdatedState(onFailure)
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && navigate(request.url)

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        if (request.url.scheme == "https") return null
                        Log.e(LOGIN_LOG_ID, "Blocked non-HTTPS sign-in resource")
                        return WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden",
                            emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        Log.e(LOGIN_LOG_ID, "Sign-in TLS certificate error code=${error.primaryError}")
                        failure(tlsErrorText)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        Log.e(LOGIN_LOG_ID, "Sign-in resource error code=${error.errorCode} mainFrame=${request.isForMainFrame}")
                        if (request.isForMainFrame) failure(loadErrorText)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        Log.e(LOGIN_LOG_ID, "Sign-in HTTP error=${errorResponse.statusCode} mainFrame=${request.isForMainFrame}")
                        if (request.isForMainFrame) {
                            failure(context.getString(R.string.clarity_login_http_error, errorResponse.statusCode))
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url.startsWith("$CALLBACK_SCHEME:")) {
                            view.stopLoading()
                            navigate(Uri.parse(url))
                        }
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
