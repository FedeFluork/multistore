package com.multistore.feature.settings

import kotlinx.serialization.Serializable

/**
 * Which stores MultiStore searches, as a destination of its own.
 *
 * No fields: there is one store catalogue and the screen reads it whole. A route carrying the open
 * tab would make "which group am I looking at" a back-stack entry, so every tab tapped would be one
 * more press of Back on the way out — and the tab is screen state, which `rememberSaveable` already
 * carries across process death.
 *
 * It lives in `:feature:settings` and not in a module of its own for the same reason
 * `StoreComparisonRoute` lives in `:feature:appdetail`: it answers the Settings screen's own
 * question, is opened from it and returns to it.
 */
@Serializable
data object StoreChooserRoute
