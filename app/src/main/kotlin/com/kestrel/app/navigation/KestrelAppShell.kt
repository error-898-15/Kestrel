// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.feature.details.DetailsScreen
import com.kestrel.feature.extensions.ExtensionsScreen
import com.kestrel.feature.home.HomeScreen
import com.kestrel.feature.library.LibraryScreen
import com.kestrel.feature.player.PlayerScreen
import com.kestrel.feature.search.SearchScreen

sealed class KestrelRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Home : KestrelRoute("home", "Discover", Icons.Outlined.Explore)
    data object Search : KestrelRoute("search", "Search", Icons.Outlined.Search)
    data object Library : KestrelRoute("library", "Library", Icons.Outlined.Bookmarks)
    data object Extensions : KestrelRoute("extensions", "Extensions", Icons.Outlined.Extension)
}

private val TopLevelRoutes = listOf(
    KestrelRoute.Home,
    KestrelRoute.Search,
    KestrelRoute.Library,
    KestrelRoute.Extensions
)

@Composable
fun KestrelAppShell() {
    val navController = rememberNavController()
    val colors = KestrelTheme.colors
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevelRoutes.any { route ->
        currentDestination?.hierarchy?.any { it.route == route.route } == true
    }

    Scaffold(
        containerColor = colors.canvas,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = colors.surfaceLow) {
                    TopLevelRoutes.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accentAmber,
                                selectedTextColor = colors.accentAmber,
                                indicatorColor = colors.accentAmberDim,
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = KestrelRoute.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(KestrelRoute.Home.route) {
                HomeScreen(
                    onOpenExtensions = { navController.navigate(KestrelRoute.Extensions.route) }
                )
            }
            composable(KestrelRoute.Search.route) {
                SearchScreen(
                    onOpenExtensions = { navController.navigate(KestrelRoute.Extensions.route) }
                )
            }
            composable(KestrelRoute.Library.route) {
                LibraryScreen()
            }
            composable(KestrelRoute.Extensions.route) {
                ExtensionsScreen()
            }
            composable("details") {
                DetailsScreen(onBack = { navController.popBackStack() })
            }
            composable("player") {
                PlayerScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
