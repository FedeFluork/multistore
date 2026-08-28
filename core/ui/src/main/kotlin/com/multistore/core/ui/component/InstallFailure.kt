package com.multistore.core.ui.component

import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.multistore.core.common.result.AppError
import com.multistore.core.ui.R

/**
 * Why the installation did not succeed, said in a sentence that leads to a gesture.
 *
 * ### The code was there and nobody read it
 *
 * `AppError.InstallFailed` has carried `PackageInstaller`'s `statusCode` from the start, and
 * [appErrorMessage] used to flatten it onto a single sentence: "The system refused the installation".
 * True for all seven outcomes and useful for none — because the seven lead to seven different
 * gestures, and two of them (**space** and **signature conflict**) the user can solve in thirty
 * seconds if only somebody tells them.
 *
 * ### The case that counts most is the second
 *
 * `STATUS_FAILURE_BLOCKED` is the R6 risk — "`REQUEST_INSTALL_PACKAGES` obstructed by the OEM" — seen
 * from the user's side. It is the outcome one gets when the refuser is not Android but something
 * above it: a device policy, a system antivirus, or a manufacturer ROM feature. On that family of
 * cases "the system refused" sends people looking in the wrong place — in Android's settings, where
 * there is nothing to change.
 *
 * **The manufacturer's name appears in the message and the steps to take do not**, and that is a
 * choice. MIUI, HyperOS, EMUI, ColorOS and Funtouch each have their own entry in their own menu, and
 * none of them is verifiable from here: this project has one rule about what it has not measured —
 * "write *why* instead of inventing it". A list of menu paths copied from a forum ages with every ROM
 * update and has no test that notices; the manufacturer's name, by contrast, is declared by the
 * device, and it is what makes the rest searchable. The diagnostic report, which already writes that
 * name, is the other half.
 */
@Composable
fun installFailureExplanation(error: AppError.InstallFailed): String =
    when (val kind = InstallFailureKind.of(error.statusCode)) {
        // The manufacturer's name only here: it is the only outcome in which the ROM is likely to be
        // the one refusing, and elsewhere it would be an extra word helping nobody.
        InstallFailureKind.BLOCKED -> stringResource(R.string.install_failed_blocked, Build.MANUFACTURER)
        else -> stringResource(kind.messageRes)
    }

/**
 * The explanation **plus** the system's raw message, for whoever shows them together.
 *
 * The system's message is written for developers (`INSTALL_FAILED_VERSION_DOWNGRADE`) and is not
 * translated, but it is exactly what needs pasting into a report. Removing it would make the sentence
 * cleaner and the report useless. Whoever shows it on their own account — "My apps"'s uninstall
 * dialog — uses [installFailureExplanation].
 */
@Composable
fun installFailureMessage(error: AppError.InstallFailed): String =
    listOfNotNull(installFailureExplanation(error), error.systemMessage?.takeIf { it.isNotBlank() })
        .joinToString(separator = "\n")

/**
 * `PackageInstaller`'s seven outcomes, plus "I do not know".
 *
 * `internal` and not public: whoever is outside wants the sentence, not the enum. It exists as a type
 * — instead of a `when` over the `Int` inside the composable — because that is how the compiler
 * demands the `when` be exhaustive, and it is the only thing that will notice the day Android adds
 * the eighth.
 */
internal enum class InstallFailureKind(@param:StringRes val messageRes: Int) {

    /** No code, or a code this version of Android did not know. */
    UNKNOWN(R.string.install_failed_unknown),

    /** Refused by something above Android: a policy, an antivirus, the manufacturer's ROM. */
    BLOCKED(R.string.install_failed_blocked_generic),

    /** The archive is not a valid APK: nearly always a corrupt download. */
    INVALID(R.string.install_failed_invalid),

    /** Conflict with an already installed package: different signature, or older version. */
    CONFLICT(R.string.install_failed_conflict),

    STORAGE(R.string.install_failed_storage),

    /** The app does not run on this device: `minSdk` too high, or no compatible ABI. */
    INCOMPATIBLE(R.string.install_failed_incompatible),

    /** The system took too long. Retrying makes sense, and it is the only case in which it does. */
    TIMEOUT(R.string.install_failed_timeout),
    ;

    companion object {
        /**
         * `null` means "there is no code", and it really happens: the silent channels talk to `pm`,
         * which prints text and not an integer. There the only available diagnosis is the system's
         * message, which is indeed always shown.
         */
        fun of(statusCode: Int?): InstallFailureKind = when (statusCode) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> BLOCKED
            PackageInstaller.STATUS_FAILURE_INVALID -> INVALID
            PackageInstaller.STATUS_FAILURE_CONFLICT -> CONFLICT
            PackageInstaller.STATUS_FAILURE_STORAGE -> STORAGE
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> INCOMPATIBLE
            // `STATUS_FAILURE_TIMEOUT` exists from API 34 and the `minSdk` is 26: the constant is `8`
            // and reading it by name below 34 does not throw — it is a `static final int`, which the
            // compiler inlines — but the branch on `SDK_INT` tells the reader that below that level
            // that code does not arrive. It is the same family as `longVersionCode` and
            // `GET_SIGNING_CERTIFICATES`, with the mildest outcome of the three.
            TIMEOUT_STATUS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) TIMEOUT else UNKNOWN
            else -> UNKNOWN
        }

        /**
         * `PackageInstaller.STATUS_FAILURE_TIMEOUT`, written by value.
         *
         * The constant cannot be used in a `when` branch: it is annotated `@RequiresApi(34)` and lint
         * refuses it in a position that does not allow a version check first. The value is part of the
         * public API and cannot change — and if it did, the branch would stop matching and the outcome
         * would be `UNKNOWN`, i.e. the generic sentence.
         */
        private const val TIMEOUT_STATUS = 8
    }
}
