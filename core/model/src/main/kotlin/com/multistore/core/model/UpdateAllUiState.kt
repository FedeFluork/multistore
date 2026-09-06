package com.multistore.core.model

/**
 * Where an "update everything" has got, as **something a screen can hold**.
 *
 * Two types and not one, and the difference is what each is for. `UpdateAllStep` is the event
 * stream, and it carries a case — `UpdateAllStep.UserAction` — that must never be a resting state:
 * an intent kept in the state would be relaunched on every recomposition and every rotation. This
 * one is what a `StateFlow` holds, so it has an [Idle] the events do not need and no `UserAction`
 * the state must not have. Folding them together would leave one branch nobody ever walks, which
 * this project treats as a branch nobody proves.
 *
 * It lives in `:core:model` rather than in a feature because **three** modules now name it: the two
 * screens that offer the gesture, which may not depend on one another, and `:core:ui`, which draws
 * the panel they share. `:core:ui` cannot see `:core:domain` — that edge would drag Room and the
 * DataStore into the module of the shared components — so the vocabulary goes to the one module
 * everybody already depends on. One definition is also the only way the two screens cannot end up
 * disagreeing about what "finished" means.
 */
sealed interface UpdateAllUiState {

    data object Idle : UpdateAllUiState

    data class Running(val done: Int, val total: Int, val label: String) : UpdateAllUiState

    /** [failed] includes cancellations: whoever said no to the system dialog. */
    data class Finished(val installed: Int, val failed: Int) : UpdateAllUiState
}
