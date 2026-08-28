package com.multistore.core.installer.shell

import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The half of [PrivilegedShell] that `su` and Shizuku have in common: a `java.lang.Process`.
 *
 * It is not a convenience: `ShizukuRemoteProcess` **extends** `java.lang.Process`, so the two roads
 * really differ only in the line that creates it. Everything else — draining the output while
 * writing the input, closing stdin, waiting for the exit code — is the same code, and getting it
 * wrong in one place is better than getting it wrong in two.
 */
abstract class ProcessShell(private val io: CoroutineDispatcher) : PrivilegedShell {

    /** Starts the command. It can throw: the caller treats that as a failed execution. */
    protected abstract fun start(command: String): Process

    override suspend fun exec(command: String, stdin: ((OutputStream) -> Unit)?): ShellResult =
        withContext(io) {
            val process = runCatching { start(command) }
                .getOrElse { return@withContext ShellResult(EXEC_FAILED, it.message.orEmpty()) }

            coroutineScope {
                // The two outputs are read **while** the input is written, not afterwards. A command
                // saying more than fits in the pipe's buffer would block writing while we block
                // writing its input, and neither would proceed: the classic deadlock of reading a
                // process sequentially.
                val out = async { runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("") }
                val err = async { runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("") }

                // `use` closes stdin even when there is nothing to write: without the close the
                // command waits for an EOF that never arrives.
                runCatching { process.outputStream.use { sink -> stdin?.invoke(sink) } }

                val exitCode = runCatching { process.waitFor() }.getOrDefault(EXEC_FAILED)
                ShellResult(exitCode, listOf(out.await(), err.await()).joinToString("\n").trim())
            }
        }

    protected companion object {
        /** Not a command's exit code: it is "the command did not even start". */
        const val EXEC_FAILED = -1
    }
}
