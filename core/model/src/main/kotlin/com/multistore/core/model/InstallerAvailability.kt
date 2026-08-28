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
)
