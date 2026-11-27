// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kestrel.core.designsystem.GlassNavBar
import com.kestrel.core.designsystem.GlassNavItem
import com.kestrel.core.designsystem.glassSource
import com.kestrel.core.designsystem.rememberGlassState
import com.kestrel.feature.details.DetailsScreen
import com.kestrel.feature.home.HomeScreen
import com.kestrel.feature.library.LibraryScreen
import com.kestrel.feature.player.PlayerScreen
import com.kestrel.feature.search.SearchScreen
import com.kestrel.feature.settings.SettingsDestination
import com.kestrel.feature.settings.SettingsScreen
import java.net.URLEncoder

sealed class KestrelRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    data object Home : KestrelRoute(
        route = "home",
        label = "Discover",
        icon = Icons.Outlined.Explore,
        selectedIcon = Icons.Filled.Explore
    )

    data object Search : KestrelRoute(
        route = "search",
        label = "Search",
        icon = Icons.Outlined.Search,
        selectedIcon = Icons.Filled.Search
    )

    data object Library : KestrelRoute(
        route = "library",
        label = "Library",
        icon = Icons.Outlined.Bookmarks,
        selectedIcon = Icons.Filled.Bookmarks
    )

    data object Settings : KestrelRoute(
        route = "settings",
        label = "Settings",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings
    )
}

private val TopLevelRoutes = listOf(
    KestrelRoute.Home,
    KestrelRoute.Search,
    KestrelRoute.Library,
    KestrelRoute.Settings
)

private fun detailsRoute(providerId: String, url: String): String {
    val encProvider = URLEncoder.encode(providerId, "UTF-8")
    val encUrl = URLEncoder.encode(url, "UTF-8")
    return "details?providerId=$encProvider&url=$encUrl"
}

@Composable
fun KestrelAppShell() {
    val navController = rememberNavController()
    val shellGlass = rememberGlassState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val selectedIndex = TopLevelRoutes.indexOfFirst { route ->
        currentDestination?.hierarchy?.any { it.route == route.route } == true
    }
    val showBottomBar = selectedIndex >= 0
    val navItems = remember {
        TopLevelRoutes.map { route ->
            GlassNavItem(
                label = route.label,
                icon = route.icon,
                selectedIcon = route.selectedIcon
            )
        }
    }

    var pendingSettingsDestination by remember { mutableStateOf<SettingsDestination?>(null) }
    var settingsNavNonce by remember { mutableIntStateOf(0) }

    val navigateToTopTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openSourcesInSettings: () -> Unit = {
        pendingSettingsDestination = SettingsDestination.CONTENT_DISCOVERY
        settingsNavNonce++
        navigateToTopTab(KestrelRoute.Settings.route)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = KestrelRoute.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .glassSource(shellGlass)
        ) {
            composable(KestrelRoute.Home.route) {
                HomeScreen(
                    onOpenExtensions = openSourcesInSettings,
                    onOpenMedia = { media ->
                        navController.navigate(detailsRoute(media.providerId, media.url))
                    }
                )
            }
            composable(KestrelRoute.Search.route) {
                SearchScreen(
                    onOpenExtensions = openSourcesInSettings,
                    onOpenMedia = { media ->
                        navController.navigate(detailsRoute(media.providerId, media.url))
                    }
                )
            }
            composable(KestrelRoute.Library.route) {
                LibraryScreen(
                    onOpenEntry = { entry ->
                        navController.navigate(detailsRoute(entry.providerId, entry.url))
                    },
                    onOpenExtensions = openSourcesInSettings
                )
            }
            composable(KestrelRoute.Settings.route) {
                SettingsScreen(
                    initialDestination = pendingSettingsDestination,
                    navigationNonce = settingsNavNonce
                )
            }
            composable(
                route = "details?providerId={providerId}&url={url}",
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                DetailsScreen(
                    onBack = { navController.popBackStack() },
                    onPlayStarted = { navController.navigate("player") },
                    onOpenMedia = { media ->
                        navController.navigate(detailsRoute(media.providerId, media.url))
                    }
                )
            }
            composable("player") {
                PlayerScreen(onBack = { navController.popBackStack() })
            }
        }

        if (showBottomBar) {
            GlassNavBar(
                items = navItems,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    val dest = TopLevelRoutes[index]
                    if (dest == KestrelRoute.Settings) {
                        pendingSettingsDestination = null
                    }
                    navigateToTopTab(dest.route)
                },
                state = shellGlass,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            )
        }
    }
}
