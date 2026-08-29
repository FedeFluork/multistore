package com.multistore.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.multistore.app.DownloadOverlayViewModel
import com.multistore.app.R
import com.multistore.app.navigation.MultiStoreNavHost
import com.multistore.app.navigation.TopLevelDestination
import com.multistore.app.navigation.navigateToDownloads
import com.multistore.app.navigation.navigateToTopLevel
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
    val downloadBadge by overlayViewModel.badge.collectAsStateWithLifecycle()

    // The bar exists only on the five top-level destinations. A detail page is not a bar tab: showing it
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
                            NavigationIcon(
                                destination = destination,
                                selected = selected,
                                // Only the Downloads tab wears a dot, and only it can: the badge
                                // answers "is something coming down or waiting for a tap", which is
                                // a question about that tab and no other.
                                badged = destination == TopLevelDestination.DOWNLOADS &&
                                    downloadBadge,
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
            // Not on the Downloads screen itself: the card is a signpost to that list, and on the
            // list it is the same rows twice — with less in them, and covering the first one.
            val onDownloads = currentDestination.isOn(TopLevelDestination.DOWNLOADS)
            val downloads by overlayViewModel.visible.collectAsStateWithLifecycle()
            DownloadOverlay(
                downloads = if (onDownloads) emptyList() else downloads,
                onDismiss = overlayViewModel::dismiss,
                // The arrow leads to the Downloads tab and does not open a second list of its own:
                // two surfaces showing the same rows are two places to keep in step, and the first
                // divergence between them would be invisible.
                onExpand = navController::navigateToDownloads,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * A bar icon, with the dot when there is something to say about that tab.
 *
 * The dot is a **dot** and not a count, and the reason is the label right under it: a number on the
 * icon and a word beneath it are two pieces of text competing in a 56dp-wide target, and the exact
 * figure is one tap away on the screen the tab opens. What a badge has to do here is say "look",
 * which is all a dot ever says.
 *
 * The `contentDescription` goes on the badge and not on the icon, which keeps its `null`: the label
 * is already drawn under it and TalkBack would otherwise read the tab's name twice. Without a
 * description of its own the dot would be a purely visual signal — invisible to exactly the people
 * who cannot see that it turned red.
 */
@Composable
private fun NavigationIcon(
    destination: TopLevelDestination,
    selected: Boolean,
    badged: Boolean,
) {
    val icon = @Composable {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            // The label is always visible under the icon: repeating it as a contentDescription
            // would have it announced twice.
            contentDescription = null,
        )
    }
    if (!badged) {
        icon()
        return
    }
    val badgeLabel = stringResource(R.string.nav_downloads_badge)
    BadgedBox(
        badge = { Badge(modifier = Modifier.semantics { contentDescription = badgeLabel }) },
        content = { icon() },
    )
}

/** `true` if the current destination, or an ancestor of it, is the menu entry's. */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true

/**
 * `true` while on one of the bar's five destinations.
 *
 * `null` — the very first composition, before the NavHost has a current destination — counts as
 * "top-level": the start destination is one, and making the bar appear a frame after the rest would
 * produce a visible jump on every launch.
 */
private fun NavDestination?.isTopLevel(): Boolean =
    this == null || TopLevelDestination.entries.any { isOn(it) }

