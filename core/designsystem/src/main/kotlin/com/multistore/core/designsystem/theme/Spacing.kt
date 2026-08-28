package com.multistore.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Material 3 defines no spacing tokens: without a shared scale every screen invents its own
 * padding and nothing ever lines up. These are the only permitted values.
 */
@Suppress("MemberNameEqualsClassName")
data class SpacingTokens(
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val huge: Dp = 32.dp,
    /** Standard horizontal margin of a screen. */
    val screenHorizontal: Dp = 16.dp,
    /** Gap between two cards in a list. */
    val listItemGap: Dp = 8.dp,
)

internal val Spacing = SpacingTokens()

val LocalSpacing = staticCompositionLocalOf { SpacingTokens() }
