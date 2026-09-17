package com.cuppa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.cuppa.app.navigation.CuppaNavHost
import com.cuppa.app.navigation.Screen
import com.cuppa.app.ui.theme.CuppaTheme
import com.cuppa.app.ui.theme.ThemeState
import com.cuppa.app.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * MainActivity — Single activity host for the Cuppa app.
 *
 * Uses a Scaffold with a Material 3 NavigationBar at the bottom.
 * All screen content is rendered via the CuppaNavHost composable.
 * Edge-to-edge display is enabled for a modern, immersive look.
 */
class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        com.cuppa.app.util.CuppaLog.i("MainActivity", "POST_NOTIFICATIONS permission result: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display (transparent system bars)
        enableEdgeToEdge()

        // 1. Request Notification Permission (Android 13+)
        requestNotificationPermission()

        // 2. Check and request USB Permissions for attached printers
        com.cuppa.app.util.UsbPermissionHelper.checkAndRequestAllPrinters(this)

        // 3. Failsafe for rooted devices: grant node permissions
        if (com.cuppa.app.util.RootHelper.isRootAvailable()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.cuppa.app.util.RootHelper.grantUsbPermissions(packageName)
            }
        }

        handleUsbIntent(intent)

        // Resume the print server if it was running last time the app was open but the process
        // has since died (force-stop, low-memory kill, etc). This is independent of "Auto-Start
        // on Boot", which only fires on an actual device reboot (BootReceiver) — without this,
        // a killed-and-reopened app would silently stay stopped until the user manually tapped
        // Start Service again.
        resumeServerIfNeeded()

        setContent {
            val darkMode by ThemeState.darkMode.collectAsState()
            val dynamicColor by ThemeState.dynamicColor.collectAsState()
            val isDark = when (darkMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            CuppaTheme(darkTheme = isDark, dynamicColor = dynamicColor) {
                CuppaApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.cuppa.app.util.UsbPermissionHelper.checkAndRequestAllPrinters(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun resumeServerIfNeeded() {
        val prefs = getSharedPreferences("cuppa_settings", MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean("server_should_be_running", false)
        if (shouldBeRunning && !com.cuppa.cups.CupsEngine.isServerRunning()) {
            com.cuppa.app.util.CuppaLog.i("MainActivity", "Server was running last session but process was killed — resuming")
            com.cuppa.app.service.CupsPrintService.start(this)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleUsbIntent(intent: android.content.Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                val desc = com.cuppa.app.util.UsbPermissionHelper.let {
                    device.deviceName + " (VID 0x" + Integer.toHexString(device.vendorId) + ")"
                }
                com.cuppa.app.util.CuppaLog.i("MainActivity", "USB Device attached intent received for: $desc")
                com.cuppa.app.util.UsbPermissionHelper.requestUsbPermission(this, device)
            }
        }
    }
}

/**
 * Root composable for the Cuppa app.
 *
 * Manages the NavController and renders the Scaffold with bottom navigation.
 */
@Composable
fun CuppaApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current

    // Track the selected index for bottom nav animation
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    // Throttled (at most once per 6h) background check, gated by the "Auto-check for updates"
    // setting — never downloads or installs anything on its own, just surfaces availability via
    // UpdateManager.availableUpdate for the Settings screen (and the bottom-nav badge below).
    LaunchedEffect(Unit) {
        UpdateManager.checkForUpdate(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            bottomBar = {
                CuppaBottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { screen ->
                        selectedIndex = Screen.bottomNavItems.indexOf(screen)
                        navController.navigate(screen.route) {
                            // Pop to the start destination to avoid building a large back stack
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            // Avoid multiple copies of the same destination
                            launchSingleTop = true
                            // Restore state when re-selecting a previously selected tab
                            restoreState = true
                        }
                    },
                )
            },
        ) { innerPadding ->
            CuppaNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * Material 3 bottom navigation bar with animated icon selection.
 */
@Composable
private fun CuppaBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
) {
    val availableUpdate by UpdateManager.availableUpdate.collectAsState()

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Screen.bottomNavItems.forEach { screen ->
            val isSelected = currentRoute == screen.route
            val showUpdateBadge = availableUpdate != null && screen == Screen.Settings

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(screen) },
                icon = {
                    if (showUpdateBadge) {
                        androidx.compose.material3.BadgedBox(
                            badge = { androidx.compose.material3.Badge() }
                        ) {
                            Icon(
                                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                            contentDescription = screen.title,
                        )
                    }
                },
                label = { Text(screen.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
