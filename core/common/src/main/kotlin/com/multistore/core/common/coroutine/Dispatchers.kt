package com.multistore.core.common.coroutine

import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatchers, injected rather than taken from `Dispatchers.IO` at the point of use.
 *
 * No `Thread.sleep` in tests: clocks and dispatchers are injected. A repository that calls
 * `Dispatchers.IO` directly cannot be tested deterministically, because the test has no way to
 * substitute a `TestDispatcher`.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MainDispatcher

/** A long-lived scope, tied to the process rather than to a screen. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

/**
 * The three dispatchers in one injectable object.
 *
 * Useful where all three are needed (a download engine reads from the network, computes a digest
 * and notifies the UI); where only one is needed, inject that one with its qualifier.
 */
data class DispatcherProvider(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val main: CoroutineDispatcher,
)
