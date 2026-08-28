package com.multistore.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.multistore.core.designsystem.theme.MultiStoreTheme
import com.multistore.core.model.ThemeMode
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base class for a screen's screenshot tests.
 *
 * Rule 3: "every new screen has a Roborazzi screenshot test **in both themes**". This class is the
 * half that makes the rule convenient to respect; `ScreenshotCoverageTest` in `:guardrails` is the
 * half that makes it mandatory.
 *
 * One test per theme, not a single one with two captures: `setContent` cannot be called twice on the
 * same rule. It is also what lets the guardrail notice if a theme is missing.
 *
 * ```kotlin
 * class HomeScreenScreenshotTest : ScreenshotTest() {
 *     @Test fun light() = capture("HomeScreen", ThemeMode.LIGHT) { HomeScreen() }
 *     @Test fun dark() = capture("HomeScreen", ThemeMode.DARK) { HomeScreen() }
 * }
 * ```
 *
 * ### It captures **and** checks
 *
 * Every capture also goes through the Accessibility Test Framework — see [checkAccessibility]. The
 * place is chosen: this is the bottleneck every screen in both themes passes through, and a guardrail
 * that already exists makes them pass through it. A check hooked up elsewhere would be a list of
 * screens to keep up to date by hand, i.e. a list that sooner or later is not.
 */
@RunWith(AndroidJUnit4::class)
// NATIVE is needed because without Robolectric's hardware-accurate rendering the images would all
// come out transparent: LEGACY does not really draw.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    // A fixed device makes the screenshots comparable across different machines: density, dimensions
    // and font scale must not depend on who runs the test.
    qualifiers = RobolectricDeviceQualifiers.Pixel7,
    sdk = [ScreenshotDefaults.SDK],
)
abstract class ScreenshotTest {

    /**
     * KNOWN DEBT — `createComposeRule` is deprecated in favour of
     * `androidx.compose.ui.test.junit4.v2`. The migration is not an import change: the v2 package of
     * `ui-test-junit4:1.12.0` exposes **only** `createAndroidComposeRule`, which demands a test
     * Activity, and uses `StandardTestDispatcher` instead of `UnconfinedTestDispatcher` — i.e. it
     * queues the tasks instead of running them immediately. For a screenshot test that means being
     * able to capture a composition that is not yet stable, therefore goldens that change without the
     * UI having changed.
     *
     * It has to be done deliberately, with the goldens re-recorded and looked at one by one, not
     * tacked onto a one-line change inside another modification.
     */
    @Suppress("DEPRECATION")
    @get:Rule
    val composeTestRule: ComposeContentTestRule = createComposeRule()

    /**
     * Composes [content] inside [MultiStoreTheme] in the requested theme, saves its image and checks
     * its accessibility.
     *
     * `dynamicColor` is forced to `false`: the dynamic palette depends on the device's wallpaper, so a
     * screenshot using it would not be reproducible.
     */
    protected fun capture(
        screenName: String,
        themeMode: ThemeMode,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            MultiStoreTheme(themeMode = themeMode, dynamicColor = false) {
                content()
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = screenshotPath(screenName, themeMode),
            roborazziOptions = COMPARE_ACROSS_PLATFORMS,
        )
        checkAccessibility()
    }

    private companion object {
        /**
         * Comparison tolerance, and the reason it is a **per-pixel colour distance** and not a
         * changed-pixel ratio.
         *
         * The goldens are recorded on macOS/arm64 and compared on the Linux/x86-64 CI runner, and
         * those two rasterise curved edges and hairlines differently: switch thumbs and 1px
         * dividers, with every glyph identical.
         *
         * ### The numbers, measured
         *
         * Taken on 28/08/2026 from the first real CI run, comparing each committed golden against
         * the runner's own output for the 12 screenshots that failed:
         *
         * | | |
         * |---|---|
         * | pixels differing | 0.05-0.26% (56 to 110049 of 2586122) |
         * | largest per-channel delta | **2 / 255**, on every one of the 12 |
         * | largest `Color.distance` | **0.01358** |
         * | pixels still differing at `maxDistance` 0.010 | 286 |
         * | pixels still differing at `maxDistance` 0.014 | **0** |
         *
         * `0.01358` is not a coincidence: `Color.distance` is the Euclidean distance over
         * **normalised RGBA and is not divided by the channel count**, so a delta of 2/255 on all
         * three colour channels is `2/255 * sqrt(3)` = 0.01358. A first attempt at 0.01 failed for
         * exactly that reason — it was sized against the per-channel delta instead of the metric.
         *
         * ### Why 0.02 and not 0.014
         *
         * 0.014 clears the measured ceiling by 3%, which is no margin at all if a runner image
         * change nudges the rasteriser. 0.02 clears it by 47% and still catches everything real:
         * a uniform 3/255 shift is 0.0204 and fails, and the worst it can hide is about 5/255 on a
         * single channel. Material's tonal steps are far larger, and a moved or missing glyph flips
         * pixels between text and background — distances above 0.5.
         *
         * ### Why not `changeThreshold`
         *
         * A changed-pixel *ratio* would tolerate a small region changing **completely** — a wrong
         * icon, a missing badge, a swapped colour on one row — because a small region is a small
         * fraction of the image. That is the tolerance `ci.yml` was right to refuse. This is the
         * other kind: it tolerates only what no eye can resolve, and it tolerates it *everywhere*,
         * so any pixel that changes visibly still fails however small the area.
         *
         * ### Why not record on Linux instead
         *
         * That was the earlier plan, and it costs more than it looks. `verifyRoborazziDebug` would
         * fail on every developer machine, so the fast local check before opening a PR is gone — and
         * the next `recordRoborazziDebug` run locally out of habit silently rewrites all 42 goldens
         * with macOS ones and puts CI back to red, with no guardrail able to notice. One golden set
         * valid on both platforms has no such footgun.
         */
        val COMPARE_ACROSS_PLATFORMS = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }

    /**
     * Passes the screen to the Accessibility Test Framework — **all its roots**.
     *
     * ### Why all of them, and not `onRoot()`
     *
     * A screen with a `ModalBottomSheet` or a dialog has **two** Compose roots, and there `onRoot()`
     * is not ambiguous: it fails, with "Expected exactly 1 node but found 2". The search's filter
     * panel said so on the first run — and the image capture, which also calls `onRoot()`, holds up
     * instead, so the defect would not have shown before the check was hooked up.
     *
     * The fix is not to take one: that would be taking the one **underneath** and skipping precisely
     * the surface the user is touching. All of them are checked.
     *
     * ### What it covers, and what it does not
     *
     * Measured on 26/08/2026 with three deliberately broken probes:
     *
     * | violation | outcome |
     * |---|---|
     * | 12dp clickable target with no label | **red** — `SpeakableTextPresentCheck` and `TouchTargetSizeCheck` |
     * | `#FAFAFA` text on white | green |
     * | `#FFFF33` text at 28sp on `#FFFF00` | green |
     *
     * **Contrast is not covered**, and that has to be written rather than left to be assumed: the
     * engine does receive the bitmap — `RoboComponent.Compose.getImage()` ends up in
     * `putScreenCapture` — but for Compose nodes the contrast checks produce no result. A guardrail
     * believed wider than it is makes people stop looking, which is worse than not having it.
     *
     * Contrast in this project is governed by something else: rule 3 — colours only from
     * `MaterialTheme.colorScheme`, which respects the ratios by construction — and the goldens in the
     * two themes, which show it to the eye.
     *
     * What it does cover is exactly what this app was missing: **the labels** and **the target sizes**.
     * They are the two things that, wrong, make a screen unusable with TalkBack without changing a
     * single pixel of how it looks — i.e. invisible both to a state test and to a golden.
     */
    private fun checkAccessibility() {
        val roots = composeTestRule.onAllNodes(isRoot())
        repeat(roots.fetchSemanticsNodes().size) { index ->
            roots[index].checkRoboAccessibility(AccessibilityChecks.OPTIONS)
        }
    }
}

object ScreenshotDefaults {
    /**
     * The SDK the screenshot tests run on.
     *
     * Deliberately **not** aligned with `targetSdk`: Robolectric supports a set of levels for which an
     * `android-all` has been published, which is always behind the latest release. Raising it when
     * Robolectric supports it is a one-line change; chasing it immediately means tests that do not
     * start.
     */
    const val SDK: Int = 34
}

/** Where the goldens end up. They are committed: they are the comparison's baseline. */
const val SCREENSHOT_OUTPUT_DIRECTORY: String = "src/test/screenshots"

fun screenshotPath(screenName: String, themeMode: ThemeMode): String =
    "$SCREENSHOT_OUTPUT_DIRECTORY/${screenName}_${themeMode.name.lowercase()}.png"
