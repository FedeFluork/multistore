package com.multistore.core.ui.component

import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `PackageInstaller`'s codes, translated into what the user can do.
 *
 * The code travelled inside `AppError.InstallFailed` from the start and nobody read it: the listing
 * showed "The system refused the installation" for all seven outcomes. Always true, never useful —
 * because "there is no space" and "the app does not run on this device" lead to two different
 * gestures, and one of the two is solved in thirty seconds.
 *
 * The test that counts most is the last: **every constant must have its outcome**, and none may fall
 * onto [InstallFailureKind.UNKNOWN] by carelessness.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstallFailureKindTest {

    @Test
    fun `with no code the outcome is unknown`() {
        // The silent channels talk to `pm`, which prints text and not an integer. It is not a gap:
        // there the only available diagnosis is the system's message, which is shown below the
        // sentence anyway.
        assertThat(InstallFailureKind.of(null)).isEqualTo(InstallFailureKind.UNKNOWN)
    }

    @Test
    fun `a code this version does not know invents no diagnosis`() {
        assertThat(InstallFailureKind.of(9_999)).isEqualTo(InstallFailureKind.UNKNOWN)
    }

    @Test
    fun `the ROM's refusal has an outcome of its own`() {
        // It is the R6 risk seen from the user's side, and it is the only outcome whose sentence names
        // the manufacturer: on that family of cases "the system refused" sends people looking in
        // Android's settings, where there is nothing to change.
        assertThat(InstallFailureKind.of(PackageInstaller.STATUS_FAILURE_BLOCKED))
            .isEqualTo(InstallFailureKind.BLOCKED)
    }

    @Test
    fun `every PackageInstaller constant has its outcome`() {
        assertThat(
            listOf(
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_STORAGE,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                TIMEOUT_STATUS,
            ).map(InstallFailureKind::of),
        ).containsExactly(
            InstallFailureKind.BLOCKED,
            InstallFailureKind.INVALID,
            InstallFailureKind.CONFLICT,
            InstallFailureKind.STORAGE,
            InstallFailureKind.INCOMPATIBLE,
            InstallFailureKind.TIMEOUT,
        ).inOrder()
    }

    /**
     * Below API 34 the timeout does not exist, and is not invented.
     *
     * `STATUS_FAILURE_TIMEOUT` arrived with API 34 and the `minSdk` is 26. On an older device that
     * code cannot arrive, and reading it as "retry" would tell somebody to retry something that did
     * not time out.
     *
     * `@Config` on a method and not on a private helper: Robolectric reads it on the tests, and on an
     * arbitrary method it has no effect at all — the first draft put it there and the test failed
     * while still running at 34.
     */
    @Test
    @Config(sdk = [33])
    fun `below API 34 the timeout does not exist and is not invented`() {
        assertThat(InstallFailureKind.of(TIMEOUT_STATUS)).isEqualTo(InstallFailureKind.UNKNOWN)
    }

    private companion object {
        /** `PackageInstaller.STATUS_FAILURE_TIMEOUT`, which is `@RequiresApi(34)`. */
        const val TIMEOUT_STATUS = 8
    }
}
