package com.multistore.core.installer.shell

import android.content.pm.PackageManager
import com.multistore.core.common.coroutine.IoDispatcher
import com.multistore.core.model.InstallerKind
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

/**
 * The Shizuku channel: commands run by the `shell` user (uid 2000), which has `INSTALL_PACKAGES`.
 *
 * ### Why availability here can be asked without disturbing anyone
 *
 * Unlike `su`, Shizuku knows how to answer "am I on?" and "have you already granted it?" without
 * showing anything: `pingBinder()` and `checkSelfPermission()`. It is the reason this channel can
 * enter the automatic chain and root cannot — see the note in [RootShell].
 *
 * ### Why reflection, and why it is the only one
 *
 * Shizuku 13's public API exposes the binder and the permissions, but not a way of starting a
 * process: `Shizuku.newProcess` exists and is what the service uses, but it is `private`. The two
 * alternatives are worse: wrapping `IPackageInstaller` in `ShizukuBinderWrapper` requires the hidden
 * API stubs — another dependency, and a contract that changes with every Android release — whereas
 * `pm` is a command stable since API 21.
 *
 * `ShizukuRemoteProcess` extends `java.lang.Process`, so from here on the code is the same as `su`'s.
 *
 * **R8:** the rule keeping this method lives in `core/installer/consumer-rules.pro`, i.e. in the
 * module that depends on Shizuku. Without it, `getDeclaredMethod` looks for a name that no longer
 * exists and the channel comes out absent in the only build users install.
 */
@Singleton
class ShizukuShell @Inject constructor(
    @param:IoDispatcher io: CoroutineDispatcher,
) : ProcessShell(io) {

    override val kind: InstallerKind = InstallerKind.SHIZUKU

    override fun start(command: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

    override suspend fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shows Shizuku's dialog and waits for the answer.
     *
     * It must be called with the app in the foreground: it is a real window, and from a worker it
     * would not appear. The caller is the Settings screen.
     */
    override suspend fun requestPermission(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        if (isAvailable()) return true

        return suspendCancellableCoroutine { continuation ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (continuation.isActive) {
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                .onFailure {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }

    private companion object {

        /** Arbitrary, but it has to be stable: the listener compares it with what it receives. */
        const val REQUEST_CODE = 0x5A1D

        val newProcess: Method by lazy {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }
    }
}
