package com.multistore.core.domain.usecase

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which downloads a screen is currently driving towards an installation.
 *
 * ### Why it exists
 *
 * Since M1 the transfer lives in a worker and **survives the screen**: leaving a listing does not
 * throw eighteen megabytes away. What does not survive is the rest of the path — the flow that
 * waits for the file and hands it to the installer runs in the listing's scope, so leaving means
 * the APK lands in staging and nobody installs it. That is the behaviour
 * `auto_install_after_download` lets the user change, and changing it needs a second candidate: a
 * coordinator in the shell that watches every transfer and carries on the ones a screen has
 * abandoned.
 *
 * Two candidates for the same file is two `PackageInstaller` sessions and two confirmation dialogs
 * for one app. This is the register that stops it: [InstallAppUseCase] declares a download while it
 * is driving it, and the coordinator skips whatever is declared.
 *
 * ### In memory, and it is right that it should be
 *
 * A screen cannot drive anything across a process death, so the truth this holds cannot outlive the
 * process either. Persisting it would create the opposite fault — a download marked as driven by a
 * screen that no longer exists, which nobody would ever carry on.
 *
 * ### It is not the whole defence
 *
 * There is a window of microseconds between the coordinator reading this register and the driver
 * deregistering in its `finally`. What settles that is not this class but `pending_install`, the
 * claim token in the row, which SQLite decides. This register is the deterministic half — while a
 * listing is on screen the coordinator never even looks — and the token is the exact half.
 *
 * An [AtomicReference] over an immutable set rather than a lock: the writes are two per download
 * and the reads are one per row of a list that redraws while it scrolls.
 */
@Singleton
class ActiveInstallDrivers @Inject constructor() {

    private val driven = AtomicReference<Set<Long>>(emptySet())

    /** `true` while a screen is carrying [downloadId] towards its installation. */
    fun isDriven(downloadId: Long): Boolean = downloadId in driven.get()

    internal fun acquire(downloadId: Long) {
        driven.updateAndGet { it + downloadId }
    }

    internal fun release(downloadId: Long) {
        driven.updateAndGet { it - downloadId }
    }
}
