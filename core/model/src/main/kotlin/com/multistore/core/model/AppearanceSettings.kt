package com.multistore.core.model

/**
 * The settings that decide how the app looks.
 *
 * Matches the "Appearance" section of `settings.proto`. It lives in `:core:model` because the
 * theme reads it, and the theme must know nothing about how settings are persisted.
 */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /**
     * Palette derived from the user's wallpaper, Android 12+.
     *
     * Off by default, and not as a matter of taste: it is the proto3 zero value, so the default
     * exists in exactly one place and cannot drift. It also makes the default experience the one
     * the golden screenshots cover, which could not exist with a wallpaper-derived palette.
     */
    val dynamicColor: Boolean = false,
    /** BCP-47. Empty = follow the system language, falling back to [SupportedLanguage.FALLBACK]. */
    val languageTag: String = SupportedLanguage.FOLLOW_SYSTEM_TAG,
) {
    /** The explicitly chosen language, or `null` when following the system. */
    val selectedLanguage: SupportedLanguage?
        get() = SupportedLanguage.fromTagOrNull(languageTag)
}
