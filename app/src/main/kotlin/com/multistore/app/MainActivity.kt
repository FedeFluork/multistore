package com.multistore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.multistore.app.ui.MultiStoreApp
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.AppearanceSettings
import com.multistore.core.model.SupportedLanguage
import com.multistore.core.model.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * `AppCompatActivity` and not `ComponentActivity`: the per-app language of
 * `AppCompatDelegate.setApplicationLocales` only works inside AppCompat, and it is the API that on
 * Android 13+ delegates to the system `LocaleManager` instead of reimplementing language selection
 * by hand.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * The confirmation dialogs the shell has to launch, for downloads no screen was left to install.
     *
     * Injected into the Activity and not into a ViewModel, and that is the whole point: from API 34
     * the system's installation confirmation **cannot be started from the background**, so the only
     * thing allowed to launch it is something that knows it is in the foreground. A ViewModel does
     * not know; `repeatOnLifecycle(STARTED)` does.
     */
    @Inject lateinit var autoInstall: AutoInstallCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Until the theme and language are known we cannot draw without risking a flash of the
        // wrong theme: the splash stays up until the settings are first read.
        var isLoading = true
        splashScreen.setKeepOnScreenCondition { isLoading }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The channel buffers while nothing is collecting, so a transfer that finished with
                // the app in the background gets its dialog on the way back in — which is exactly
                // when it can be shown, and not a moment earlier.
                autoInstall.userActions.collect { intent ->
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    isLoading = state is MainUiState.Loading
                    if (state is MainUiState.Ready) {
                        applyNightMode(state.appearance)
                        syncLocale(state.appearance)
                    }
                }
            }
        }

        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val appearance = (state as? MainUiState.Ready)?.appearance ?: AppearanceSettings()
            MultiStoreTheme(
                themeMode = appearance.themeMode,
                dynamicColor = appearance.dynamicColor,
            ) {
                MultiStoreApp()
            }
        }
    }

    /**
     * Aligns AppCompat's night mode with the chosen theme.
     *
     * Not a duplicate of `MultiStoreTheme`: that colours what Compose draws, this decides which
     * variant of the **XML** theme the window uses — that is the splash background and the window
     * background, which the system draws before any of our code runs.
     *
     * Without this line, whoever picks the dark theme sees a white splash on every launch and then a
     * dark app: the defect is small but it is exactly the app's first half-second. AppCompat persists
     * the choice itself, so from the second launch the correct variant is already active before the
     * DataStore is read.
     *
     * Single source of truth: `settings.proto`. Here it is only pushed, never read back. Comparing
     * before writing avoids a `recreate()` on every launch.
     */
    private fun applyNightMode(appearance: AppearanceSettings) {
        val desired = when (appearance.themeMode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != desired) {
            AppCompatDelegate.setDefaultNightMode(desired)
        }
    }

    /**
     * Keeps the stored language and the one in force aligned.
     *
     * It is the only place in the app that calls `setApplicationLocales`, but the synchronisation is
     * **not one-way**, and the reason is that the manifest declares `android:localeConfig`: since
     * Android 13 there is a second place where the user can change MultiStore's language, the "App
     * languages" list in the system settings. Always pushing the proto towards AppCompat would erase
     * that choice on every launch. So on the process's first pass the system wins (see
     * [MainViewModel.reconcileLocaleWithSystem]) and from then on the proto leads.
     *
     * Comparing before writing is not an optimisation: below Android 13 `setApplicationLocales`
     * causes the Activity to `recreate()`, so rewriting the same value on every launch would give a
     * visible restart every time.
     *
     * AppCompat's own storage (`autoStoreLocales`, declared in the manifest) stays useful for
     * re-applying the language *before* this Activity exists: that is what makes startup flicker-free
     * on pre-Android 13 devices.
     */
    private fun syncLocale(appearance: AppearanceSettings) {
        val current = AppCompatDelegate.getApplicationLocales()
        // The LocaleManager can return a tag with a region ("it-IT"): we normalise it to the
        // language, which is the unit the app reasons in. A tag outside the 5 supported ones is
        // equivalent to "follow the system".
        val systemTag = current.toLanguageTags()
            .substringBefore(',')
            .let { SupportedLanguage.fromBcp47OrNull(it)?.tag }
            ?: SupportedLanguage.FOLLOW_SYSTEM_TAG

        if (viewModel.reconcileLocaleWithSystem(systemTag, appearance.languageTag)) return

        val desired = if (appearance.languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(appearance.languageTag)
        }
        if (current != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}
