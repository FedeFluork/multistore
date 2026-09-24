package com.multistore.core.ui.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multistore.core.model.ModifiedBuild
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rework badge's three states, asserted on the tree rather than on pixels.
 *
 * The golden holds the two visible ones. This holds the third — and the third is the one that
 * matters, because [ModifiedBuild.NONE] applies to **four stores out of nine** and its whole
 * behaviour is to draw nothing: a "not modified" chip would be a claim whose only evidence is that
 * somebody else did not make one, and a picture with an empty slot cannot tell that apart from a
 * badge that failed to lay out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModifiedBuildBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a store that publishes no reworks gets no badge at all`() {
        compose.setContent {
            ModifiedBuildBadge(state = ModifiedBuild.NONE, storeDisplayName = "F-Droid")
        }

        // Neither label, not "no" and not "maybe": the component must produce nothing rather than a
        // reassuring chip. Both are checked because falling back to the wrong `when` branch would
        // otherwise show the "may be modified" one — the label `NONE` shares an arm with.
        compose.onNodeWithText(text = "Modified build", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text = "modified", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a declared rework and a possible one do not read the same`() {
        // One `setContent` and a state that changes under it, rather than two: the claim is that
        // the two states produce **different** text, and two independent tests asserting one label
        // each would both pass against a `when` collapsed to a single arm — which is exactly the
        // regression this guards, since `NONE` already shares an arm with `POSSIBLE` by design.
        val state = mutableStateOf(ModifiedBuild.DECLARED)
        compose.setContent {
            ModifiedBuildBadge(state = state.value, storeDisplayName = "an1")
        }
        compose.onNodeWithText("Modified build").assertIsDisplayed()

        state.value = ModifiedBuild.POSSIBLE
        compose.onNodeWithText("May be modified").assertIsDisplayed()
        compose.onNodeWithText("Modified build").assertDoesNotExist()
    }

    /**
     * The sentence the badge exists for, and the half of it that is the point.
     *
     * The reassuring half — "the package name matches, the hash is the announced one" — is true and
     * is not why this dialog is here. The other half is the limit `PreInstallVerifier` already
     * declares: a rework has no original developer signature to compare against, so nothing can say
     * the app was not altered by whoever republished it. A dialog that dropped it would be worse
     * than no dialog.
     */
    @Test
    fun `tapping the badge says what the check cannot prove, and names the store`() {
        compose.setContent {
            ModifiedBuildBadge(state = ModifiedBuild.DECLARED, storeDisplayName = "an1")
        }

        compose.onNodeWithText("Modified build").performClick()

        compose.onNodeWithText(text = "an1", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text = "cannot prove", substring = true).assertIsDisplayed()
    }
}
