package com.multistore.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.multistore.core.data.repository.SettingsRepository
import com.multistore.core.model.AppearanceSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app's startup state.
 *
 * [Loading] is not cosmetic: until the settings are read we do not know which theme or which
 * language to apply, and drawing with the defaults only to change them afterwards would produce a
 * visible flash on every launch. The splash screen stays up while we are in [Loading].
 */
sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val appearance: AppearanceSettings) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * `true` until the stored language has been compared once with the one actually in force in the
     * system. It lives in the ViewModel and not in the Activity on purpose: it must hold once per
     * process, not once per Activity recreation — and changing language *causes* a recreation.
     */
    private var awaitingLocaleReconciliation = true

    val uiState: StateFlow<MainUiState> = settingsRepository.appearance
        .map<AppearanceSettings, MainUiState>(MainUiState::Ready)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState.Loading,
        )

    /**
     * Reconciles, once per process, the stored language with the one in force.
     *
     * The case that makes this function necessary: the manifest declares `android:localeConfig`, so
     * since Android 13 MultiStore appears in the "App languages" list of the system settings. That
     * is a real configuration point, deliberately exposed, and the user can use it without going
     * through our screen.
     *
     * Always and only pushing `settings.proto` towards AppCompat would mean **undoing that choice on
     * every launch**: the user sets French from the system settings, reopens the app, and finds it in
     * Italian. On the first pass, therefore, the system is in charge and the value read is written
     * into the proto; from then on the proto leads again.
     *
     * @return `true` if it adopted the system value — in which case the caller must push nothing: the
     *   write will make the Flow re-emit and the next pass will be a no-op.
     */
    fun reconcileLocaleWithSystem(systemTag: String, storedTag: String): Boolean {
        if (!awaitingLocaleReconciliation) return false
        awaitingLocaleReconciliation = false
        if (systemTag == storedTag) return false
        viewModelScope.launch { runCatching { settingsRepository.setLanguageTag(systemTag) } }
        return true
    }
}
