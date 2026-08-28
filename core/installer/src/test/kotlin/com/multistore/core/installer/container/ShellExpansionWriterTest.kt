package com.multistore.core.installer.container

import com.google.common.truth.Truth.assertThat
import com.multistore.core.installer.shell.PrivilegedShell
import com.multistore.core.installer.shell.ShellResult
import com.multistore.core.model.BundlePart
import com.multistore.core.model.Sha256
import com.multistore.core.model.SplitKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where game data ends up, and what it passes through.
 *
 * The channel is the privileged shell and not the app, and the reason is measured: on Android 16,
 * with `MANAGE_EXTERNAL_STORAGE` **granted** and `isExternalStorageManager()` answering `true`,
 * `Android/obb/<another package>` still gives `mkdirs = false`. See [ExpansionWriter].
 */
class ShellExpansionWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val shell = RecordingShell()
    private val writer = ShellExpansionWriter(shell)

    private fun expansion(name: String, bytes: ByteArray) = ExtractedPart(
        part = BundlePart(name, SplitKind.EXPANSION, bytes.size.toLong()),
        file = File(folder.root, name).apply { writeBytes(bytes) },
        sha256 = Sha256.ofBytes(ByteArray(32)),
    )

    @Test
    fun `the directory is decided by the verified package, not by the container`() = runTest {
        val payload = ByteArray(16) { 7 }

        val result = writer.place(
            packageName = "com.rockstargames.gtactw",
            expansions = listOf(expansion("main.4.com.rockstargames.gtactw.obb", payload)),
        )

        assertThat(shell.commands).containsExactly(
            "mkdir -p /sdcard/Android/obb/com.rockstargames.gtactw",
            "cat > /sdcard/Android/obb/com.rockstargames.gtactw/main.4.com.rockstargames.gtactw.obb",
        ).inOrder()
        // We write the bytes ourselves to stdin, as for the APK: the extracted file sits in
        // `filesDir`, which the `shell` user cannot read, and copying it elsewhere would put it in
        // the clear precisely between extraction and writing.
        assertThat(shell.stdin).isEqualTo(payload.toList())
        assertThat(result).isEqualTo(ExpansionResult.Placed(files = 1, bytes = payload.size.toLong()))
    }

    @Test
    fun `a package that is not a package name never reaches the shell`() = runTest {
        val result = writer.place("com.example; rm -rf /sdcard", listOf(expansion("main.1.a.obb", ByteArray(4))))

        assertThat(shell.commands).isEmpty()
        assertThat(result).isInstanceOf(ExpansionResult.Failed::class.java)
    }

    @Test
    fun `a refused mkdir does not try to write`() = runTest {
        shell.responses["mkdir"] = ShellResult(1, "Permission denied")

        val result = writer.place("com.example", listOf(expansion("main.1.com.example.obb", ByteArray(4))))

        assertThat(shell.commands).hasSize(1)
        assertThat((result as ExpansionResult.Failed).reason).contains("Permission denied")
    }

    @Test
    fun `with no expansions the shell is not touched`() = runTest {
        val result = writer.place("com.example", emptyList())

        assertThat(shell.commands).isEmpty()
        assertThat(result).isEqualTo(ExpansionResult.Placed(files = 0, bytes = 0))
    }

    private class RecordingShell : PrivilegedShell {

        override val kind = com.multistore.core.model.InstallerKind.SHIZUKU

        val commands = mutableListOf<String>()
        val stdin = mutableListOf<Byte>()
        val responses = mutableMapOf<String, ShellResult>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun requestPermission(): Boolean = true

        override suspend fun exec(command: String, stdin: ((OutputStream) -> Unit)?): ShellResult {
            commands += command
            responses.entries.firstOrNull { command.startsWith(it.key) }?.let { return it.value }
            if (stdin != null) {
                val sink = ByteArrayOutputStream()
                stdin(sink)
                this.stdin += sink.toByteArray().toList()
            }
            return ShellResult(0, "")
        }
    }
}
