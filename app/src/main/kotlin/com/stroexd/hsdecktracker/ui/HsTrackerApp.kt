package com.stroexd.hsdecktracker.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.overlay.rememberPlayAndTrack
import com.stroexd.hsdecktracker.ui.decks.DeckBuilderScreen
import com.stroexd.hsdecktracker.ui.decks.DeckDetailScreen
import com.stroexd.hsdecktracker.ui.decks.DecksScreen
import com.stroexd.hsdecktracker.ui.meta.ChinaMetaScreen
import com.stroexd.hsdecktracker.ui.settings.SettingsScreen
import com.stroexd.hsdecktracker.ui.tracker.TrackerScreen
import com.stroexd.hsdecktracker.ui.tracker.PredictionScreen

object Routes {
    const val DECKS = "decks"
    const val META = "meta"
    const val DECK = "deck/{id}"
    const val BUILDER = "builder?id={id}&cls={cls}&fmt={fmt}"
    const val TRACKER = "tracker"
    const val PREDICTION = "prediction"
    const val SETTINGS = "settings"

    fun deck(id: String) = "deck/${Uri.encode(id)}"
    fun builder(id: String? = null, cls: HsClass? = null, format: GameFormat? = null) =
        "builder?id=${Uri.encode(id.orEmpty())}&cls=${cls?.name.orEmpty()}&fmt=${format?.name.orEmpty()}"
}

private data class TopLevel(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.DECKS, R.string.tab_decks, Icons.Filled.Style),
    TopLevel(Routes.META, R.string.tab_meta, Icons.Filled.Leaderboard),
)

@Composable
fun HsTrackerApp(
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
    openTracker: Boolean,
    onTrackerOpened: () -> Unit,
    playRequested: Boolean = false,
    onPlayHandled: () -> Unit = {},
) {
    val navController = key("log-navigation") { rememberNavController() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevel.any { it.route == currentRoute }

    LaunchedEffect(sharedText) {
        if (sharedText != null && currentRoute != Routes.DECKS) navController.navigateTopLevel(Routes.DECKS)
    }
    val startTracking = rememberPlayAndTrack {
        navController.navigate(Routes.TRACKER) { launchSingleTop = true }
    }
    LaunchedEffect(playRequested) {
        if (playRequested) {
            onPlayHandled()
            startTracking()
        }
    }
    LaunchedEffect(openTracker) {
        if (openTracker) {
            navController.navigate(Routes.TRACKER) { launchSingleTop = true }
            onTrackerOpened()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DECKS,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.DECKS) {
                DecksScreen(
                    navController = navController,
                    sharedText = sharedText,
                    onSharedTextHandled = onSharedTextHandled,
                )
            }
            composable(Routes.META) { ChinaMetaScreen(navController) }
            composable(Routes.DECK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                DeckDetailScreen(navController, entry.arguments?.getString("id").orEmpty())
            }
            composable(
                Routes.BUILDER,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("cls") { type = NavType.StringType; defaultValue = "" },
                    navArgument("fmt") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                DeckBuilderScreen(
                    navController = navController,
                    deckId = args?.getString("id")?.takeIf { it.isNotBlank() },
                    initialClass = args?.getString("cls")?.takeIf { it.isNotBlank() }?.let { HsClass.fromString(it) },
                    initialFormat = args?.getString("fmt")?.takeIf { it.isNotBlank() }?.let { GameFormat.fromString(it) },
                )
            }
            composable(Routes.TRACKER) { TrackerScreen(navController) }
            composable(Routes.PREDICTION) { PredictionScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
        }
    }
}

fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
