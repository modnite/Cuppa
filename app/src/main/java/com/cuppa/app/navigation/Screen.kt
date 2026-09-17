package com.cuppa.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sealed class representing the navigation destinations in Cuppa.
 *
 * Each destination has:
 * - [route]: Unique string identifier for navigation
 * - [title]: Display name for the screen
 * - [selectedIcon]: Filled icon when this destination is active
 * - [unselectedIcon]: Outlined icon when this destination is inactive
 */
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Dashboard : Screen(
        route = "dashboard",
        title = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
    )

    data object Printers : Screen(
        route = "printers",
        title = "Printers",
        selectedIcon = Icons.Filled.Print,
        unselectedIcon = Icons.Outlined.Print,
    )

    data object Jobs : Screen(
        route = "jobs",
        title = "Jobs",
        selectedIcon = Icons.Filled.Receipt,
        unselectedIcon = Icons.Outlined.Receipt,
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )

    data object DriverManagement : Screen(
        route = "driver_management",
        title = "Driver Management",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )

    data object ThermalSettings : Screen(
        route = "thermal_settings",
        title = "Thermal Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )

    companion object {
        /** All bottom navigation destinations in display order. */
        val bottomNavItems = listOf(Dashboard, Printers, Jobs, Settings)
    }
}
