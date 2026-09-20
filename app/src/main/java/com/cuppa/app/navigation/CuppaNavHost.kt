package com.cuppa.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.cuppa.app.ui.screens.DashboardScreen
import com.cuppa.app.ui.screens.DriverManagementScreen
import com.cuppa.app.ui.screens.JobsScreen
import com.cuppa.app.ui.screens.PrintersScreen
import com.cuppa.app.ui.screens.SettingsScreen
import com.cuppa.app.ui.screens.ThermalSettingsScreen

/**
 * CuppaNavHost — Main navigation host for the app.
 *
 * Defines all composable destinations and transition animations.
 * The NavHostController is managed by MainActivity and passed down.
 */
@Composable
fun CuppaNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val animDuration = 300

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(animDuration)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(animDuration),
                    initialOffset = { it / 20 },
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(animDuration))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(animDuration))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(animDuration)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(animDuration),
                    targetOffset = { it / 20 },
                )
        },
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onOpenPrinters = {
                    navController.navigate(Screen.Printers.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        composable(Screen.Printers.route) {
            PrintersScreen()
        }

        composable(Screen.Jobs.route) {
            JobsScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToDrivers = { 
                    navController.navigate(Screen.DriverManagement.route) { launchSingleTop = true }
                },
                onNavigateToThermal = { 
                    navController.navigate(Screen.ThermalSettings.route) { launchSingleTop = true }
                },
            )
        }

        composable(Screen.DriverManagement.route) {
            DriverManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ThermalSettings.route) {
            ThermalSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
