package com.multistore.feature.webviewdownload

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.version.VersionSelection
import com.multistore.core.data.repository.AppDetail
import com.multistore.core.data.store.StoreRegistry
import com.multistore.core.domain.usecase.ActiveInstallDrivers
import com.multistore.core.domain.usecase.InstallAppUseCase
import com.multistore.core.domain.usecase.ResolveDownloadUseCase
import com.multistore.core.model.AppVersion
import com.multistore.core.model.ArtifactType
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.core.model.StoreListingDetail
import com.multistore.core.model.StoreListingSummary
import com.multistore.core.model.VersionRef
import com.multistore.core.testing.FakeSettingsRepository
import com.multistore.core.testing.FakeAppDetailRepository
import com.multistore.core.testing.FakeDownloadRepository
import com.multistore.core.testing.FakeInstallRepository
import com.multistore.core.testing.FakeStoreAdapter
import com.multistore.core.testing.MainDispatcherRule
import com.multistore.core.model.WebFilterConfig
import com.multistore.store.api.DownloadHint
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The assisted path's return.
 *
 * The screen exists for one reason, and this is it: with the system browser the APK lands in the Downloads
 * folder and Android installs it, **without** any of the pipeline's checks having run. By intercepting the
 * download here, the file goes back into MultiStore's queue and crosses the same verification as the other
 * eight stores.
 *
 * What needs testing is not the WebView — that is Chromium — but the translation from "the page started a
 * download" to "a queued row with the right headers".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WebViewDownloadViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val details = FakeAppDetailRepository(anAppDetail())
    private val downloads = FakeDownloadRepository()
    private val installs = FakeInstallRepository()
    private val settings = FakeSettingsRepository()

    private fun viewModel(pageUrl: String = PAGE_URL): WebViewDownloadViewModel {
        val registry = StoreRegistry(setOf(FakeStoreAdapter()))
        return WebViewDownloadViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "storeId" to StoreId.FDROID.wireName,
                    "ref" to REF.value,
                    "versionRef" to VERSION.ref.value,
                    "pageUrl" to pageUrl,
                    "hint" to DownloadHint.SOLVE_CAPTCHA.name,
                ),
            ),
            installApp = InstallAppUseCase(
                resolve = ResolveDownloadUseCase(registry, details, FakeSettingsRepository()),
                downloads = downloads,
                installs = installs,
                details = details,
                settings = FakeSettingsRepository(),
                drivers = ActiveInstallDrivers(),
            ),
            details = details,
            settings = settings,
            filter = WebFilterConfig(),
        )
    }

    @Test
    fun `the filter is on by default and goes away when the user switches it off`() = runTest(dispatcher) {
        val viewModel = viewModel()

        // The zero value of `allow_web_ads` is "do not let adverts through": the filter has to be there
        // without anybody having touched anything.
        assertThat(viewModel.uiState.value.webFilter).isNotNull()

        settings.setAllowWebAds(true)

        // And the switch is **observed**: captured at construction, turning it off would have no effect on
        // an already-open screen, which is exactly when it gets turned off.
        assertThat(viewModel.uiState.value.webFilter).isNull()
    }

    @Test
    fun `the notice closes and stays closed`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.uiState.value.hintVisible).isTrue()

        viewModel.dismissHint()

        assertThat(viewModel.uiState.value.hintVisible).isFalse()
    }

    @Test
    fun `the intercepted download is queued with cookie, user agent and referer`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onDownloadIntercepted(intercepted())

        val row = downloads.active.value.single()
        assertThat(row.packageName).isEqualTo(PACKAGE)
        assertThat(row.versionRef).isEqualTo(VERSION.ref)
        // The headers are not decoration: a URL served after a challenge is tied to the session, and
        // without the cookie the WebView obtained — and the same UA it obtained it with — the next request
        // reaches the server as a different client and gets a 403.
        val resolution = downloads.resolutions.single()
        assertThat(resolution.headers).containsEntry("Cookie", "cf_clearance=abc")
        assertThat(resolution.headers).containsEntry("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        assertThat(resolution.headers).containsEntry("Referer", PAGE_URL)
        assertThat(resolution.expectedSize).isEqualTo(SIZE)
        // The worker starts at once: the file is there and the session authorising it expires.
        assertThat(downloads.started).containsExactly(row.id)
    }

    @Test
    fun `with no cookie and no user agent no header is invented`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onDownloadIntercepted(intercepted(cookie = null, userAgent = null))

        // An empty header is not neutral: `Cookie:` with no value differs from no `Cookie` at all, and some
        // servers answer 400. What the WebView did not give is not sent.
        assertThat(downloads.resolutions.single().headers.keys).containsExactly("Referer")
    }

    @Test
    fun `a second intercepted download does not queue twice`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onDownloadIntercepted(intercepted())
        viewModel.onDownloadIntercepted(intercepted())

        // Some pages start the download twice — an `<a download>` plus a redirect — and two rows for the
        // same file would overwrite each other in staging.
        assertThat(downloads.active.value).hasSize(1)
        assertThat(viewModel.uiState.value.handedOff).isTrue()
    }

    @Test
    fun `the artifact type comes from the extension, not from the declared MIME`() = runTest(dispatcher) {
        val viewModel = viewModel()

        // Servers distributing modified APKs almost always declare `application/octet-stream`, which does
        // not tell an APK from a bundle.
        viewModel.onDownloadIntercepted(intercepted(fileName = "game.xapk"))

        assertThat(downloads.resolutions.single().artifactType).isEqualTo(ArtifactType.XAPK)
    }

    @Test
    fun `the title comes from the listing, the host from the page`() = runTest(dispatcher) {
        val state = viewModel().uiState.value

        assertThat(state.title).isEqualTo("Example")
        // The host is not a technical detail: an assisted download crosses several redirects, and without
        // seeing it the user no longer knows which site they are tapping on.
        assertThat(state.currentHost).isEqualTo("example.test")
        assertThat(state.hint).isEqualTo(DownloadHint.SOLVE_CAPTCHA)
    }

    @Test
    fun `the page progress stays within bounds`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onPageProgress(140)

        assertThat(viewModel.uiState.value.pageProgress).isEqualTo(100)
    }

    private fun intercepted(
        fileName: String = "app.apk",
        cookie: String? = "cf_clearance=abc",
        userAgent: String? = "Mozilla/5.0 (Linux; Android 14)",
    ) = InterceptedDownload(
        url = "https://cdn.example.test/$fileName",
        fileName = fileName,
        userAgent = userAgent,
        cookie = cookie,
        referer = PAGE_URL,
        mimeType = "application/octet-stream",
        contentLength = SIZE,
    )

    private fun anAppDetail() = AppDetail(
        listing = StoreListingDetail(
            summary = StoreListingSummary(
                storeId = StoreId.FDROID,
                ref = REF,
                title = "Example",
                packageName = PACKAGE,
            ),
            versions = listOf(VERSION),
        ),
        installed = null,
        selection = VersionSelection.Outcome.Offer(VERSION, isUpdate = false),
        stale = false,
    )

    private companion object {
        const val PACKAGE = "org.example.app"
        const val PAGE_URL = "https://example.test/app/download"
        const val SIZE = 7_000_000L
        val REF = StoreAppRef(PACKAGE)
        val VERSION = AppVersion(
            versionName = "1.2.3",
            versionCode = 12,
            ref = VersionRef("v12"),
            sizeBytes = SIZE,
            artifactType = ArtifactType.APK,
        )
    }
}
