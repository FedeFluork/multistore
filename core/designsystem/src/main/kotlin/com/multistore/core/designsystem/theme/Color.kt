package com.multistore.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * MultiStore's palette.
 *
 * **This file is the only place in the project where writing a `Color(0xFF…)` is allowed.**
 * Everywhere else colours come from `MaterialTheme.colorScheme`; a fixed colour in a
 * `:feature:*` breaks dark theme and dynamic colour without anyone noticing until they look.
 *
 * Both `ColorScheme`s are complete: every Material 3 role has an explicit value in light and in
 * dark. No role is left to the library default, because the defaults are not coordinated with
 * this palette and would produce off-key pairings on some surfaces only.
 */

// --- Brand seed: indigo-violet, with a rose tertiary ---------------------------------------
private val Indigo10 = Color(0xFF06006C)
private val Indigo20 = Color(0xFF1B2278)
private val Indigo30 = Color(0xFF343A90)
private val Indigo40 = Color(0xFF4F5BD5)
private val Indigo80 = Color(0xFFBFC2FF)
private val Indigo90 = Color(0xFFE0E0FF)

private val Slate10 = Color(0xFF191A2C)
private val Slate20 = Color(0xFF2E2F42)
private val Slate30 = Color(0xFF444559)
private val Slate40 = Color(0xFF5C5D72)
private val Slate80 = Color(0xFFC5C4DD)
private val Slate90 = Color(0xFFE1E0F9)

private val Rose10 = Color(0xFF2E1126)
private val Rose20 = Color(0xFF46263C)
private val Rose30 = Color(0xFF5F3C53)
private val Rose40 = Color(0xFF78536B)
private val Rose80 = Color(0xFFE8B9D5)
private val Rose90 = Color(0xFFFFD8EE)

private val Red10 = Color(0xFF410002)
private val Red20 = Color(0xFF690005)
private val Red30 = Color(0xFF93000A)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)

private val Neutral0 = Color(0xFF000000)
private val Neutral6 = Color(0xFF0E0D13)
private val Neutral10 = Color(0xFF131318)
private val Neutral12 = Color(0xFF1B1B21)
private val Neutral17 = Color(0xFF1F1F25)
private val Neutral20 = Color(0xFF2A2930)
private val Neutral22 = Color(0xFF303036)
private val Neutral24 = Color(0xFF35343B)
private val Neutral30 = Color(0xFF39383F)
private val Neutral90 = Color(0xFFE5E1E9)
private val Neutral94 = Color(0xFFEAE7EF)
private val Neutral96 = Color(0xFFF0EDF4)
private val Neutral98 = Color(0xFFF6F2FA)
private val Neutral99 = Color(0xFFFFFBFF)
private val Neutral100 = Color(0xFFFFFFFF)
private val NeutralDim = Color(0xFFDCD9E0)
private val NeutralInverseOn = Color(0xFFF3EFF4)

private val NeutralVariant30 = Color(0xFF46464F)
private val NeutralVariant50 = Color(0xFF777680)
private val NeutralVariant60 = Color(0xFF918F9A)
private val NeutralVariant80 = Color(0xFFC7C5D0)
private val NeutralVariant90 = Color(0xFFE3E1EC)

internal val MultiStoreLightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Neutral100,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    inversePrimary = Indigo80,
    secondary = Slate40,
    onSecondary = Neutral100,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,
    tertiary = Rose40,
    onTertiary = Neutral100,
    tertiaryContainer = Rose90,
    onTertiaryContainer = Rose10,
    error = Red40,
    onError = Neutral100,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = Neutral99,
    onBackground = Neutral12,
    surface = Neutral99,
    onSurface = Neutral12,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = Indigo40,
    inverseSurface = Neutral22,
    inverseOnSurface = NeutralInverseOn,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Neutral0,
    surfaceBright = Neutral99,
    surfaceDim = NeutralDim,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral98,
    surfaceContainer = Neutral96,
    surfaceContainerHigh = Neutral94,
    surfaceContainerHighest = Neutral90,
)

internal val MultiStoreDarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    inversePrimary = Indigo40,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Rose80,
    onTertiary = Rose20,
    tertiaryContainer = Rose30,
    onTertiaryContainer = Rose90,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Indigo80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral22,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Neutral0,
    surfaceBright = Neutral30,
    surfaceDim = Neutral10,
    surfaceContainerLowest = Neutral6,
    surfaceContainerLow = Neutral12,
    surfaceContainer = Neutral17,
    surfaceContainerHigh = Neutral20,
    surfaceContainerHighest = Neutral24,
)
