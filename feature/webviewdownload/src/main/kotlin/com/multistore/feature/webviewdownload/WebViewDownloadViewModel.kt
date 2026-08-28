package com.multistore.feature.webviewdownload

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.multistore.core.common.result.AppError
import com.multistore.core.data.repository.AppDetailRepository
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.VersionRef
import com.multistore.core.model.WebFilterConfig
import com.multistore.store.api.DownloadHint
import com.multistore.store.api.DownloadResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the WebView intercepted when the page really did start a download. */
data class InterceptedDownload(
    val url: String,
    val fileName: String,
    val userAgent: String?,
    /** **That** page's cookies: without them the CDN answers 403 to the second client. */
    val cookie: String?,
    val referer: String?,
    val mimeType: String?,
    val contentLength: Long?,
)

data class WebViewDownloadUiState(
    val title: String,
    val pageUrl: String,
    val hint: DownloadHint,
    /** 0–100, as `WebChromeClient` reports it. At 100 the bar disappears. */
    val pageProgress: Int = 0,
    /** The host currently being viewed: an assisted download goes through several hops. */
    val currentHost: String? = null,
    /** The download was intercepted and queued: the screen is done. */
    val handedOff: Boolean = false,
    val error: AppError? = null,
    /**
     * `false` when the user closed the instructions notice.
     *
     * It lives in the screen state and not in a `remember` because it has to survive rotation: a notice
     * that reappears when the phone is turned halfway through a captcha is worse than not having closed
     * it. It does not survive leaving, and rightly so — the notice says what to do, and on the next page
     * the question comes up again.
     */
    val hintVisible: Boolean = true,
    /**
     * The hosts this WebView must not load, or `null` if the filter is off.
     *
     * It lives in the state and not in a constant because **two** things change underneath: the switch in
     * Settings and the list arriving from the signed document. With a value captured at construction,
     * turning the filter off would have no effect until restart — the same defect already fixed on
     * scheduling and on the challenge strategy.
     */
    val webFilter: WebFilterConfig? = null,
)

/**
 * The assisted path: the store's real page, the user's real tap.
 *
 * The line is explicit about where it runs: **actually doing** what the site asks is legitimate;
 * **pretending** to have done it is not. This screen sits entirely on the legitimate side — it solves no
 * captchas, forges no TLS fingerprints, rotates no addresses. It opens a real browser engine on the real
 * page and waits for a person to press.
 *
 * What it adds over "open in the system browser" is the return: when the page starts the download, the
 * WebView **intercepts** it instead of leaving it to Android's download manager, and the file goes back
 * into MultiStore's queue. From there it goes through exactly the same verification pipeline as every
 * other store — size, SHA-256, `apksig`, `packageName`, signature, anti-downgrade. Without the return, an
 * APK taken from the browser would be the only one installed with no checks at all.
 */
@HiltViewModel
class WebViewDownloadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val installApp: InstallAppUseCase,
    private val details: AppDetailRepository,
    settings: SettingsRepository,
    /**
     * The list of hosts not to load, already merged with the signed document's override.
     *
     * Injected rather than built here: the merge is done by `RemoteParsers` in `:app`, the only point in
     * the project where remote config touches anything. Having it as a dependency also means being able to
     * replace it in a test without going through a signed document.
     */
    private val filter: WebFilterConfig,
) : ViewModel() {

    private val route: WebViewDownloadRoute = savedStateHandle.toRoute()
    private val storeId: StoreId? = route.storeIdOrNull()
    private val ref: StoreAppRef = route.appRef()
    private val versionRef: VersionRef = route.version()

    private val _uiState = MutableStateFlow(
        WebViewDownloadUiState(
            // Until the page is read, the title is the host: it is still more useful than an empty string,
            // and it tells the user where they are about to end up.
            title = route.pageUrl.hostOrNull().orEmpty(),
            pageUrl = route.pageUrl,
            hint = route.downloadHint(),
            currentHost = route.pageUrl.hostOrNull(),
        ),
    )
    val uiState: StateFlow<WebViewDownloadUiState> = _uiState.asStateFlow()

    /**
     * The id of the queued download. The screen uses it to go back.
     *
     * A `SharedFlow` and not a state field: it is an **event**, and keeping it in the state would restart
     * the navigation on every recomposition.
     */
    private val _handoff = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val handoff: SharedFlow<Long> = _handoff.asSharedFlow()

    init {
        viewModelScope.launch {
            val id = storeId ?: return@launch
            details.detail(id, ref)?.let { detail ->
                _uiState.update { it.copy(title = detail.listing.summary.title) }
            }
        }
        // The switch is **observed**, not read once: it is the same rule already applied to the challenge
        // strategy and to update scheduling — a value captured at startup makes a Settings entry that seems
        // to do nothing until the next restart.
        viewModelScope.launch {
            settings.network.collect { network ->
                _uiState.update { it.copy(webFilter = filter.takeUnless { network.allowWebAds }) }
            }
        }
    }

    /** Hides the instructions notice: it takes up space, and at some point it has been read. */
    fun dismissHint() {
        _uiState.update { it.copy(hintVisible = false) }
    }

    fun onPageProgress(progress: Int) {
        _uiState.update { it.copy(pageProgress = progress.coerceIn(0, 100)) }
    }

    fun onPageChanged(url: String?) {
        _uiState.update { it.copy(currentHost = url?.hostOrNull() ?: it.currentHost) }
    }

    fun onPageError(error: AppError) {
        _uiState.update { it.copy(error = error) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * The page started a download: we take it.
     *
     * The headers are not decoration. A download URL served after a challenge is nearly always tied to the
     * session: without the `Cookie` the WebView obtained, and without the same `User-Agent` it obtained it
     * with, the next request reaches the server as a different client and gets a 403. The `Referer` is for
     * the stores that check which page one arrives from.
     *
     * The `User-Agent` is **the WebView's**, not the one the adapter declares: that is the client that
     * actually made the request, and replacing it with another would be declaring a browser different from
     * the one we are running.
     */
    fun onDownloadIntercepted(download: InterceptedDownload) {
        val id = storeId ?: return
        if (_uiState.value.handedOff) return

        viewModelScope.launch {
            val packageName = details.detail(id, ref)?.listing?.summary?.packageName
            val downloadId = installApp.enqueueAssisted(
                storeId = id,
                ref = ref,
                versionRef = versionRef,
                packageName = packageName,
                resolution = DownloadResolution.Direct(
                    url = download.url,
                    headers = buildMap {
                        download.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                        download.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                        download.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                    },
                    fileName = download.fileName,
                    artifactType = artifactTypeOf(download.fileName),
                    // The store published no hash on this path: verification will compute it anyway and
                    // report it as "not contradicted". The check that stays non-negotiable — the
                    // `packageName` — does not depend on this.
                    expectedSha256 = null,
                    expectedSize = download.contentLength?.takeIf { it > 0 },
                ),
            )
            _uiState.update { it.copy(handedOff = true) }
            _handoff.emit(downloadId)
        }
    }

    private companion object {
        /**
         * The artifact type inferred from the extension, not from the MIME type.
         *
         * Servers distributing modified APKs almost always declare `application/octet-stream`, which does
         * not tell an APK from a bundle: the extension is the only thing that does. The value feeds
         * verification, which refuses anything that is not a single APK — so overestimating it would mean
         * handing a bundle to the `PackageInstaller` and watching it fail with an opaque error.
         */
        fun artifactTypeOf(fileName: String): ArtifactType = when {
            fileName.endsWith(".xapk", ignoreCase = true) -> ArtifactType.XAPK
            fileName.endsWith(".apkm", ignoreCase = true) -> ArtifactType.APKM
            fileName.endsWith(".apks", ignoreCase = true) -> ArtifactType.APKS
            else -> ArtifactType.APK
        }

        /** A URL's host, without dragging `android.net.Uri` into a testable place. */
        fun String.hostOrNull(): String? = runCatching { java.net.URI(this).host }.getOrNull()
    }
}
