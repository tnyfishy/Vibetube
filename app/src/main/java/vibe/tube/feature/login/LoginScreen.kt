package vibe.tube.feature.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.CenterLoading
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VibeLinearProgress
import vibe.tube.ui.theme.Spacing

// Built by concatenation: the percent-encoded `continue` URL contains sequences such as `%2F`
// that String.format would parse as format specifiers and crash on.
private fun loginUrl(language: String): String {
    val hl = android.net.Uri.encode(language)
    return "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3D" + hl +
        "&hl=" + hl + "&ec=66426"
}

private val allowedHosts = listOf("google.com", "youtube.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "googleapis.com")

private val blockedMarkers = listOf("may not be secure", "không an toàn", "disallowed_useragent")

private fun hostAllowed(host: String?): Boolean =
    host != null && allowedHosts.any { host == it || host.endsWith(".$it") }

private fun isYoutubeHost(host: String?): Boolean =
    host != null && (host == "youtube.com" || host.endsWith(".youtube.com"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onClose: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language.ifBlank { "en" }

    LaunchedEffect(state) {
        if (state is LoginUiState.Done) onClose()
    }
    // Google finishes sign-in with script redirects that do not always reach a WebViewClient callback, so also
    // watch for the session cookies while the page is open (this is what the reload button used to trigger).
    LaunchedEffect(state) {
        if (state != LoginUiState.Browsing) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(700)
            val header = CookieManager.getInstance().getCookie("https://www.youtube.com")
            if (viewModel.hasSessionCookies(header)) {
                viewModel.onCookieHeader(header.orEmpty())
                break
            }
        }
    }
    BackHandler(enabled = webView?.canGoBack() == true && state is LoginUiState.Browsing) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                navigationIcon = { TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), onClose) },
                actions = {
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                LoginUiState.Browsing, LoginUiState.Verifying, LoginUiState.Done -> {
                    Column(Modifier.fillMaxSize()) {
                        if (progress in 1..99) {
                            VibeLinearProgress(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs))
                        }
                        Text(
                            stringResource(R.string.login_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                        LoginWebView(
                            loginUrl = remember(language) { loginUrl(language) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onProgress = { progress = it },
                            onCreated = { webView = it },
                            onYoutubeReached = { header ->
                                val ready = viewModel.hasSessionCookies(header)
                                if (ready) viewModel.onCookieHeader(header.orEmpty())
                                ready
                            },
                            onBlocked = viewModel::onBlocked,
                        )
                    }
                    if (s == LoginUiState.Verifying) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CenterLoading(Modifier.padding(Spacing.xl))
                                Text(stringResource(R.string.login_verifying), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                is LoginUiState.Blocked -> StateMessage(
                    icon = VIcon.Shield,
                    title = stringResource(R.string.login_blocked_title),
                    body = stringResource(R.string.login_blocked_body, s.detail),
                    primaryLabel = stringResource(R.string.action_retry),
                    onPrimary = viewModel::restart,
                    secondaryLabel = stringResource(R.string.action_close),
                    onSecondary = onClose,
                )
                LoginUiState.NotConfirmed -> StateMessage(
                    icon = VIcon.Error,
                    title = stringResource(R.string.login_not_confirmed_title),
                    body = stringResource(R.string.login_not_confirmed_body),
                    primaryLabel = stringResource(R.string.action_retry),
                    onPrimary = viewModel::restart,
                    secondaryLabel = stringResource(R.string.action_close),
                    onSecondary = onClose,
                )
                is LoginUiState.NetworkFailure -> StateMessage(
                    icon = VIcon.WifiOff,
                    title = stringResource(R.string.login_network_title),
                    body = stringResource(R.string.login_network_body),
                    primaryLabel = stringResource(R.string.action_retry),
                    onPrimary = viewModel::retryVerify,
                    secondaryLabel = stringResource(R.string.action_close),
                    onSecondary = onClose,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    loginUrl: String,
    modifier: Modifier,
    onProgress: (Int) -> Unit,
    onCreated: (WebView) -> Unit,
    onYoutubeReached: (String?) -> Boolean,
    onBlocked: (String) -> Unit,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }
    DisposableEffect(webView) {
        onCreated(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https" || !hostAllowed(uri.host)) return true
                // The session cookies are ready as soon as Google hands off to youtube.com. Stop there
                // instead of loading the heavy YouTube home page just to notice the cookies.
                if (isYoutubeHost(uri.host) && onYoutubeReached(CookieManager.getInstance().getCookie("https://www.youtube.com"))) {
                    view.stopLoading()
                    return true
                }
                val text = uri.toString()
                if (blockedMarkers.any { text.contains(it, ignoreCase = true) } || text.contains("signin/rejected")) {
                    onBlocked("rejected_url")
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onProgress(1)
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                if (isYoutubeHost(host) && onYoutubeReached(CookieManager.getInstance().getCookie("https://www.youtube.com"))) {
                    view.stopLoading()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onProgress(100)
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                if (isYoutubeHost(host)) {
                    CookieManager.getInstance().flush()
                    onYoutubeReached(CookieManager.getInstance().getCookie("https://www.youtube.com"))
                } else if (host == "accounts.google.com") {
                    view.evaluateJavascript(
                        "(function(){var t=(document.body&&document.body.innerText||'').toLowerCase();return t;})()",
                    ) { raw ->
                        val text = raw.orEmpty().lowercase()
                        if (blockedMarkers.any { text.contains(it) }) onBlocked("page_notice")
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && request.url.host == "accounts.google.com" && errorResponse.statusCode in listOf(400, 403)) {
                    onBlocked("http_${errorResponse.statusCode}")
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onBlocked("net_${error.errorCode}")
            }
        }
        webView.loadUrl(loginUrl)
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}
