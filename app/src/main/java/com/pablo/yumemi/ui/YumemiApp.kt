package com.pablo.yumemi.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.ui.chat.ChatScreen
import com.pablo.yumemi.ui.settings.SettingsScreen
import com.pablo.yumemi.ui.settings.SettingsViewModel
import com.pablo.yumemi.ui.threads.ThreadListScreen

object Routes {
    const val THREADS = "threads"
    const val CHAT = "chat/{threadId}"
    const val SETTINGS = "settings"
    fun chat(threadId: String) = "chat/${Uri.encode(threadId)}"
}

private const val DUR = 380

@Composable
fun YumemiApp() {
    val nav = rememberNavController()
    val settingsVm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    // Decided once, at launch: no URL configured yet -> first-run settings.
    val firstRun = remember { settingsVm.uiState.value.baseUrl.isBlank() }
    val start = if (firstRun) Routes.SETTINGS else Routes.THREADS

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier,
        enterTransition = { slideInHorizontally(tween(DUR)) { it / 5 } + fadeIn(tween(DUR)) },
        exitTransition = { slideOutHorizontally(tween(DUR)) { -it / 6 } + fadeOut(tween(DUR / 2)) },
        popEnterTransition = { slideInHorizontally(tween(DUR)) { -it / 6 } + fadeIn(tween(DUR)) },
        popExitTransition = { slideOutHorizontally(tween(DUR)) { it / 5 } + fadeOut(tween(DUR / 2)) },
    ) {
        composable(Routes.THREADS) {
            ThreadListScreen(
                onOpenChat = { id -> nav.navigate(Routes.chat(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
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
