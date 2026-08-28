package com.multistore.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.multistore.core.common.result.AppError
import com.multistore.core.ui.R

/**
 * The sentence to show for an [AppError].
 *
 * It lives in `:core:ui` and not in every feature for a reason only visible after the third screen:
 * the same `AppError.Blocked` has to say the same thing in the search, on the Home and on the detail
 * screen. Three separate mappings diverge, and diverge silently.
 *
 * The `when` is exhaustive on purpose, with no `else` branch: when `AppError` gains a case, the
 * compiler will stop the build here instead of letting the generic sentence appear to the user.
 */
@Composable
fun appErrorMessage(error: AppError): String = when (error) {
    is AppError.Network -> stringResource(R.string.error_network)
    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
    is AppError.Blocked -> stringResource(R.string.error_blocked)
    is AppError.Parse -> stringResource(R.string.error_parse)
    AppError.NotFound -> stringResource(R.string.error_not_found)
    is AppError.IntegrityFailed -> stringResource(R.string.error_integrity)
    is AppError.Storage -> stringResource(R.string.error_storage)
    is AppError.InstallFailed -> stringResource(R.string.error_install_failed)
    AppError.Cancelled -> stringResource(R.string.error_cancelled)
    AppError.UserAssistanceDisabled -> stringResource(R.string.error_user_assistance_disabled)
    is AppError.Unexpected -> stringResource(R.string.error_unexpected)
}
