package com.multistore.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` with a test dispatcher.
 *
 * It serves anyone testing a `ViewModel`: `viewModelScope` runs on `Dispatchers.Main`, which in a JVM
 * test does not exist — without this rule the first `launch` fails with "Module with the Main
 * dispatcher had failed to initialize", and the error does not say this line is missing.
 *
 * The default is [UnconfinedTestDispatcher]: the coroutines start **immediately**, so the state an
 * `init {}` produces is already there when the test reads it, without having to remember an
 * `advanceUntilIdle()` before every assertion. Whoever instead has to control time — a debounce, a
 * timeout — passes a `StandardTestDispatcher(testScheduler)` and uses
 * `advanceTimeBy`/`advanceUntilIdle`.
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
