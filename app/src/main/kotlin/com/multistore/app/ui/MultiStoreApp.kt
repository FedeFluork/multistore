package com.multistore.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.multistore.app.DownloadOverlayViewModel
import com.multistore.app.R
import com.multistore.app.navigation.MultiStoreNavHost
import com.multistore.app.navigation.TopLevelDestination
import com.multistore.core.ui.component.DownloadOverlay

/**
 * The app shell: bottom bar plus NavHost.
 *
 * The top bar is not here but inside each screen, because the title and the actions belong to the
 * feature: centralising them here would force `:app` to know every feature's strings.
 */
@Composable
fun MultiStoreApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    overlayViewModel: DownloadOverlayViewModel = hiltViewModel(),
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val navigationLabel = stringResource(R.string.nav_bar_content_description)

    // The bar exists only on the four top-level destinations. A detail page is not a bar tab: showing it
    // there would mean offering two exit gestures that do different things — the back button returns to
    // the list, the menu entry starts over from the Home — with nothing explaining the difference.
    val showNavigationBar = currentDestination.isTopLevel()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!showNavigationBar) return@Scaffold
            NavigationBar(
                modifier = Modifier.semantics { contentDescription = navigationLabel },
            ) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination.isOn(destination)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                // The label is always visible under the icon: repeating it as a
                                // contentDescription would have it announced twice.
                                contentDescription = null,
                            )
                        },
                        label = { Text(text = stringResource(destination.labelRes)) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(),
                    )
                }
            }
        },
    ) { innerPadding ->
        // The progress card sits **above** the NavHost and below the navigation bar: it is part of the
        // shell, like the bar, and no feature could draw it above the others without depending on them.
        // The `Box` overlays it rather than shrinking the content: a card that pushed the page up would
        // make it jump every time a download starts.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            MultiStoreNavHost(
                navController = navController,
                // We consume only the bottom padding, the bottom bar's: at the top every screen has its
                // own top bar, which handles the status inset itself.
                modifier = Modifier.fillMaxSize(),
            )
            val downloads by overlayViewModel.visible.collectAsStateWithLifecycle()
            DownloadOverlay(
                downloads = downloads,
                onDismiss = overlayViewModel::dismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** `true` if the current destination, or an ancestor of it, is the menu entry's. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true

/**
 * `true` while on one of the bar's four destinations.
 *
 * `null` — the very first composition, before the NavHost has a current destination — counts as
 * "top-level": the start destination is one, and making the bar appear a frame after the rest would
 * produce a visible jump on every launch.
 */
private fun NavDestination?.isTopLevel(): Boolean =
    this == null || TopLevelDestination.entries.any { isOn(it) }

/**
 * Navigation between the top-level destinations.
 *
 * `launchSingleTop` and the `popUpTo` on the start destination ensure that tapping the bottom bar's
 * entries repeatedly does not build a deep stack: the back button returns to the Home, it does not
 * retrace every tab visited.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
