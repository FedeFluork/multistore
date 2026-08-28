package com.multistore.core.installer.shell

import com.google.common.truth.Truth.assertThat
import com.multistore.core.installer.InstallProgress
import com.multistore.core.installer.InstallRequest
import com.multistore.core.installer.StagedApk
import com.multistore.core.installer.UninstallProgress
import com.multistore.core.model.InstallerKind
import com.multistore.core.model.Sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The protocol that talks to `pm`, tested without `pm`.
 *
 * It is the only half of the silent installer that can be tested anywhere: `su` and Shizuku exist on
 * no emulator image, and on a real device they would not answer a unit test. What lives here, though,
 * is also the half that can go wrong in ways nobody would notice — a session abandoned where a commit
 * was needed, a hash compared after writing instead of during, a package name interpolated without
 * looking at it.
 */
class ShellInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val shell = RecordingShell()

    private fun installer(installerPackageName: String = OWN_PACKAGE) =
        ShellInstaller(shell, installerPackageName)

    private fun apk(bytes: ByteArray = APK_BYTES): File =
        temporaryFolder.newFile("base.apk").apply { writeBytes(bytes) }

    private fun request(
        file: File = apk(),
        expectedSha256: Sha256 = Sha256.ofBytes(MessageDigest.getInstance("SHA-256").digest(file.readBytes())),
        packageName: String = "org.fdroid.fdroid",
    ) = InstallRequest(
        packageName = packageName,
        apks = listOf(StagedApk(file.name, file, expectedSha256)),
        label = "F-Droid",
    )

    @Test
    fun `the session is created, receives the bytes and is committed`() = runTest {
        val steps = installer().install(request()).toList()

        assertThat(shell.commands).containsExactly(
            "pm install-create -r -S ${APK_BYTES.size} -i $OWN_PACKAGE",
            "pm install-write -S ${APK_BYTES.size} $SESSION_ID base.apk -",
            "pm install-commit $SESSION_ID",
        ).inOrder()
        // It is not enough that the commands are the right ones: the APK must really have gone
        // through there. With `filesDir` private to the app, Shizuku's `shell` user could not read
        // the file, and a path instead of the bytes would give a "Success" on an empty session.
        assertThat(shell.stdin).isEqualTo(APK_BYTES.toList())
        assertThat(steps.last()).isEqualTo(InstallProgress.Installed)
    }

    @Test
    fun `progress arrives while the bytes go out, not at the end`() = runTest {
        val steps = installer().install(request()).toList()

        val written = steps.filterIsInstance<InstallProgress.Writing>()
        assertThat(written).isNotEmpty()
        assertThat(written.last().bytesWritten).isEqualTo(APK_BYTES.size.toLong())
        assertThat(written.last().bytesTotal).isEqualTo(APK_BYTES.size.toLong())
    }

    @Test
    fun `the package's installer of record is us, not the shell`() = runTest {
        installer(installerPackageName = "com.multistore.debug").install(request()).toList()

        // Without `-i`, the installer of record is `com.android.shell`: "My apps" would say we did
        // not install that package, and the update channel would not exist.
        assertThat(shell.commands.first()).contains("-i com.multistore.debug")
    }

    @Test
    fun `a hash different from the verified one abandons the session instead of committing it`() = runTest {
        val steps = installer().install(request(expectedSha256 = checkNotNull(Sha256.parseOrNull(OTHER_HASH)))).toList()

        assertThat(shell.commands).contains("pm install-abandon $SESSION_ID")
        // What matters is the absence: the bytes are already inside the session, and all that
        // separates them from installation is the commit that is not made.
        assertThat(shell.commands.none { it.startsWith("pm install-commit") }).isTrue()
        assertThat(steps.last()).isInstanceOf(InstallProgress.Failed::class.java)
    }

    @Test
    fun `a matching hash commits`() = runTest {
        val steps = installer().install(request(expectedSha256 = Sha256.ofBytes(sha256(APK_BYTES)))).toList()

        assertThat(shell.commands).contains("pm install-commit $SESSION_ID")
        assertThat(steps.last()).isEqualTo(InstallProgress.Installed)
    }

    @Test
    fun `a creation with no session id writes nothing`() = runTest {
        shell.responses["pm install-create"] = ShellResult(0, "Success")

        val steps = installer().install(request()).toList()

        assertThat(shell.commands).hasSize(1)
        assertThat(steps.last()).isInstanceOf(InstallProgress.Failed::class.java)
    }

    @Test
    fun `a refused write abandons the session`() = runTest {
        shell.responses["pm install-write"] = ShellResult(1, "Error: INSTALL_FAILED_INSUFFICIENT_STORAGE")

        val steps = installer().install(request()).toList()

        assertThat(shell.commands).contains("pm install-abandon $SESSION_ID")
        assertThat(shell.commands.none { it.startsWith("pm install-commit") }).isTrue()
        assertThat((steps.last() as InstallProgress.Failed).message)
            .contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")
    }

    @Test
    fun `a refused commit does not re-abandon an already closed session`() = runTest {
        shell.responses["pm install-commit"] = ShellResult(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")

        val steps = installer().install(request()).toList()

        assertThat(shell.commands.none { it.startsWith("pm install-abandon") }).isTrue()
        assertThat((steps.last() as InstallProgress.Failed).message)
            .contains("INSTALL_FAILED_VERSION_DOWNGRADE")
    }

    @Test
    fun `a package name that is not a package name never reaches the shell`() = runTest {
        val steps = installer().uninstall("org.fdroid.fdroid; rm -rf /data").toList()

        // The names come from HTML downloaded from a store: a shell line built by interpolation is
        // the wrong place to trust a remote string. The check takes nothing legitimate away — the
        // alphabet of Android package names is already this one.
        assertThat(shell.commands).isEmpty()
        assertThat(steps.last()).isInstanceOf(UninstallProgress.Failed::class.java)
    }

    @Test
    fun `a malformed installer of record never reaches the shell`() = runTest {
        val steps = installer(installerPackageName = "com.multistore\$(id)").install(request()).toList()

        assertThat(shell.commands).isEmpty()
        assertThat(steps.last()).isInstanceOf(InstallProgress.Failed::class.java)
    }

    @Test
    fun `a successful and a refused uninstall are told apart`() = runTest {
        assertThat(installer().uninstall("org.fdroid.fdroid").toList().last())
            .isEqualTo(UninstallProgress.Uninstalled)

        shell.responses["pm uninstall"] = ShellResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]")
        val failed = installer().uninstall("org.fdroid.fdroid").toList().last()
        assertThat((failed as UninstallProgress.Failed).message).contains("DELETE_FAILED_INTERNAL_ERROR")
    }

    // --- Split containers -----------------------------------------------------------------------

    private fun staged(name: String, bytes: ByteArray): StagedApk {
        val file = File(temporaryFolder.root, name).apply { writeBytes(bytes) }
        return StagedApk(name, file, Sha256.ofBytes(sha256(bytes)))
    }

    @Test
    fun `base and splits enter the same session, one write each`() = runTest {
        val base = ByteArray(30) { 1 }
        val abi = ByteArray(20) { 2 }
        val dpi = ByteArray(10) { 3 }
        val request = InstallRequest(
            packageName = "com.duolingo",
            apks = listOf(
                staged("base.apk", base),
                staged("split_config.arm64_v8a.apk", abi),
                staged("split_config.xxhdpi.apk", dpi),
            ),
            label = "Duolingo",
        )

        val steps = installer().install(request).toList()

        // **One** session, with the sum in `-S`: base and splits separately would be two
        // installations, and the system would refuse the second.
        assertThat(shell.commands).containsExactly(
            "pm install-create -r -S 60 -i $OWN_PACKAGE",
            "pm install-write -S 30 $SESSION_ID base.apk -",
            "pm install-write -S 20 $SESSION_ID split_config.arm64_v8a.apk -",
            "pm install-write -S 10 $SESSION_ID split_config.xxhdpi.apk -",
            "pm install-commit $SESSION_ID",
        ).inOrder()
        // The names must stay **different** from each other: two writes with the same name are one
        // overwriting the other, and the outcome is an app missing a split with no error at all.
        assertThat(shell.stdin).isEqualTo((base + abi + dpi).toList())
        assertThat(steps.last()).isEqualTo(InstallProgress.Installed)
    }

    @Test
    fun `a wrong digest on a split abandons before writing the rest`() = runTest {
        val request = InstallRequest(
            packageName = "com.duolingo",
            apks = listOf(
                staged("base.apk", ByteArray(30) { 1 }),
                staged("split_config.arm64_v8a.apk", ByteArray(20) { 2 })
                    .copy(sha256 = checkNotNull(Sha256.parseOrNull(OTHER_HASH))),
                staged("split_config.xxhdpi.apk", ByteArray(10) { 3 }),
            ),
        )

        val steps = installer().install(request).toList()

        // The third write must not start: on a real container that would be hundreds of megabytes
        // spent on a session that is abandoned anyway.
        assertThat(shell.commands.none { it.contains("split_config.xxhdpi.apk") }).isTrue()
        assertThat(shell.commands).contains("pm install-abandon $SESSION_ID")
        assertThat((steps.last() as InstallProgress.Failed).message)
            .contains("split_config.arm64_v8a.apk")
    }

    @Test
    fun `a malformed entry name never reaches the shell`() = runTest {
        val request = InstallRequest(
            packageName = "com.duolingo",
            apks = listOf(staged("base.apk", ByteArray(4)).copy(name = "base.apk; rm -rf /data")),
        )

        val steps = installer().install(request).toList()

        // The entry names are written by the store inside the container, and end up interpolated into
        // a shell line. The alphabet is wider than package names' — `+` and `-` appear in real file
        // names — but it is still an alphabet.
        assertThat(shell.commands.none { it.contains("rm -rf") }).isTrue()
        assertThat(steps.last()).isInstanceOf(InstallProgress.Failed::class.java)
    }

    /**
     * A shell that runs nothing and remembers everything.
     *
     * It answers the way `pm` really answers — "Success: created install session [1234567]" — because
     * we extract the session id from that line, and an invented answer would prove our regex against
     * itself.
     */
    private class RecordingShell : PrivilegedShell {

        override val kind: InstallerKind = InstallerKind.SHIZUKU

        val commands = mutableListOf<String>()
        val stdin = mutableListOf<Byte>()

        /** Key = the command's prefix. */
        val responses = mutableMapOf<String, ShellResult>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun requestPermission(): Boolean = true

        override suspend fun exec(command: String, stdin: ((OutputStream) -> Unit)?): ShellResult {
            commands += command
            if (stdin != null) {
                val sink = ByteArrayOutputStream()
                stdin(sink)
                this.stdin += sink.toByteArray().toList()
            }
            responses.entries.firstOrNull { command.startsWith(it.key) }?.let { return it.value }
            return when {
                command.startsWith("pm install-create") ->
                    ShellResult(0, "Success: created install session [$SESSION_ID]")

                command.startsWith("pm install-write") ->
                    ShellResult(0, "Success: streamed ${this.stdin.size} bytes")

                else -> ShellResult(0, "Success")
            }
        }
    }

    private companion object {
        const val OWN_PACKAGE = "com.multistore"
        const val SESSION_ID = 1_234_567L

        /** Longer than a buffer is unnecessary: the copy loop is the same at 8 KB and at 80 MB. */
        val APK_BYTES = ByteArray(200_000) { (it % 251).toByte() }

        /** A valid hash that is not the APK's. */
        const val OTHER_HASH = "0000000000000000000000000000000000000000000000000000000000000001"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
