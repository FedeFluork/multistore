package com.multistore.core.model

/**
 * The three theme states.
 *
 * It lives in `:core:model` (pure Kotlin) rather than `:core:datastore` so `:core:designsystem`
 * can read it without pulling in Proto DataStore: the design system knows nothing of persistence.
 */
enum class ThemeMode {
    /** Follows the system setting. The default on first launch. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM
    }
}
