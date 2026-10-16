package com.pablo.yumemi.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.ui.chat.ChatScreen
import com.pablo.yumemi.ui.cron.CronResultsScreen
import com.pablo.yumemi.ui.cron.CronScreen
import com.pablo.yumemi.ui.settings.SettingsScreen
import com.pablo.yumemi.ui.settings.SettingsViewModel
import com.pablo.yumemi.ui.theme.Yu
import com.pablo.yumemi.ui.threads.ThreadListScreen

object Routes {
    const val THREADS = "threads"
    const val CHAT = "chat/{threadId}"
    const val SETTINGS = "settings"
    const val CRON = "cron"
    const val RESULTS = "cron_results"
    fun chat(threadId: String) = "chat/${Uri.encode(threadId)}"
}

private const val DUR = 380
private const val TAB_DUR = 220

private val TAB_ROUTES = setOf(Routes.THREADS, Routes.CRON, Routes.RESULTS)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.THREADS, "Chats", Icons.Rounded.Forum),
    Tab(Routes.CRON, "Cronjobs", Icons.Rounded.Schedule),
    Tab(Routes.RESULTS, "Resultados", Icons.Rounded.MarkEmailRead),
)

private fun NavBackStackEntry.isTab(): Boolean = destination.route in TAB_ROUTES

// Switching between tabs fades; anything else falls back to the NavHost slide (null).
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(): EnterTransition? =
    if (initialState.isTab() && targetState.isTab()) fadeIn(tween(TAB_DUR)) else null

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(): ExitTransition? =
    if (initialState.isTab() && targetState.isTab()) fadeOut(tween(TAB_DUR)) else null

@Composable
fun YumemiApp() {
    val nav = rememberNavController()
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    // Decided once, at launch: no URL configured yet -> first-run settings.
    val firstRun = remember { settingsVm.uiState.value.baseUrl.isBlank() }
    val start = if (firstRun) Routes.SETTINGS else Routes.THREADS

    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = currentRoute in TAB_ROUTES,
                enter = slideInVertically(tween(TAB_DUR + 80)) { it } + fadeIn(tween(TAB_DUR)),
                exit = slideOutVertically(tween(TAB_DUR)) { it } + fadeOut(tween(TAB_DUR)),
            ) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(Routes.THREADS) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Yu.PeriwinkleDeep,
                                selectedTextColor = Yu.PeriwinkleDeep,
                                indicatorColor = Yu.LilacSoft,
                                unselectedIconColor = Yu.Muted,
                                unselectedTextColor = Yu.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // Inner screens have their own Scaffolds: hand them only the bottom bar's height and
        // mark it as consumed so they don't add the navigation-bar inset a second time.
        val bottom = PaddingValues(bottom = innerPadding.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            enterTransition = { slideInHorizontally(tween(DUR)) { it / 5 } + fadeIn(tween(DUR)) },
            exitTransition = { slideOutHorizontally(tween(DUR)) { -it / 6 } + fadeOut(tween(DUR / 2)) },
            popEnterTransition = { slideInHorizontally(tween(DUR)) { -it / 6 } + fadeIn(tween(DUR)) },
            popExitTransition = { slideOutHorizontally(tween(DUR)) { it / 5 } + fadeOut(tween(DUR / 2)) },
        ) {
        composable(
            route = Routes.THREADS,
            enterTransition = { tabEnter() },
            exitTransition = { tabExit() },
            popEnterTransition = { tabEnter() },
            popExitTransition = { tabExit() },
        ) {
            ThreadListScreen(
                onOpenChat = { id -> nav.navigate(Routes.chat(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CRON,
            enterTransition = { tabEnter() },
            exitTransition = { tabExit() },
            popEnterTransition = { tabEnter() },
            popExitTransition = { tabExit() },
        ) {
            CronScreen()
        }
        composable(
            route = Routes.RESULTS,
            enterTransition = { tabEnter() },
            exitTransition = { tabExit() },
            popEnterTransition = { tabEnter() },
            popExitTransition = { tabExit() },
        ) {
            CronResultsScreen()
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("threadId") { type = NavType.StringType }),
        ) {
            ChatScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                canGoBack = !firstRun || nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onSaved = {
                    if (firstRun && nav.previousBackStackEntry == null) {
                        nav.navigate(Routes.THREADS) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
    }
    }
}
