package com.multistore.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.common.net.FailureKind
import com.multistore.core.common.net.StoreDiagnosis
import com.multistore.core.common.net.StoreFault
import com.multistore.core.model.StoreHealthState
import com.multistore.core.model.StoreId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the golden cannot compare: the sentences that are **absent**, and the ones that must differ.
 *
 * A picture holds the layout and the colours. It cannot hold that a store which has never been
 * queried says so rather than "it last answered 56 years ago" — the sentence a `null` treated as an
 * epoch would produce, which reads as data rather than as a bug.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoreDiagnosisDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a store that has never answered says so, rather than counting from the epoch`() {
        compose.setContent {
            StoreDiagnosisDialog(
                diagnosis = StoreDiagnosis(storeId = StoreId.AN1),
                storeName = "an1",
                onDismiss = {},
                now = NOW,
            )
        }

        compose.onNodeWithText(text = "never answered", substring = true).assertIsDisplayed()
        // And no run of faults: a duration since nothing would be the dialog inventing a history.
        compose.onNodeWithText(text = "failing for", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a fault seconds old reads as one minute, not as zero`() {
        compose.setContent {
            StoreDiagnosisDialog(
                diagnosis = StoreDiagnosis(
                    storeId = StoreId.APKMIRROR,
                    state = StoreHealthState.OPEN,
                    failingSince = NOW - 20.seconds,
                    lastFailure = StoreFault(FailureKind.BLOCKED, NOW - 20.seconds),
                ),
                storeName = "APKMirror",
                onDismiss = {},
                now = NOW,
            )
        }

        // "0 minutes" on something that has just happened reads as a bug rather than as "just now".
        compose.onNodeWithText(text = "1 minute", substring = true).assertIsDisplayed()
    }

    /**
     * The four faults do not collapse into one sentence, and the assertion is on the **difference**.
     *
     * The kinds lead to different expectations — a rate limit clears itself, a block does not, a
     * parse failure needs the app repaired rather than the store recovered — so a `when` collapsed
     * to a single arm would be a dialog that always says the same reassuring thing. Comparing the
     * resource ids rather than the words means rewording any of them cannot break this, while
     * merging two of them always does.
     */
    @Test
    fun `each fault has a sentence of its own`() {
        val distinct = FailureKind.entries
            .filter { it != FailureKind.NOT_FOUND }
            .map { it.explanationRes }
            .toSet()

        assertThat(distinct).hasSize(4)
    }

    /** Same argument one level up: four breaker states, four things to expect, four sentences. */
    @Test
    fun `each breaker state has a sentence of its own`() {
        assertThat(StoreHealthState.entries.map { it.headlineRes }.toSet()).hasSize(4)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-06T12:00:00Z")
    }
}
