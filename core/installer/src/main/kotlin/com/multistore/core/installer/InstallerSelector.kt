package com.multistore.core.installer

import com.multistore.core.installer.container.ExpansionWriter
import com.multistore.core.model.InstallerAvailability
import com.multistore.core.model.InstallerKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses which installer to proceed with.
 *
 * The chain is `ROOT -> SHIZUKU -> SESSION`, and the last link is always available: it is what makes
 * true the promise that the app works fully with no setup at all.
 *
 * ### Two different questions, and why it is not a boolean parameter
 *
 * "Who installs this app?" and "who installs it **without asking the user anything**?" have answers
 * of different types, not merely of different values. The first cannot fail — there is always
 * `SessionInstaller` — and indeed [select] does not return `null`. The second may well have no
 * answer, and that is why [selectSilent] is nullable.
 *
 * The user of it is the periodic check: with no UI in the foreground, an installer opening the
 * system's confirmation screen installs nothing — from API 34 that activity does not even start from
 * background — and degrading silently would mean a session left open and a user who saw nothing.
 * Better to say no and leave the update in the list.
 */
@Singleton
class InstallerSelector @Inject constructor(
    private val installers: Set<@JvmSuppressWildcards Installer>,
) {

    /** The order of preference: silent if there is one, otherwise the one that asks for confirmation. */
    private val chain = listOf(InstallerKind.ROOT, InstallerKind.SHIZUKU, InstallerKind.SESSION)

    /**
     * The installer to use, whatever happens.
     *
     * @param preferred the installer the user asked for. If it is not available we descend the chain
     * rather than fail: preferring is not demanding.
     */
    suspend fun select(preferred: InstallerKind? = null): Installer =
        pick(preferred, silentOnly = false)
            ?: error(
                "No installer available. This should not be possible: SessionInstaller always " +
                    "answers available, so reaching here means Hilt's graph is not registering " +
                    "it.",
            )

    /**
     * The installer to use when there is nobody to show a confirmation to. `null` if there is none.
     *
     * An explicit preference for a **non**-silent installer counts as `null`, and not as "then pick
     * another one". The two only look alike from outside: whoever chose the system confirmation asked
     * to see it, and installing silently on their behalf because nobody happened to be watching is
     * exactly what they did not ask for. If instead the chosen channel is silent but off — Shizuku
     * not started — we descend the chain as always: there the preference is about the *how*, and stays
     * satisfied.
     */
    suspend fun selectSilent(preferred: InstallerKind? = null): Installer? {
        if (preferred != null && installers.any { it.kind == preferred && !it.supportsSilent }) {
            return null
        }
        return pick(preferred, silentOnly = true)
    }

    private suspend fun pick(preferred: InstallerKind?, silentOnly: Boolean): Installer? {
        val candidates = installers.filter { !silentOnly || it.supportsSilent }
        preferred?.let { wanted ->
            candidates.firstOrNull { it.kind == wanted && it.isAvailable() }?.let { return it }
        }
        for (kind in chain) {
            candidates.firstOrNull { it.kind == kind && it.isAvailable() }?.let { return it }
        }
        return null
    }

    /**
     * Who, on this device, knows how to put game data in its place. `null` if nobody.
     *
     * It is a question separate from "who installs", and not for symmetry: the installation may well
     * go through the system confirmation — because the user chose it — while the game data is written
     * by the shell anyway, if there is one. Tying them would mean whoever prefers seeing the
     * confirmation cannot install a game with OBB.
     */
    suspend fun expansionWriter(): ExpansionWriter? {
        for (kind in chain) {
            installers.firstOrNull { it.kind == kind && it.expansions != null && it.isAvailable() }
                ?.let { return it.expansions }
        }
        return null
    }

    /**
     * What this device really offers, for the Settings screen.
     *
     * It distinguishes **installed** from **usable**: a device with Shizuku stopped, or with the
     * permission never granted, must be able to show the entry and offer to grant it — hiding it
     * would leave the user wondering why the feature they read about is not there. It is the same
     * choice made for dynamic colour below Android 12.
     */
    suspend fun availability(): InstallerAvailability = InstallerAvailability(
        supported = installers.map { it.kind }.toSet(),
        usable = installers.filter { it.isAvailable() }.map { it.kind }.toSet(),
        silent = installers.filter { it.supportsSilent && it.isAvailable() }.map { it.kind }.toSet(),
    )

    /**
     * Asks the user for what is needed to make [kind] usable.
     *
     * `false` also when that installer does not exist at all in this build: to the caller — the
     * Settings screen — "it is not there" and "the user said no" lead to the same row, i.e. the entry
     * stays off.
     */
    suspend fun requestPermission(kind: InstallerKind): Boolean =
        installers.firstOrNull { it.kind == kind }?.requestPermission() ?: false
}
