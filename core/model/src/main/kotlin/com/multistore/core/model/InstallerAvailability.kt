package com.multistore.core.model

/**
 * Which installers this device offers, and in what state.
 *
 * The three sets are not redundant, and the difference is what the Settings screen has to be
 * able to say:
 *
 *  - [supported] is what this build can do — today always all three;
 *  - [usable] is what works **now**: Shizuku running with permission granted, `su` answering.
 *    An entry outside this stays visible but disabled, with the way to enable it next to it;
 *  - [silent] is the subset of [usable] that installs without showing the system confirmation,
 *    i.e. the only one that makes "install updates by itself" meaningful.
 */
data class InstallerAvailability(
    val supported: Set<InstallerKind> = emptySet(),
    val usable: Set<InstallerKind> = emptySet(),
    val silent: Set<InstallerKind> = emptySet(),
) {
    /** `true` when there is a way to install without the user confirming by hand. */
    val hasSilent: Boolean get() = silent.isNotEmpty()

    /**
     * Whether an installation started now would go through **without** a confirmation screen.
     *
     * It mirrors `InstallerSelector.selectSilent` and has to keep mirroring it: an explicit
     * preference for the confirmation installer is not "then pick another one" — whoever chose to
     * see the dialog asked to see it — while any other preference descends the chain among the
     * silent candidates, so what decides is simply whether one exists.
     *
     * Two callers, and they must agree: the Settings row that disables
     * `auto_install_after_download` where there is no prompt to propose, and the coordinator that
     * would otherwise act on a `true` stored back when this device had no privileged channel.
     */
    fun installsSilently(preference: InstallerPreference): Boolean =
        preference != InstallerPreference.SESSION && hasSilent
}

/**
 * Which installer the user wants to proceed with.
 *
 * [AUTOMATIC] is first because the proto3 zero value *is* the default — the same trap that put
 * `SYSTEM` at the head of [ThemeMode] and `DAILY` at the head of [UpdateInterval]. Getting it
 * wrong here would mean the app starts forcing a channel the device may not have.
 *
 * The three explicit choices mean "try this first", not "only this": if the chosen channel is
 * unusable the chain is walked down, because an app that refuses to install something because
 * Shizuku is off would be a broken app. The one place where "silent only" really is a
 * constraint is the periodic check, which has nobody to show a confirmation to — and there it
 * is a parameter of the request, not a setting.
 */
enum class InstallerPreference(val kind: InstallerKind?) {
    /** The chain: root, then Shizuku, then the system confirmation. */
    AUTOMATIC(null),
    SESSION(InstallerKind.SESSION),
    SHIZUKU(InstallerKind.SHIZUKU),
    ROOT(InstallerKind.ROOT),
}

/**
 * How installation happens.
 *
 * A group of its own rather than a field of [SecuritySettings], because it is not a waiver of a
 * check: the verification pipeline is identical whichever channel hands the file to the system.
 * All that changes is who writes the bytes into the session, and whether the user sees a
 * confirmation.
 */
data class InstallSettings(
    val preference: InstallerPreference = InstallerPreference.AUTOMATIC,
    /**
     * Carry on to the installation when a download finishes and nobody is on the listing any more.
     *
     * `false` — the proto3 zero value — is what the app has always done: the file lands in staging
     * and waits for a tap. The positive name already gives the prudent default, the same case as
     * `show_nsfw_content`: what is prudent here is **not** to put a system dialog in front of
     * somebody who has moved on to doing something else.
     *
     * ### Why it is scoped to the confirmation installer
     *
     * With a silent channel there is no prompt to propose, so the entry says nothing there and the
     * screen disables it with that reason written next to it. The predicate is not "the preference
     * is SESSION" but "the installer that would actually be chosen is not silent": with
     * [InstallerPreference.AUTOMATIC] on a phone without root or Shizuku the chosen channel *is*
     * the confirmation one, and that is the common case this setting exists for.
     */
    val autoInstallAfterDownload: Boolean = false,
)
