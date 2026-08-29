package com.multistore.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.multistore.core.model.StoreAppRef
import com.multistore.core.model.StoreId
import com.multistore.feature.appdetail.AppDetailRoute
import com.multistore.feature.appdetail.AppDetailScreen
import com.multistore.feature.appdetail.UserAssistedRequest
import com.multistore.feature.downloads.DownloadsScreen
import com.multistore.feature.home.HomeScreen
import com.multistore.feature.myapps.MyAppsScreen
import com.multistore.feature.search.SearchScreen
import com.multistore.feature.settings.SettingsScreen
import com.multistore.feature.storelisting.StoreListingRoute
import com.multistore.feature.storelisting.StoreListingScreen
import com.multistore.feature.webviewdownload.WebViewDownloadRoute
import com.multistore.feature.webviewdownload.WebViewDownloadScreen

/**
 * The navigation graph.
 *
 * It is the only place where the features meet: no `:feature:*` knows the others, so the link between
 * them can only live here.
 *
 * The five top-level routes live in `:app` because they take no arguments and are a fact of the
 * navigation bar, not of the features. `AppDetailRoute`, by contrast, lives in its own feature: it
 * carries the arguments without which the screen cannot open, and it is the one that has to declare
 * them.
 */
@Composable
fun MultiStoreNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onAppClick = navController::navigateToAppDetail,
                onBrowseCatalogue = navController::navigateToStoreListing,
            )
        }
        composable<SearchRoute> {
            SearchScreen(onAppClick = navController::navigateToAppDetail)
        }
        composable<MyAppsRoute> {
            MyAppsScreen(onAppClick = navController::navigateToAppDetail)
        }
        composable<DownloadsRoute> { DownloadsScreen() }
        composable<SettingsRoute> { SettingsScreen() }
        composable<AppDetailRoute> {
            AppDetailScreen(
                onBack = { navController.popBackStack() },
                onUserAssistedDownload = navController::navigateToWebViewDownload,
                onOpenListing = navController::navigateToAnotherListing,
            )
        }
        composable<StoreListingRoute> {
            StoreListingScreen(
                onAppClick = navController::navigateToAppDetail,
                onBack = { navController.popBackStack() },
            )
        }
        composable<WebViewDownloadRoute> {
            WebViewDownloadScreen(
                // Once a download is intercepted, we go back to the detail page, which sees the queued
                // row and shows the progress: it is the same observation that lets it re-attach to a
                // download started by another instance of itself.
                onFinished = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Goes to the Downloads tab, from wherever one is.
 *
 * It exists because the progress card above the screens leads there, and that card is drawn by the
 * shell rather than by a screen: it has to reach a top-level destination the same way the bottom bar
 * does, or the two would build different back stacks for the same place.
 */
internal fun NavHostController.navigateToDownloads() = navigateToTopLevel(TopLevelDestination.DOWNLOADS)

/**
 * Navigation between the top-level destinations.
 *
 * `launchSingleTop` and the `popUpTo` on the start destination ensure that tapping the bottom bar's
 * entries repeatedly does not build a deep stack: the back button returns to the Home, it does not
 * retrace every tab visited.
 *
 * It lives here rather than next to the bar because the bar is no longer its only caller: the
 * progress card's arrow leads to the same destination, and two ways of reaching one tab would be two
 * back stacks with nothing saying which one the user is in.
 */
internal fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Browses a store's catalogue. A `null` [categoryId] = the whole catalogue.
 *
 * `launchSingleTop`: the Home can open this screen with different categories, but tapping the same
 * chip twice must not push two identical copies.
 */
internal fun NavHostController.navigateToStoreListing(storeId: StoreId, categoryId: String?) {
    navigate(StoreListingRoute.of(storeId, categoryId)) { launchSingleTop = true }
}

/**
 * Opens the store page that requires a human gesture.
 *
 * It is the only place where the detail page and the assisted download know of each other, and it
 * could not be anywhere else: a `:feature:*` never depends on another `:feature:*`.
 */
internal fun NavHostController.navigateToWebViewDownload(request: UserAssistedRequest) {
    navigate(
        WebViewDownloadRoute.of(
            storeId = request.storeId,
            ref = request.ref,
            versionRef = request.versionRef,
            pageUrl = request.pageUrl,
            hint = request.hint,
        ),
    ) { launchSingleTop = true }
}

internal fun NavHostController.navigateToAppDetail(storeId: StoreId, ref: StoreAppRef) {
    // `launchSingleTop`: tapping the same result twice in quick succession must not push two copies of
    // the same page, which would then have to be closed twice with the back button.
    navigate(AppDetailRoute.of(storeId, ref)) { launchSingleTop = true }
}

/**
 * From "available on 3 stores" to another store's page: **it replaces, it does not stack**.
 *
 * Not `launchSingleTop`, and that was found on the device and could not have been found anywhere else:
 * `launchSingleTop` looks at the **destination**, not at its arguments, and here departure and arrival
 * are the same destination with different arguments. With the flag on, the tap produced nothing — no
 * error, no transition, the same page sitting still under the finger. From search it worked, because
 * there the top of the stack is a different destination: it is the app's only page → page jump, that is
 * the only case where the flag could bite.
 *
 * `popUpTo` instead, because every jump used to push an entry, justified as "the back button returns to
 * the page you started from, which is how two stores get compared". In use that does not hold:
 * comparing three stores leaves three pages on the stack, and getting back to search means walking back
 * through **all** of them, one at a time, re-crossing precisely the pages one had just decided to leave.
 * The "back" gesture stops meaning "return to where I came from".
 *
 * With `popUpTo<AppDetailRoute> { inclusive = true }` the chain never forms: the stack holds **at most
 * one** page, and the back button always returns to the screen one entered from — search, Home or "My
 * apps". That the rule applies from the very first jump is what makes it exact: `popUpTo` walks back to
 * the **nearest** match, so it only works because there is never more than one to walk back to.
 */
internal fun NavHostController.navigateToAnotherListing(storeId: StoreId, ref: StoreAppRef) {
    navigate(AppDetailRoute.of(storeId, ref)) {
        popUpTo<AppDetailRoute> { inclusive = true }
    }
}
