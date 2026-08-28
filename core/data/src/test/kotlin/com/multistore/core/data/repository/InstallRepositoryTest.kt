package com.multistore.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.installer.InstallProgress
import com.multistore.core.installer.InstallRequest
import com.multistore.core.installer.Installer
import com.multistore.core.installer.InstallerSelector
import com.multistore.core.installer.container.ContainerExtractor
import com.multistore.core.installer.container.ZipContainerReader
import com.multistore.core.installer.UninstallProgress
import com.multistore.core.installer.session.InstallSessionReconciler
import com.multistore.core.installer.verify.ApkArchiveInfo
import com.multistore.core.installer.verify.ApkArchiveReader
import com.multistore.core.installer.verify.ApkReadResult
import com.multistore.core.installer.verify.PreInstallVerifier
import com.multistore.core.model.DeviceProfile
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.InstallerPreference
import com.multistore.core.model.InstalledApp
import com.multistore.core.model.InstalledPackage
import com.multistore.core.model.Sha256
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Who installs, and who decides it.
 *
 * `InstallerSelectorTest` proves the **rule** — who wins the chain, when a silent request has no
 * answer. Here the **connection** is proven, which is the half that can vanish silently: that the
 * preference chosen in Settings really reaches the selector, and that `requireSilent` is not a field
 * nobody looks at.
 *
 * The second is a safety promise, not a convenience: if it falls, the periodic check opens the
 * system's confirmation screen from the background — where from API 34 it does not appear — and the
 * outcome is an installation that does not happen with nobody saying so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstallRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = LocalSettings()
    private val installedApps = RecordingInstalledApps()

    private val session = RecordingInstaller(InstallerKind.SESSION, silent = false)
    private val shizuku = RecordingInstaller(InstallerKind.SHIZUKU, silent = true)
    private val root = RecordingInstaller(InstallerKind.ROOT, silent = true)

    @Test
    fun `with no silent channel an unattended installation stops, instead of asking`() = runTest {
        val steps = repository(session).install(plan(requireSilent = true)).toList()

        assertThat(steps.last()).isEqualTo(InstallStep.SilentUnavailable)
        // What matters is the absence: nobody has opened the confirmation screen.
        assertThat(session.requests).isEmpty()
    }

    @Test
    fun `with a silent channel the unattended installation goes through it`() = runTest {
        val steps = repository(session, shizuku).install(plan(requireSilent = true)).toList()

        assertThat(shizuku.requests).hasSize(1)
        assertThat(session.requests).isEmpty()
        assertThat(steps.last()).isInstanceOf(InstallStep.Installed::class.java)
        // Which channel it came through stays written: it is what "My apps" shows.
        assertThat(installedApps.recorded).containsExactly(InstallerKind.SHIZUKU)
    }

    @Test
    fun `the preference chosen in Settings decides who installs`() = runTest {
        settings.installation.value = com.multistore.core.model.InstallSettings(InstallerPreference.SHIZUKU)

        repository(session, shizuku, root).install(plan()).toList()

        // Without reading the setting, root would win, being first in the chain: it is precisely that
        // which makes the test able to notice if that line disappeared.
        assertThat(shizuku.requests).hasSize(1)
        assertThat(root.requests).isEmpty()
    }

    @Test
    fun `a preference on the single plan beats the general one`() = runTest {
        settings.installation.value = com.multistore.core.model.InstallSettings(InstallerPreference.SHIZUKU)

        repository(session, shizuku, root).install(plan(preferred = InstallerKind.ROOT)).toList()

        assertThat(root.requests).hasSize(1)
        assertThat(shizuku.requests).isEmpty()
    }

    @Test
    fun `uninstalling goes through the preference too`() = runTest {
        settings.installation.value = com.multistore.core.model.InstallSettings(InstallerPreference.SHIZUKU)

        repository(session, shizuku, root).uninstall(PACKAGE).toList()

        // Whoever chose Shizuku chose it in order not to see confirmations, and an uninstall is a
        // confirmation like any other.
        assertThat(shizuku.uninstalled).containsExactly(PACKAGE)
        assertThat(root.uninstalled).isEmpty()
    }

    @Test
    fun `availability reaches the screen without going through InstallerSelector`() = runTest {
        val availability = repository(session, shizuku).installerAvailability()

        // `:feature:settings` does not see `:core:installer`: if this route closed, the "install by
        // itself" entry would stay on for a device that cannot install by itself.
        assertThat(availability.silent).containsExactly(InstallerKind.SHIZUKU)
        assertThat(availability.hasSilent).isTrue()
    }

    // --- scaffolding ------------------------------------------------------------------------

    private fun repository(vararg installers: Installer) = InstallRepositoryImpl(
        verifier = PreInstallVerifier(FakeReader()),
        selector = InstallerSelector(installers.toSet()),
        installedApps = installedApps,
        settings = settings,
        sessions = InstallSessionReconciler(context, UnconfinedTestDispatcher()),
        containers = ZipContainerReader(),
        extractor = ContainerExtractor(),
        device = DeviceProfile(sdkInt = 34, supportedAbis = listOf("arm64-v8a"), densityDpi = 420),
    )


    @Test
    fun `a plan with no store writes nothing in installed_apps`() = runTest {
        // It is MultiStore's own update, the only plan with no provenance. That table says "MultiStore
        // installed this app from this store, and will update it from there": a row for us would give
        // a channel pointing at a listing that does not exist, and MultiStore in "My apps" as though
        // it came from a catalogue.
        //
        // The fault would not show immediately — the installation succeeds all the same — but it would
        // show at the first periodic check, which would try to update us from a ref that does not
        // resolve.
        repository(session).install(plan(storeId = null, ref = null)).collect { }

        assertThat(installedApps.recorded).isEmpty()
        // And the installation **happened**: without this line the test would pass even if the plan
        // had been refused before reaching the installer, i.e. it would prove the wrong thing.
        assertThat(session.requests).hasSize(1)
    }

    // --- Split containers -----------------------------------------------------------------------

    private fun xapk(vararg entries: Pair<String, ByteArray>): File {
        val file = temporaryFolder.newFile("app.xapk")
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private val xapkManifest = """
        {"xapk_version":"2","package_name":"$PACKAGE","version_code":"2",
         "split_apks":[{"file":"$PACKAGE.apk","id":"base"},
                       {"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"},
                       {"file":"config.x86.apk","id":"config.x86"},
                       {"file":"config.xxhdpi.apk","id":"config.xxhdpi"}]}
    """.trimIndent().toByteArray()

    @Test
    fun `a container is opened, and only what the device needs enters the session`() = runTest {
        val container = xapk(
            "manifest.json" to xapkManifest,
            "$PACKAGE.apk" to ByteArray(40) { 1 },
            "config.arm64_v8a.apk" to ByteArray(30) { 2 },
            "config.x86.apk" to ByteArray(20) { 3 },
            "config.xxhdpi.apk" to ByteArray(10) { 4 },
        )

        val steps = repository(session).install(plan(apk = container)).toList()

        val request = session.requests.single()
        // A single session, and without `config.x86.apk`: the test device is arm64.
        assertThat(request.apks.map { it.name })
            .containsExactly("$PACKAGE.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk")
        assertThat(steps.filterIsInstance<InstallStep.Unpacking>().single().summary.skipped.map { it.entryName })
            .containsExactly("config.x86.apk")
        assertThat(steps.last()).isInstanceOf(InstallStep.Installed::class.java)
    }

    @Test
    fun `the extracted pieces do not stay in staging after the installation`() = runTest {
        val container = xapk(
            "manifest.json" to xapkManifest,
            "$PACKAGE.apk" to ByteArray(40) { 1 },
            "config.arm64_v8a.apk" to ByteArray(30) { 2 },
            "config.x86.apk" to ByteArray(20) { 3 },
            "config.xxhdpi.apk" to ByteArray(10) { 4 },
        )

        repository(session).install(plan(apk = container)).collect { }

        // They are derived data: the container is still there and reopening it costs less than
        // keeping a second copy — on Firefox that would be 250 MB.
        assertThat(Staging.splitsOf(container).exists()).isFalse()
    }

    @Test
    fun `a container with game data stops before extracting, if nobody can place them`() = runTest {
        val container = xapk(
            "manifest.json" to """
                {"xapk_version":"2","package_name":"$PACKAGE","version_code":"2",
                 "split_apks":[{"file":"$PACKAGE.apk","id":"base"}],
                 "expansions":[{"file":"main.2.$PACKAGE.obb",
                                "install_path":"Android/obb/$PACKAGE/main.2.$PACKAGE.obb"}]}
            """.trimIndent().toByteArray(),
            "$PACKAGE.apk" to ByteArray(40) { 1 },
            "main.2.$PACKAGE.obb" to ByteArray(64) { 9 },
        )

        val steps = repository(session).install(plan(apk = container)).toList()

        // `session` is not a privileged channel, and `Android/obb/<another package>` is not writable
        // by an app **even with `MANAGE_EXTERNAL_STORAGE`** — measured on API 36. Stopping here avoids
        // extracting nine hundred megabytes and leaving an installed game that does not start.
        assertThat((steps.last() as InstallStep.ContainerRejected).problem)
            .isEqualTo(ContainerProblem.ExpansionsNeedPrivilegedInstaller)
        assertThat(session.requests).isEmpty()
        assertThat(Staging.splitsOf(container).exists()).isFalse()
    }

    @Test
    fun `a container carrying only other architectures never reaches the installer`() = runTest {
        val container = xapk(
            "manifest.json" to """
                {"xapk_version":"2","package_name":"$PACKAGE","version_code":"2",
                 "split_apks":[{"file":"$PACKAGE.apk","id":"base"},
                               {"file":"config.x86.apk","id":"config.x86"}]}
            """.trimIndent().toByteArray(),
            "$PACKAGE.apk" to ByteArray(40) { 1 },
            "config.x86.apk" to ByteArray(20) { 3 },
        )

        val steps = repository(session).install(plan(apk = container)).toList()

        val problem = (steps.last() as InstallStep.ContainerRejected).problem
        assertThat((problem as ContainerProblem.IncompatibleAbi).available).containsExactly("x86")
        assertThat(session.requests).isEmpty()
    }

    private fun plan(
        requireSilent: Boolean = false,
        preferred: InstallerKind? = null,
        storeId: StoreId? = StoreId.FDROID,
        ref: StoreAppRef? = StoreAppRef(PACKAGE),
        apk: File = temporaryFolder.newFile("base.apk").apply { writeBytes(ByteArray(16)) },
    ) = InstallPlan(
        apk = apk,
        storeId = storeId,
        ref = ref,
        label = "Example",
        declaredPackageName = PACKAGE,
        expectedSha256 = null,
        expectedSizeBytes = null,
        expectedSignerSha256 = SIGNER,
        preferredInstaller = preferred,
        requireSilent = requireSilent,
    )

    /** It always reads the same APK, so verification passes and is not the test's subject. */
    private class FakeReader : ApkArchiveReader {
        override fun read(file: File): ApkReadResult = ApkReadResult.Readable(
            ApkArchiveInfo(
                packageName = PACKAGE,
                versionCode = 2,
                minSdk = 26,
                signerSha256 = listOf(SIGNER),
                signatureSchemes = setOf(2),
                fileSha256 = FILE_HASH,
                sizeBytes = file.length(),
            ),
        )
    }

    private class RecordingInstaller(
        override val kind: InstallerKind,
        silent: Boolean,
    ) : Installer {

        override val supportsSilent: Boolean = silent

        val requests = mutableListOf<InstallRequest>()
        val uninstalled = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override fun install(request: InstallRequest): Flow<InstallProgress> {
            requests += request
            return flowOf(InstallProgress.Installed)
        }

        override fun uninstall(packageName: String): Flow<UninstallProgress> {
            uninstalled += packageName
            return flowOf(UninstallProgress.Uninstalled)
        }
    }

    /** The minimum to record an installation, and remember which channel it came through. */
    private class RecordingInstalledApps : InstalledAppsRepository {
        val recorded = mutableListOf<InstallerKind>()

        override fun observe(): Flow<List<InstalledApp>> = emptyFlow()
        override suspend fun get(packageName: String): InstalledApp? = null
        override suspend fun all(): List<InstalledApp> = emptyList()
        override suspend fun forListing(storeId: StoreId, ref: StoreAppRef): InstalledApp? = null

        /** `null`: the package is not installed, so the signature is compared with the store's. */
        override suspend fun installedPackage(packageName: String): InstalledPackage? = null

        override suspend fun reconcile() = Unit

        override suspend fun record(
            packageName: String,
            label: String,
            storeId: StoreId,
            ref: StoreAppRef,
            listingId: Long?,
            apkSha256: Sha256?,
            installerKind: InstallerKind,
        ) {
            recorded += installerKind
        }

        override suspend fun forget(packageName: String) = Unit
        override suspend fun setIgnoreUpdates(packageName: String, ignore: Boolean) = Unit
        override suspend fun setPinnedVersionCode(packageName: String, versionCode: Long?) = Unit
        override suspend fun setUpdateChannel(
            packageName: String,
            storeId: StoreId,
            ref: StoreAppRef,
        ): Boolean = true
    }

    private companion object {
        const val PACKAGE = "org.example.app"
        val SIGNER = requireNotNull(
            Sha256.parseOrNull("11".repeat(32)),
        )
        val FILE_HASH = requireNotNull(
            Sha256.parseOrNull("22".repeat(32)),
        )
    }
}
