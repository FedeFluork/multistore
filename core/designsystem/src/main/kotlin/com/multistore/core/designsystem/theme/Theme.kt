package com.multistore.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.multistore.core.model.ThemeMode

/**
 * MultiStore's theme.
 *
 * Rule 3: every component works in light and in dark. What makes that true is not discipline but
 * the absence of an alternative — no `:feature:*` may declare a colour, so everything comes
 * through here.
 *
 * Three states ([ThemeMode]) for three distinct behaviours, plus Android 12+ dynamic colour,
 * which replaces the brand palette with one derived from the user's wallpaper. Dynamic colour
 * has to be checked separately: a system-generated palette cannot be assumed to keep the
 * contrasts ours was designed for.
 */
@Composable
fun MultiStoreTheme(
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolveIsDark()
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamicColor = dynamicColor && supportsDynamicColor

    val colorScheme: ColorScheme = when {
        useDynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        useDynamicColor -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> MultiStoreDarkColorScheme
        else -> MultiStoreLightColorScheme
    }

    CompositionLocalProvider(
        LocalMultiStoreThemeState provides MultiStoreThemeState(
            isDark = darkTheme,
            isDynamicColor = useDynamicColor,
            themeMode = themeMode,
        ),
        LocalSpacing provides Spacing,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MultiStoreTypography,
            shapes = MultiStoreShapes,
            content = content,
        )
    }
}

/** `true` if the theme in force is dark, whatever the reason. */
@Composable
private fun ThemeMode.resolveIsDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Theme state readable by components that need to *know* which theme they are in — to choose
 * between two assets, say — without having to re-derive it.
 */
data class MultiStoreThemeState(
    val isDark: Boolean,
    val isDynamicColor: Boolean,
    val themeMode: ThemeMode,
)

val LocalMultiStoreThemeState = staticCompositionLocalOf {
    MultiStoreThemeState(isDark = false, isDynamicColor = false, themeMode = ThemeMode.DEFAULT)
}
