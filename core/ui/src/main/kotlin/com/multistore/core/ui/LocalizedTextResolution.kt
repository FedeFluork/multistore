package com.multistore.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.multistore.core.model.LocalizedText

/**
 * The languages the user prefers to read in, in order.
 *
 * It serves to resolve the [LocalizedText]s arriving **from the store** — summaries, descriptions,
 * category and anti-feature names — which do not go through `strings.xml` and are therefore not
 * resolved by the resource system.
 *
 * The source is the `Configuration` and not `settings.proto`, and the difference matters:
 * `MainActivity` pushes the chosen language into `AppCompatDelegate.setApplicationLocales`, so what
 * is read here is **the language really in force**, already including the choice made from the system
 * settings on Android 13+. Reading the proto would give the same answer in the normal case and a
 * wrong answer precisely in the case where the two diverge.
 */
@Composable
fun rememberPreferredLanguageTags(): List<String> {
    val locales = LocalConfiguration.current.locales
    return remember(locales) {
        List(locales.size()) { index -> locales[index]?.toLanguageTag().orEmpty() }
            .filter { it.isNotEmpty() }
    }
}
