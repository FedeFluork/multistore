package com.multistore.app.navigation

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.feature.appdetail.AppDetailRoute
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The only logic living in the navigation graph: **which jumps push a back-stack entry**.
 *
 * The test exists for a defect found on the device and not findable anywhere else. `launchSingleTop`
 * looks at the **destination**, not at its arguments: in the jump from one detail page to another
 * store's, departure and arrival are the same destination, and with the flag on the tap produced
 * nothing — no error, no transition, the same page sitting still under the finger. From search it
 * worked, because there the top of the stack is a different destination.
 *
 * Both directions are intended and both need protecting: from search the flag is needed (two quick taps
 * must not push two copies), from the detail page it has to go.
 *
 * **The page → page jump now pushes no entry at all**: `popUpTo` removes the departure one. The earlier
 * justification — "the back button returns to the page you started from, which is how two stores get
 * compared" — does not hold in use: comparing four left four pages to close one at a time in order to
 * get back to search, re-crossing precisely the pages one had just decided to leave.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MultiStoreNavHostTest {

    private lateinit var controller: TestNavHostController

    @Before
    fun setUp() {
        controller = TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = SearchRoute) {
                composable<SearchRoute> {}
                composable<AppDetailRoute> {}
            }
        }
    }

    @Test
    fun `from one page to another store's it replaces, it does not stack`() {
        controller.navigateToAppDetail(StoreId.FDROID, StoreAppRef("de.danoeh.antennapod"))
        val depthBefore = controller.currentBackStack.value.size

        controller.navigateToAnotherListing(StoreId.APKCOMBO, StoreAppRef("antennapod/de.danoeh.antennapod"))

        // The depth does not grow: the departure page has been removed from the stack.
        assertThat(controller.currentBackStack.value.size).isEqualTo(depthBefore)
        val arrived = controller.currentBackStackEntry?.toRoute<AppDetailRoute>()
        assertThat(arrived?.storeId).isEqualTo(StoreId.APKCOMBO.wireName)
    }

    @Test
    fun `three jumps in a row leave a single page between search and the user`() {
        controller.navigateToAppDetail(StoreId.FDROID, StoreAppRef("de.danoeh.antennapod"))
        controller.navigateToAnotherListing(StoreId.APKCOMBO, StoreAppRef("a/b"))
        controller.navigateToAnotherListing(StoreId.APKMIRROR, StoreAppRef("c/d"))
        controller.navigateToAnotherListing(StoreId.UPTODOWN, StoreAppRef("e"))

        // It is the case the previous test does not cover and the user really meets: comparing four
        // stores. `popUpTo` walks back to the **nearest** match, so it only works because there is never
        // more than one to walk back to — with a single jump made without the rule, the chain would
        // already have formed.
        controller.popBackStack()
        assertThat(controller.currentBackStackEntry?.toRoute<SearchRoute>()).isNotNull()
    }

    @Test
    fun `tapping the same result twice does not stack two copies`() {
        val ref = StoreAppRef("de.danoeh.antennapod")

        controller.navigateToAppDetail(StoreId.FDROID, ref)
        val depthAfterFirst = controller.currentBackStack.value.size
        controller.navigateToAppDetail(StoreId.FDROID, ref)

        // `launchSingleTop` is needed here, and it is the half of the rule that must not be removed: two
        // quick taps on the same result would give two identical pages to close twice.
        assertThat(controller.currentBackStack.value.size).isEqualTo(depthAfterFirst)
    }
}
