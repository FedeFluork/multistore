package com.multistore.core.installer.shell

import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.model.InstallerKind
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `su` channel.
 *
 * ### Why availability is measured by running, and what it costs
 *
 * There is no way of asking a root manager "would you give me permission, if I asked?" without
 * asking. The only true answer is the outcome of a `su -c id`, and on a rooted device that line can
 * bring up Magisk's dialog.
 *
 * The choice is therefore between three imperfect things, and this is the least bad:
 *
 *  1. **never try** until the user chooses "root" in Settings → after every app restart the chosen
 *     channel would stop working, because the probe's result lives in memory;
 *  2. **try on every installation** → a root dialog every time, which is exactly what nobody wants;
 *  3. **try once per process, and only if a `su` binary really exists** → on a non-rooted device
 *     (the vast majority, and every emulator image) the cost is five `File.exists()` and no process;
 *     on a rooted one it is a `su -c id`, which the root manager answers from its own memory after
 *     the first time.
 *
 * The dialog, when it appears, appears **once in the app's lifetime** and not at every launch:
 * Magisk remembers both consent and refusal.
 */
@Singleton
class RootShell @Inject constructor(
    @param:IoDispatcher io: CoroutineDispatcher,
) : ProcessShell(io) {

    override val kind: InstallerKind = InstallerKind.ROOT

    private val probeLock = Mutex()

    /** `null` until it has been tried. It lives as long as the process: a restart retries. */
    private var granted: Boolean? = null

    override fun start(command: String): Process = ProcessBuilder("su", "-c", command).start()

    override suspend fun isAvailable(): Boolean = probeLock.withLock {
        granted ?: probe().also { granted = it }
    }

    /**
     * Really retries, ignoring the previous outcome.
     *
     * It serves the case where the user refused by mistake and then chooses "root" in Settings:
     * without it, the cached `false` would make that entry inert until the app's next launch.
     */
    override suspend fun requestPermission(): Boolean = probeLock.withLock {
        probe().also { granted = it }
    }

    private suspend fun probe(): Boolean {
        // The cheap check first: without a `su` binary there is nobody to ask anything of, and this
        // is the road every non-rooted device takes.
        if (SU_PATHS.none { runCatching { File(it).canExecute() }.getOrDefault(false) }) return false
        val result = exec("id")
        // root's `id` prints `uid=0(root)`. The exit code alone is not enough: some managers answer
        // 0 to a command they never ran.
        return result.ok && result.output.contains("uid=0")
    }

    private companion object {
        /** The places a root manager puts its own binary. */
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
    }
}
