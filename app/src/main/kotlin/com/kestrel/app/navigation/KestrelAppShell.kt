// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.kestrel.feature.details.DetailsScreen
import com.kestrel.feature.home.HomeScreen
import com.kestrel.feature.library.LibraryScreen
import com.kestrel.feature.manga.MangaDetailsScreen
import com.kestrel.feature.manga.MangaReaderScreen
import com.kestrel.feature.manga.MangaScreen
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

    data object Manga : KestrelRoute(
        route = "manga",
        label = "Manga",
        icon = Icons.Outlined.AutoStories,
        selectedIcon = Icons.Filled.AutoStories
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
    KestrelRoute.Manga,
    KestrelRoute.Library,
    KestrelRoute.Settings
)

private fun detailsRoute(providerId: String, url: String): String {
    val encProvider = URLEncoder.encode(providerId, "UTF-8")
    val encUrl = URLEncoder.encode(url, "UTF-8")
    return "details?providerId=$encProvider&url=$encUrl"
}

private fun mangaDetailsRoute(extensionId: String, sourceId: String, mangaUrl: String): String {
    val encExt = URLEncoder.encode(extensionId, "UTF-8")
    val encSource = URLEncoder.encode(sourceId, "UTF-8")
    val encUrl = URLEncoder.encode(mangaUrl, "UTF-8")
    return "manga_details?extensionId=$encExt&sourceId=$encSource&mangaUrl=$encUrl"
}

private fun mangaReaderRoute(
    extensionId: String,
    sourceId: String,
    mangaUrl: String,
    chapterUrl: String
): String {
    val encExt = URLEncoder.encode(extensionId, "UTF-8")
    val encSource = URLEncoder.encode(sourceId, "UTF-8")
    val encManga = URLEncoder.encode(mangaUrl, "UTF-8")
    val encChapter = URLEncoder.encode(chapterUrl, "UTF-8")
    return "manga_reader?extensionId=$encExt&sourceId=$encSource&mangaUrl=$encManga&chapterUrl=$encChapter"
}

@Composable
fun KestrelAppShell() {
    val navController = rememberNavController()
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
    var navBarCollapsed by remember { mutableStateOf(false) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(selectedIndex, showBottomBar) {
        navBarCollapsed = false
        scrollAccumulator = 0f
    }

    val navBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val dy = consumed.y
                if (dy != 0f) {
                    if ((dy < 0f && scrollAccumulator > 0f) || (dy > 0f && scrollAccumulator < 0f)) {
                        scrollAccumulator = 0f
                    }
                    scrollAccumulator += dy
                    if (scrollAccumulator < -36f && !navBarCollapsed) {
                        navBarCollapsed = true
                        scrollAccumulator = 0f
                    } else if (scrollAccumulator > 22f && navBarCollapsed) {
                        navBarCollapsed = false
                        scrollAccumulator = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    val navigateToTopTab: (String) -> Unit = { route ->
        navBarCollapsed = false
        scrollAccumulator = 0f
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val openSourcesInSettings: () -> Unit = {
        pendingSettingsDestination = SettingsDestination.PLUGINS
        settingsNavNonce++
        navigateToTopTab(KestrelRoute.Settings.route)
    }

    val openMangaSourcesInSettings: () -> Unit = {
        pendingSettingsDestination = SettingsDestination.MANGA_SOURCE
        settingsNavNonce++
        navigateToTopTab(KestrelRoute.Settings.route)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(navBarScrollConnection)
    ) {
        NavHost(
            navController = navController,
            startDestination = KestrelRoute.Home.route,
            modifier = Modifier.fillMaxSize()
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
            composable(KestrelRoute.Manga.route) {
                MangaScreen(
                    onOpenMangaSourceSettings = openMangaSourcesInSettings,
                    onOpenManga = { manga ->
                        navController.navigate(
                            mangaDetailsRoute(manga.extensionId, manga.sourceId, manga.url)
                        )
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
            composable(
                route = "manga_details?extensionId={extensionId}&sourceId={sourceId}&mangaUrl={mangaUrl}",
                arguments = listOf(
                    navArgument("extensionId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mangaUrl") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                MangaDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onReadChapter = { extId, srcId, mangaUrl, chapterUrl ->
                        navController.navigate(
                            mangaReaderRoute(extId, srcId, mangaUrl, chapterUrl)
                        )
                    }
                )
            }
            composable(
                route = "manga_reader?extensionId={extensionId}&sourceId={sourceId}&mangaUrl={mangaUrl}&chapterUrl={chapterUrl}",
                arguments = listOf(
                    navArgument("extensionId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mangaUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("chapterUrl") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                MangaReaderScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        if (showBottomBar) {
            GlassNavBar(
                items = navItems,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    navBarCollapsed = false
                    val dest = TopLevelRoutes[index]
                    if (dest == KestrelRoute.Settings) {
                        pendingSettingsDestination = null
                    }
                    navigateToTopTab(dest.route)
                },
                collapsed = navBarCollapsed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            )
        }
    }
}
