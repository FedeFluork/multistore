package com.multistore.core.testing

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

/**
 * How a screen's accessibility is checked, and what was decided not to count.
 *
 * It lives next to [ScreenshotTest] and not inside it, because the only suppression that exists needs
 * to be **read** — and a suppression hidden at the bottom of a base class is how one ends up with ten
 * suppressions nobody has ever looked at again.
 */
object AccessibilityChecks {

    /**
     * `LATEST` and not a hand-picked set of checks.
     *
     * A list written here would age silently: ATF adds checks, and with a fixed set the new ones would
     * never run on any screen. `Error` and not `Warning` for the usual reason in this project — a
     * warning that stops nothing is a written rule, and this mechanism exists to turn one into a rule
     * no test can violate.
     */
    val OPTIONS: RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
        checker = RoborazziATFAccessibilityChecker(
            preset = AccessibilityCheckPreset.LATEST,
            suppressions = clippedByViewport(),
        ),
        failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
    )

    /**
     * The only suppression: **the size of a target the screen's edge clips**.
     *
     * ### The case, measured
     *
     * The search's filter panel is a scrolling `ModalBottomSheet`, and in the golden the last group's
     * chips are clipped by the bottom edge: 18 pixels of them remain visible, i.e. 7dp.
     * `TouchTargetSizeCheck` measures the bounds **on screen** and reports them as 7dp targets — which
     * is true of that frame and false of the app: a finger scrolling a centimetre brings them fully
     * into view.
     *
     * ### Why it is a suppression and not a defect to fix
     *
     * A screenshot is a **still** frame of a scrolling surface, and there is no scroll position in
     * which nothing is clipped: the one that frees the bottom clips the top. Demanding that no element
     * touch the edge would mean demanding that every list end exactly where the screen ends.
     *
     * ### Why it is narrow
     *
     * It suppresses **a single check**, and only for the elements really touching a window edge. A
     * target too small in the middle of the screen stays red, and it is verified by injection: a 12dp
     * clickable `Box` in the centre of a screen makes the test fail. If the suppression were on the
     * whole of `TouchTargetSizeCheck`, that injection would stay green — which is how a guardrail
     * becomes a caption.
     */
    private fun clippedByViewport(): Matcher<in AccessibilityViewCheckResult> =
        object : TypeSafeMatcher<AccessibilityViewCheckResult>() {

            override fun describeTo(description: Description) {
                description.appendText(
                    "a touch-target size finding on an element clipped by the window " +
                        "boundary",
                )
            }

            override fun matchesSafely(item: AccessibilityViewCheckResult): Boolean {
                if (item.accessibilityHierarchyCheck != TouchTargetSizeCheck::class.java) return false
                val element = item.element ?: return false
                val bounds = element.boundsInScreen
                val window = element.window?.boundsInScreen ?: return false
                // "Touches the edge" and not "is outside": what lies entirely outside is not drawn and
                // ATF does not see it. What the edge clips ends up exactly on the edge, and that is
                // this case.
                return bounds.top <= window.top ||
                    bounds.bottom >= window.bottom ||
                    bounds.left <= window.left ||
                    bounds.right >= window.right
            }
        }
}
