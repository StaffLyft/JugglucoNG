package tk.glucodata.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.io.ByteArrayInputStream
import java.util.Locale
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.util.findActivity

private fun systemCountry(context: Context): String =
    LocaleManagerCompat.getSystemLocales(context)[0]?.country.orEmpty().uppercase(Locale.ROOT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClarityLoginScreen(navController: NavController) {
    val model: ClarityLoginViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var country by rememberSaveable { mutableStateOf(systemCountry(context)) }

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

    val tlsErrorText = stringResource(R.string.clarity_login_tls_error)
    val loadErrorText = stringResource(R.string.clarity_login_load_error)

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            Toast.makeText(context, R.string.clarity_login_success, Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }

    BackHandler(enabled = state.phase == ClarityLoginPhase.Web, onBack = model::leaveWeb)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clarity_sign_in),
                onNavigateBack = {
                    if (state.phase == ClarityLoginPhase.Web) {
                        model.leaveWeb()
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }
    ) { padding ->
        val url = state.loginUrl
        when {
            state.phase == ClarityLoginPhase.Web && url != null -> ClarityLoginWebView(
                url = url,
                onNavigate = model::navigate,
                onFailure = model::fail,
                tlsErrorText = tlsErrorText,
                loadErrorText = loadErrorText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            state.phase == ClarityLoginPhase.Completing -> Box(
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
                state.error?.let {
                    Text(
                        text = it.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { model.begin(country) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    enabled = country.length == 2,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.clarity_sign_in))
                }
                if (state.completionAttempted) {
                    OutlinedButton(
                        onClick = model::completePending,
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
                        Log.e(CLARITY_LOGIN_LOG_ID, "Blocked non-HTTPS sign-in resource")
                        return WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Forbidden",
                            emptyMap(), ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        Log.e(CLARITY_LOGIN_LOG_ID, "Sign-in TLS certificate error code=${error.primaryError}")
                        failure(tlsErrorText)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        Log.e(CLARITY_LOGIN_LOG_ID, "Sign-in resource error code=${error.errorCode} mainFrame=${request.isForMainFrame}")
                        if (request.isForMainFrame) failure(loadErrorText)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        Log.e(CLARITY_LOGIN_LOG_ID, "Sign-in HTTP error=${errorResponse.statusCode} mainFrame=${request.isForMainFrame}")
                        if (request.isForMainFrame) {
                            failure(context.getString(R.string.clarity_login_http_error, errorResponse.statusCode))
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url.startsWith("$CLARITY_CALLBACK_SCHEME:")) {
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
