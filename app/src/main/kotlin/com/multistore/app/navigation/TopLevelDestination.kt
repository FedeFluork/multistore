package com.multistore.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.multistore.app.R
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

// Type-safe routes: the destination is a type, not a string, so a typo is a compile error instead of a
// blank screen at runtime.
@Serializable data object HomeRoute

@Serializable data object SearchRoute

@Serializable data object MyAppsRoute

@Serializable data object SettingsRoute

/**
 * The bottom bar's four destinations.
 *
 * The filled icon marks the active destination and the outlined one the others: it is the signal
 * Material 3 expects, and it holds up even when the coloured indicator is not enough (colour blindness,
 * high contrast).
 */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    HOME(
        route = HomeRoute,
        routeClass = HomeRoute::class,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
        labelRes = R.string.nav_home,
    ),
    SEARCH(
        route = SearchRoute,
        routeClass = SearchRoute::class,
        selectedIcon = Icons.Rounded.Search,
        unselectedIcon = Icons.Outlined.Search,
        labelRes = R.string.nav_search,
    ),
    MY_APPS(
        route = MyAppsRoute,
        routeClass = MyAppsRoute::class,
        selectedIcon = Icons.Rounded.Apps,
        unselectedIcon = Icons.Outlined.Apps,
        labelRes = R.string.nav_myapps,
    ),
    SETTINGS(
        route = SettingsRoute,
        routeClass = SettingsRoute::class,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        labelRes = R.string.nav_settings,
    ),
}
