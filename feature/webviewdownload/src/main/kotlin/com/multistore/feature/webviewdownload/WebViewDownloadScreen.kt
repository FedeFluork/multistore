package com.multistore.feature.webviewdownload

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multistore.core.common.result.AppError
import com.multistore.core.designsystem.theme.LocalSpacing
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.ThemeMode
import com.multistore.core.model.WebFilterConfig
import com.multistore.core.ui.component.MultiStoreDetailTopAppBar
import com.multistore.core.ui.component.appErrorMessage
import com.multistore.store.api.DownloadHint

/**
 * The assisted download: the store's real page, inside the app.
 *
 * The line is a sharp one and this screen sits entirely on the right side of it: **actually doing** what
 * the site asks is legitimate; **pretending** to have done it is not. Here a real browser engine runs on a
 * real page, and a person does the pressing. No captcha-solving service, no forged TLS fingerprint, no
 * address rotation.
 *
 * The difference from opening the system browser — which is what the app did before — is the **return**.
 * With the system browser the APK lands in the Downloads folder and Android installs it with none of
 * MultiStore's checks having run: not the `packageName` match, not the signature comparison, not the
 * anti-downgrade. By intercepting the download here, the file goes back into the queue and crosses exactly
 * the same pipeline as the other eight stores.
 */
@Composable
fun WebViewDownloadScreen(
    onFinished: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebViewDownloadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.handoff.collect(onFinished)
    }

    // The back button returns to the page's previous hop, not out of the screen: an assisted download
    // crosses three or four of them, and leaving on the first tap would force redoing everything — captcha
    // included.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    WebViewDownloadScreen(
        uiState = uiState,
        onBack = onBack,
        onDismissError = viewModel::dismissError,
        onDismissHint = viewModel::dismissHint,
        modifier = modifier,
    ) { contentModifier ->
        StoreWebView(
            pageUrl = uiState.pageUrl,
            // `rememberUpdatedState` and not the captured value: the WebView is built once, and its client
            // lives as long as the page. Without this, turning the filter off in Settings would have no
            // effect on an already-open screen — and the filter gets turned off exactly when a page is not
            // working.
            webFilter = rememberUpdatedState(uiState.webFilter),
            onCreated = { webView = it },
            onProgress = viewModel::onPageProgress,
            onPageChanged = viewModel::onPageChanged,
            onError = viewModel::onPageError,
            onDownload = viewModel::onDownloadIntercepted,
            modifier = contentModifier,
        )
    }
}

/**
 * WebView-free variant, for previews and screenshot tests.
 *
 * The web content is a **slot** and not a conditional branch: in a Robolectric test the WebView draws
 * nothing real, and a golden including it would photograph an empty rectangle and pass it off as the page.
 * What this screen has to guarantee is the frame — the instructions, the progress, the back button, the
 * colours in both themes — and the frame photographs perfectly well with a placeholder in place of
 * Chromium.
 */
@Composable
internal fun WebViewDownloadScreen(
    uiState: WebViewDownloadUiState,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onDismissHint: () -> Unit = {},
    modifier: Modifier = Modifier,
    webContent: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { MultiStoreDetailTopAppBar(title = uiState.title, onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.hintVisible) {
                HintBanner(
                    hint = uiState.hint,
                    host = uiState.currentHost,
                    onDismiss = onDismissHint,
                )
            }

            uiState.error?.let { error ->
                ErrorBanner(message = appErrorMessage(error), onDismiss = onDismissError)
            }

            if (uiState.pageProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { uiState.pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            webContent(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * What the user has to do, in one sentence, plus where they are doing it.
 *
 * The host is not a technical detail added for completeness: an assisted download crosses three or four
 * redirects, and without seeing the current domain the user has no way of noticing that the tap they are
 * about to make is no longer on the store's page.
 */
@Composable
private fun HintBanner(
    hint: DownloadHint,
    host: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = spacing.large,
                end = spacing.small,
                top = spacing.large,
                bottom = spacing.large,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Rounded.TouchApp, contentDescription = null)
            Column(
                modifier = Modifier
                    .padding(start = spacing.large)
                    .weight(1f),
            ) {
                Text(
                    text = stringResource(hint.instructionRes()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                host?.let {
                    Text(
                        text = stringResource(R.string.webviewdownload_current_host, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // The notice says what to do, and takes three lines on a screen where what matters is the page:
            // once read, it is space taken from the button to press. It closes, and stays closed for as long
            // as the screen lives — see `hintVisible`.
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.webviewdownload_hint_dismiss),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.webviewdownload_error_dismiss))
            }
        }
    }
}

/**
 * The real WebView.
 *
 * JavaScript is on deliberately, and that is exactly what makes this path legitimate: the site's challenge
 * **is executed**, not circumvented. Turning it off would make the screen useless — none of the pages that
 * reach here work without it.
 *
 * `setDownloadListener` is where the file re-enters MultiStore. Without it the download would be handed to
 * the system manager and the APK would land in the Downloads folder, outside every check of the
 * verification pipeline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StoreWebView(
    pageUrl: String,
    webFilter: State<WebFilterConfig?>,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onPageChanged: (String?) -> Unit,
    onError: (AppError) -> Unit,
    onDownload: (InterceptedDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember { mutableStateOf<WebView?>(null) }

    // A WebView is a system View with a thread of its own: without `destroy()` on disposal it stays alive
    // as long as the process, still executing the page's JS.
    DisposableEffect(Unit) {
        onDispose {
            view.value?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // Many challenges write to `localStorage` before letting you through.
                settings.domStorageEnabled = true
                // No new windows, and none opened by JavaScript without a tap. Both are `WebSettings`
                // defaults, written here because on these pages they are a **defence** and not a default:
                // the pop-under is how a CPM network takes the user elsewhere, and a default nobody names is
                // a default somebody will change one day to make something else work.
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) =
                        onProgress(newProgress)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) =
                        onPageChanged(url)

                    /**
                     * The request does not go out at all.
                     *
                     * It is the only point at which a WebView can do what an ad blocker does: there is no
                     * Adblock Plus-style rule engine in here, and there will not be. Returning an empty
                     * response is cleaner than letting it fail — no connection, no DNS, no bytes.
                     *
                     * It runs on a **network thread**, not the main one: `WebFilterConfig` is immutable and
                     * its sets are computed in the constructor, so reading it from here is safe. The `State`
                     * is read on every request because the switch can change while the page is open.
                     */
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val host = request?.url?.host ?: return null
                        if (webFilter.value?.blocks(host) != true) return null
                        return BLOCKED
                    }

                    /**
                     * **Navigations** towards a blocked host do not happen.
                     *
                     * `shouldInterceptRequest` covers subresources; this covers the case that does the most
                     * damage on these pages — the redirect the page starts by itself, or the fake button
                     * that leads elsewhere. Without it, a wrong tap would take the WebView to a CPM
                     * network's page, and from there the `DownloadListener` would accept whatever that page
                     * offered as "the store's file".
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val host = request?.url?.host ?: return false
                        return webFilter.value?.blocks(host) == true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // The main document only: an advert that does not load is not a fault to inform the
                        // user about, and on these pages there are many.
                        if (request?.isForMainFrame == true) onError(AppError.Network(null))
                    }
                }
                setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    onDownload(
                        InterceptedDownload(
                            url = url,
                            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType),
                            userAgent = userAgent,
                            cookie = CookieManager.getInstance().getCookie(url),
                            referer = this.url,
                            mimeType = mimeType,
                            contentLength = contentLength,
                        ),
                    )
                }
                loadUrl(pageUrl)
                view.value = this
                onCreated(this)
            }
        },
    )
}

/**
 * The response a blocked request ends with: empty, immediately.
 *
 * `204` and not `403`: the page has to read it as "there is nothing here", not as "you are being
 * prevented from something". Some monitoring scripts retry on an error, and a retry is one more connection
 * to the same host.
 *
 * A single instance: it is returned from a network thread on every blocked request, and on these pages
 * there are dozens. A `ByteArrayInputStream` over an empty array has no state to share.
 */
private val BLOCKED = WebResourceResponse(
    "text/plain",
    "utf-8",
    204,
    "No Content",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
)

/** The store's hint, translated. `:store:api` is pure Kotlin and does not see `strings.xml`. */
private fun DownloadHint.instructionRes(): Int = when (this) {
    DownloadHint.TAP_DOWNLOAD_BUTTON -> R.string.webviewdownload_hint_tap_download
    DownloadHint.SOLVE_CAPTCHA -> R.string.webviewdownload_hint_solve_captcha
    DownloadHint.WAIT_FOR_COUNTDOWN -> R.string.webviewdownload_hint_wait_countdown
    DownloadHint.CHOOSE_A_MIRROR -> R.string.webviewdownload_hint_choose_mirror
    DownloadHint.ACCEPT_TERMS -> R.string.webviewdownload_hint_accept_terms
}

@Preview(name = "WebViewDownload light")
@Composable
private fun WebViewDownloadScreenLightPreview() {
    MultiStoreTheme(themeMode = ThemeMode.LIGHT) { WebViewDownloadPreviewContent() }
}

@Preview(name = "WebViewDownload dark")
@Composable
private fun WebViewDownloadScreenDarkPreview() {
    MultiStoreTheme(themeMode = ThemeMode.DARK) { WebViewDownloadPreviewContent() }
}

@Composable
private fun WebViewDownloadPreviewContent() {
    WebViewDownloadScreen(
        uiState = WebViewDownloadUiState(
            title = "Example",
            pageUrl = "https://example.test/app/download",
            hint = DownloadHint.SOLVE_CAPTCHA,
            pageProgress = 60,
            currentHost = "example.test",
        ),
        onBack = {},
        onDismissError = {},
    ) { contentModifier ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = contentModifier) {}
    }
}
