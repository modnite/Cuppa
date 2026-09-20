package com.cuppa.app.ui.screens

import androidx.compose.foundation.layout.widthIn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuppa.app.BuildConfig
import com.cuppa.app.ui.theme.ThemeState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.cuppa.app.util.PermissionManager
import com.cuppa.app.util.RootHelper
import com.cuppa.app.util.ShizukuHelper
import com.cuppa.app.util.UsbPermissionHelper
import com.cuppa.cups.CupsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * SettingsScreen — Complete Server configuration, Privileged Access, and App management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToDrivers: () -> Unit = {},
    onNavigateToThermal: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE) }

    var selectedPort by remember { mutableIntStateOf(prefs.getInt("server_port", 631)) }

    // Kept only for the low-key diagnostic/about-screen info rows — Cuppa doesn't need root or
    // Shizuku on virtually any real device (port 631 binds fine without them), so there's no
    // prominent UI asking the user to grant anything; RootHelper/ShizukuHelper remain as silent
    // best-effort fallbacks in IppServer for the rare device that does need the privilege.
    val hasRoot = remember { RootHelper.isRootAvailable() }

    // Dialog control states
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var showNetworkDiscoveryDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showHelpSupportDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ShizukuHelper.updateState()
    }

    // Two columns of sections once the window is wide enough; one column otherwise.
    val wideSettings = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 900

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Settings",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .widthIn(max = if (wideSettings) 1600.dp else 960.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            // Permissions & System Access Section
            val settingsLeft: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            SettingsGroup(title = "Permissions & System Access") {
                var notifGranted by remember { mutableStateOf(PermissionManager.hasNotificationPermission(context)) }
                var batteryExempt by remember { mutableStateOf(PermissionManager.isBatteryOptimizationIgnored(context)) }
                val permEvent by UsbPermissionHelper.permissionEvent.collectAsState()
                val connectedUsb = remember(permEvent) { PermissionManager.getConnectedUsbDevices(context) }
                val lifecycleOwner = LocalLifecycleOwner.current
                val permScope = rememberCoroutineScope()

                // Re-check both permissions on every resume (returning from either system
                // dialog). Battery exemption specifically also gets a delayed second check:
                // PowerManager.isIgnoringBatteryOptimizations() can lag briefly behind the
                // system dialog's own confirmation — most noticeably on Samsung's battery
                // management layer — so an immediate re-check right on resume can still read
                // the pre-grant value even though the user just approved it.
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            notifGranted = PermissionManager.hasNotificationPermission(context)
                            batteryExempt = PermissionManager.isBatteryOptimizationIgnored(context)
                            permScope.launch {
                                delay(700)
                                batteryExempt = PermissionManager.isBatteryOptimizationIgnored(context)
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                SettingsItem(
                    icon = Icons.Outlined.Usb,
                    title = "USB Hardware Access",
                    subtitle = if (connectedUsb.isEmpty()) "No USB devices connected"
                               else "${connectedUsb.size} device(s) connected • ${connectedUsb.count { it.second }} permitted",
                    onClick = {
                        UsbPermissionHelper.checkAndRequestAllPrinters(context)
                    },
                )
                SettingsItem(
                    icon = Icons.Outlined.Notifications,
                    title = "Foreground Notifications",
                    subtitle = if (notifGranted) "Granted (Service stays running)" else "Permission missing (Tap to grant)",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            (context as? android.app.Activity)?.requestPermissions(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                                101
                            )
                        }
                    },
                )
                SettingsItem(
                    icon = Icons.Outlined.BatteryAlert,
                    title = "Battery Optimization",
                    subtitle = if (batteryExempt) "Exempt (Background printing enabled)" else "Restricted by Android Doze (Tap to exempt)",
                    onClick = {
                        PermissionManager.requestIgnoreBatteryOptimization(context)
                    },
                )
            }

            // Server Configuration Section
            SettingsGroup(title = "Server") {
                SettingsItem(
                    icon = Icons.Outlined.Tune,
                    title = "Server Configuration",
                    subtitle = "Active port: $selectedPort, boot auto-start, spool cache",
                    onClick = { showServerConfigDialog = true },
                )
                SettingsItem(
                    icon = Icons.Outlined.Lan,
                    title = "Network & Discovery",
                    subtitle = "mDNS advertising, AirPrint emulation, local IP",
                    onClick = { showNetworkDiscoveryDialog = true },
                )
                SettingsItem(
                    icon = Icons.Outlined.Security,
                    title = "Security",
                    subtitle = "Subnet restrictions, authentication, TLS mode",
                    onClick = { showSecurityDialog = true },
                )
            }

            // Drivers & Printers
            }
            val settingsRight: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            SettingsGroup(title = "Drivers & Printers") {
                SettingsItem(
                    icon = Icons.Outlined.Extension,
                    title = "Driver Management",
                    subtitle = "Installed drivers, PPDs, custom drivers",
                    onClick = onNavigateToDrivers,
                )
                SettingsItem(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "Thermal Printer Settings",
                    subtitle = "ESC/POS, ZPL, EPL presets, label sizes, polarity",
                    onClick = onNavigateToThermal,
                )
            }

            // App Section
            SettingsGroup(title = "App") {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    title = "Appearance",
                    subtitle = "Theme mode, dynamic color (Material You)",
                    onClick = { showAppearanceDialog = true },
                )
                SettingsItem(
                    icon = Icons.Outlined.BugReport,
                    title = "Logging & Diagnostics",
                    subtitle = "Real-time logcat viewer, export logs",
                    onClick = { showDiagnosticsDialog = true },
                )
                SettingsItem(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = "Help & Support",
                    subtitle = "AirPrint, Windows setup, root & thermal guides",
                    onClick = { showHelpSupportDialog = true },
                )
                run {
                    val availableUpdate by com.cuppa.app.update.UpdateManager.availableUpdate.collectAsState()
                    SettingsItem(
                        icon = Icons.Outlined.SystemUpdate,
                        title = "Check for Updates",
                        subtitle = availableUpdate?.let { "Update available: v${it.versionName}" }
                            ?: "Up to date (v${BuildConfig.VERSION_NAME})",
                        onClick = { showUpdateDialog = true },
                    )
                }
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About Cuppa",
                    subtitle = "v${BuildConfig.VERSION_NAME} • ${CupsEngine.getVersion()}",
                    onClick = { showAboutDialog = true },
                )
            }
            }
            if (wideSettings) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f), content = settingsLeft)
                    Column(modifier = Modifier.weight(1f), content = settingsRight)
                }
            } else {
                settingsLeft()
                settingsRight()
            }
        }
    }

    // ==========================================
    // DIALOGS
    // ==========================================

    if (showServerConfigDialog) {
        ServerConfigDialog(
            currentPort = selectedPort,
            onDismiss = { showServerConfigDialog = false },
            onPortChanged = { newPort ->
                selectedPort = newPort
                prefs.edit().putInt("server_port", newPort).apply()
                com.cuppa.app.service.CupsPrintService.restart(context)
            }
        )
    }

    if (showNetworkDiscoveryDialog) {
        NetworkDiscoveryDialog(
            currentPort = selectedPort,
            onDismiss = { showNetworkDiscoveryDialog = false }
        )
    }

    if (showSecurityDialog) {
        SecurityDialog(onDismiss = { showSecurityDialog = false })
    }

    if (showAppearanceDialog) {
        AppearanceDialog(onDismiss = { showAppearanceDialog = false })
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(port = selectedPort, hasRoot = hasRoot, onDismiss = { showDiagnosticsDialog = false })
    }

    if (showHelpSupportDialog) {
        HelpSupportDialog(onDismiss = { showHelpSupportDialog = false })
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showUpdateDialog) {
        UpdateDialog(onDismiss = { showUpdateDialog = false })
    }
}

// ==========================================
// 1. SERVER CONFIG DIALOG
// ==========================================
@Composable
private fun ServerConfigDialog(
    currentPort: Int,
    onDismiss: () -> Unit,
    onPortChanged: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE) }
    var portInput by remember { mutableStateOf(currentPort.toString()) }
    var autoStartBoot by remember { mutableStateOf(prefs.getBoolean("auto_start_boot", false)) }

    val spoolDir = remember { File(context.filesDir, "cups/spool") }
    var spoolFileCount by remember { mutableIntStateOf(spoolDir.listFiles()?.size ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server Configuration") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "IPP Server Port",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "631 is the standard IPP port and binds directly on Android without any special privilege on virtually all devices. If it's ever unavailable, Cuppa automatically falls back to an alternate port — the Dashboard will say so.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { portInput = "631" }, modifier = Modifier.weight(1f)) {
                        Text("631")
                    }
                    FilledTonalButton(onClick = { portInput = "8631" }, modifier = Modifier.weight(1f)) {
                        Text("8631")
                    }
                    FilledTonalButton(onClick = { portInput = "9100" }, modifier = Modifier.weight(1f)) {
                        Text("9100")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Start on Boot", fontWeight = FontWeight.Medium)
                        Text(
                            "Launch print server when phone reboots",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoStartBoot,
                        onCheckedChange = {
                            autoStartBoot = it
                            prefs.edit().putBoolean("auto_start_boot", it).apply()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text("Spool Cache Management", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Spooled jobs directory: ${spoolDir.name} ($spoolFileCount cached files)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val files = spoolDir.listFiles() ?: emptyArray()
                        var deleted = 0
                        for (f in files) {
                            if (f.delete()) deleted++
                        }
                        spoolFileCount = spoolDir.listFiles()?.size ?: 0
                        Toast.makeText(context, "Cleaned $deleted cached spool files", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Spool Cache")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portInput.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        onPortChanged(port)
                        Toast.makeText(context, "Server port set to $port — restarting server", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        Toast.makeText(context, "Please enter a valid port between 1 and 65535", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// 2. NETWORK & DISCOVERY DIALOG
// ==========================================
@Composable
private fun NetworkDiscoveryDialog(
    currentPort: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE) }
    var mdnsEnabled by remember { mutableStateOf(prefs.getBoolean("mdns_enabled", true)) }
    var airprintCompat by remember { mutableStateOf(prefs.getBoolean("airprint_compat", true)) }

    val ipAddress = remember { getLocalIpAddress() }
    val baseIppUrl = remember(ipAddress, currentPort) { "ipp://$ipAddress:$currentPort/printers/" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network & Discovery") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("mDNS / DNS-SD Advertising", fontWeight = FontWeight.Medium)
                        Text(
                            "Broadcast printers to Wi-Fi via Bonjour",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = mdnsEnabled,
                        onCheckedChange = {
                            mdnsEnabled = it
                            prefs.edit().putBoolean("mdns_enabled", it).apply()
                            com.cuppa.app.service.CupsPrintService.restart(context)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AirPrint Emulation", fontWeight = FontWeight.Medium)
                        Text(
                            "Advertise Apple URF TXT records for iOS/macOS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = airprintCompat,
                        onCheckedChange = {
                            airprintCompat = it
                            prefs.edit().putBoolean("airprint_compat", it).apply()
                            com.cuppa.app.service.CupsPrintService.restart(context)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Active Network Interface",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "IP Address: $ipAddress", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "IPP Port: $currentPort", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Multicast Lock: Enabled (Active)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Base IPP Endpoint:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = baseIppUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("IPP URL", baseIppUrl))
                                Toast.makeText(context, "Copied IPP URL to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Base IPP URL")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

// ==========================================
// 3. SECURITY DIALOG
// ==========================================
@Composable
private fun SecurityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE) }
    var accessMode by remember { mutableIntStateOf(prefs.getInt("access_mode", 0)) } // 0=all, 1=local subnet, 2=localhost
    var httpAuth by remember { mutableStateOf(prefs.getBoolean("http_auth", false)) }
    var httpAuthUsername by remember { mutableStateOf(prefs.getString("http_auth_username", "") ?: "") }
    var httpAuthPassword by remember { mutableStateOf(prefs.getString("http_auth_password", "") ?: "") }
    var tlsMode by remember { mutableStateOf(prefs.getBoolean("tls_enabled", false)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security & Access Control") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Subnet Access Restriction",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                listOf("Allow All Network Devices (0.0.0.0/0)", "Local Subnet Only (e.g. 192.168.x.x)", "Localhost Only (127.0.0.1)").forEachIndexed { index, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                accessMode = index
                                prefs.edit().putInt("access_mode", index).apply()
                                com.cuppa.app.service.CupsPrintService.restart(context)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = accessMode == index,
                            onClick = {
                                accessMode = index
                                prefs.edit().putInt("access_mode", index).apply()
                                com.cuppa.app.service.CupsPrintService.restart(context)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Applies to every incoming connection — devices printing to Cuppa, and any browser hitting its status page. Restarts the server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require credentials to print", fontWeight = FontWeight.Medium)
                        Text(
                            "HTTP Basic Auth on every incoming print request. Restarts the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = httpAuth,
                        onCheckedChange = {
                            httpAuth = it
                            prefs.edit().putBoolean("http_auth", it).apply()
                            com.cuppa.app.service.CupsPrintService.restart(context)
                        }
                    )
                }

                if (httpAuth) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = httpAuthUsername,
                        onValueChange = {
                            httpAuthUsername = it
                            prefs.edit().putString("http_auth_username", it).apply()
                        },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = httpAuthPassword,
                        onValueChange = {
                            httpAuthPassword = it
                            prefs.edit().putString("http_auth_password", it).apply()
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { com.cuppa.app.service.CupsPrintService.restart(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply Credentials")
                    }
                    if (httpAuthUsername.isBlank() || httpAuthPassword.isBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Set both fields — an empty username or password blocks all printing until credentials are set.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require IPPS (TLS) for incoming jobs", fontWeight = FontWeight.Medium)
                        Text(
                            "Self-signed cert — other devices printing to Cuppa must connect over TLS. Restarts the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = tlsMode,
                        onCheckedChange = {
                            tlsMode = it
                            prefs.edit().putBoolean("tls_enabled", it).apply()
                            com.cuppa.app.service.CupsPrintService.restart(context)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Outgoing prints to other printers (like via Test Print) already use TLS automatically whenever the target printer requires it — this switch only affects devices printing to Cuppa itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 4. APPEARANCE DIALOG
// ==========================================
@Composable
private fun AppearanceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentDarkMode by ThemeState.darkMode.collectAsState()
    val currentDynamicColor by ThemeState.dynamicColor.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column {
                Text(
                    text = "Theme Mode",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val modes = listOf("System Default", "Light Mode", "Dark Mode")
                modes.forEachIndexed { index, modeLabel ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ThemeState.setDarkMode(context, index) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = currentDarkMode == index,
                            onClick = { ThemeState.setDarkMode(context, index) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = modeLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic Color (Material You)", fontWeight = FontWeight.Medium)
                        Text(
                            "Derive palette from system wallpaper (Android 12+)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = currentDynamicColor,
                        onCheckedChange = { ThemeState.setDynamicColor(context, it) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

// ==========================================
// 5. DIAGNOSTICS & LOGGING DIALOG
// ==========================================
@Composable
private fun DiagnosticsDialog(port: Int, hasRoot: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var logContent by remember { mutableStateOf("Capturing diagnostics and logcat buffer...") }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(logContent.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Logs successfully saved to file!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save logs: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun loadLogs() {
        scope.launch {
            isRefreshing = true
            logContent = withContext(Dispatchers.IO) {
                val cuppaLogs = com.cuppa.app.util.CuppaLog.getAllLogs()
                val logcatLogs = try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-t", "200")
                    )
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line).append("\n")
                    }
                    sb.toString().trim()
                } catch (e: Exception) {
                    ""
                }

                buildString {
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    appendLine("==========================================")
                    appendLine(" CUPPA PRINT SERVER DIAGNOSTIC LOGS")
                    appendLine(" Generated: $timeFormat")
                    appendLine(" App Version: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                    appendLine(" Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine(" Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
                    appendLine(" Server Port: $port")
                    appendLine(" CUPS Engine: ${CupsEngine.getVersion()}")
                    appendLine(" Root: ${if (hasRoot) "Available" else "Not Available"}")
                    appendLine("==========================================")
                    appendLine("\n[In-Memory Buffer]")
                    appendLine(cuppaLogs)
                    if (logcatLogs.isNotBlank()) {
                        appendLine("\n[System Logcat (Recent)]")
                        appendLine(logcatLogs)
                    }
                }
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadLogs()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Logging & Diagnostics")
                IconButton(onClick = { loadLogs() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Live Logcat Buffer (Cuppa Components):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logContent,
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons: Save to Folder (.txt), Share File (.txt), Clear
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                saveFileLauncher.launch("cuppa_logs_$timestamp.txt")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save .txt")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
                                        val logFile = File(logsDir, "cuppa_logs_$timestamp.txt")
                                        logFile.writeText(logContent, Charsets.UTF_8)

                                        val fileUri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            logFile
                                        )

                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "Cuppa Print Server Logs ($timestamp)")
                                            putExtra(Intent.EXTRA_STREAM, fileUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        withContext(Dispatchers.Main) {
                                            context.startActivity(Intent.createChooser(intent, "Share Cuppa Log File"))
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Error sharing log file: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share .txt")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            com.cuppa.app.util.CuppaLog.clear()
                            scope.launch(Dispatchers.IO) {
                                try {
                                    Runtime.getRuntime().exec("logcat -c")
                                } catch (_: Exception) {}
                            }
                            loadLogs()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Buffer")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 6. HELP & SUPPORT DIALOG
// ==========================================
@Composable
private fun HelpSupportDialog(onDismiss: () -> Unit) {
    var selectedSection by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help & Support Guide") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        icon = {},
                        label = { Text("AirPrint") }
                    )
                    SegmentedButton(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        icon = {},
                        label = { Text("Windows") }
                    )
                    SegmentedButton(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        icon = {},
                        label = { Text("Port") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (selectedSection) {
                    0 -> {
                        Text("iOS & macOS AirPrint Setup", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Ensure both your Apple device and this Android phone are connected to the exact same Wi-Fi network.\n" +
                                    "2. In Cuppa Settings, ensure mDNS Advertising and AirPrint Emulation are switched ON.\n" +
                                    "3. Start the Cuppa server on the Dashboard.\n" +
                                    "4. Open any document on your iPhone, iPad, or Mac, tap 'Print', and select your Cuppa printer directly from the list.\n" +
                                    "5. Zero drivers or third-party apps required on the client device.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    1 -> {
                        Text("Windows 10 & 11 Setup", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Open Windows Settings > Bluetooth & Devices > Printers & Scanners.\n" +
                                    "2. Click 'Add printer or scanner'.\n" +
                                    "3. Click 'The printer that I want isn't listed'.\n" +
                                    "4. Choose 'Select a shared printer by name' and enter:\n" +
                                    "   http://<android-ip>:631/printers/<printer_name>\n" +
                                    "   (check the Dashboard for the exact port in use).\n" +
                                    "5. Select 'Microsoft PS Class Driver' or generic IPP driver.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    2 -> {
                        Text("Server Port", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Port 631 is the standard IPP port and binds directly on virtually all Android devices — no root needed.\n" +
                                    "2. If your device is unusual and 631 can't be bound, Cuppa automatically falls back to an alternate port, shown on the Dashboard.\n" +
                                    "3. Either way, network discovery (mDNS/Bonjour) advertises whichever port is actually active, so AirPrint/IPP Everywhere clients find it automatically — the port number only matters for manually entering a URL (e.g. on Windows).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 7. ABOUT CUPPA DIALOG
// ==========================================
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("About Cuppa")
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Cuppa Print Server for Android",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Native Material Design 3 CUPS print server bridging USB thermal label printers and network printers over IPP Everywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                InfoRow("App Version:", "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                InfoRow("CUPS Engine:", CupsEngine.getVersion())

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                val context = LocalContext.current
                Text(
                    text = "github.com/modnite/Cuppa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/modnite/Cuppa"))
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "Licensed under Apache 2.0 & GNU GPL (CUPS core). Clean-room implementation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

// ==========================================
// 8. UPDATE DIALOG
// ==========================================
@Composable
private fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableUpdate by com.cuppa.app.update.UpdateManager.availableUpdate.collectAsState()
    val downloadState by com.cuppa.app.update.UpdateManager.downloadState.collectAsState()
    var isChecking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var autoCheckEnabled by remember { mutableStateOf(com.cuppa.app.update.UpdateManager.isAutoCheckEnabled(context)) }
    var canInstallPackages by remember { mutableStateOf(com.cuppa.app.update.UpdateManager.canInstallPackages(context)) }

    // Same class of bug as the battery-optimization/notification permission checks: granting
    // "install unknown apps" happens in a separate system Settings screen with no result
    // callback, so re-checking canInstallPackages() only on resume (not just once at first
    // composition) is what actually makes the Install button unlock without closing and
    // reopening this dialog.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstallPackages = com.cuppa.app.update.UpdateManager.canInstallPackages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Updates") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-check for updates", fontWeight = FontWeight.Medium)
                        Text(
                            "Checks once every few hours when the app opens. Never downloads or installs without you tapping below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCheckEnabled,
                        onCheckedChange = {
                            autoCheckEnabled = it
                            com.cuppa.app.update.UpdateManager.setAutoCheckEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                when (val ds = downloadState) {
                    is com.cuppa.app.update.DownloadState.Idle -> {
                        if (availableUpdate == null) {
                            Text(
                                text = "You're on the latest version (v${BuildConfig.VERSION_NAME}).",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            checkError?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isChecking = true
                                        checkError = null
                                        val result = com.cuppa.app.update.UpdateManager.checkForUpdate(context, force = true)
                                        result.onFailure { checkError = it.message ?: "Check failed" }
                                        isChecking = false
                                    }
                                },
                                enabled = !isChecking,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text("Check Now")
                            }
                        } else {
                            val update = availableUpdate!!
                            Text(
                                text = "Update available: v${update.versionName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (update.releaseNotes.isNotBlank()) {
                                Text(
                                    text = update.releaseNotes.take(1000),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Button(
                                onClick = { scope.launch { com.cuppa.app.update.UpdateManager.downloadUpdate(context, update) } },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download & Install")
                            }
                        }
                    }
                    is com.cuppa.app.update.DownloadState.Downloading -> {
                        val progress = if (ds.totalBytes > 0) ds.bytesDownloaded.toFloat() / ds.totalBytes else 0f
                        Text("Downloading update…", fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${ds.bytesDownloaded / 1024} KB / ${if (ds.totalBytes > 0) "${ds.totalBytes / 1024} KB" else "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is com.cuppa.app.update.DownloadState.ReadyToInstall -> {
                        Text("Download complete — ready to install.", fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!canInstallPackages) {
                            Text(
                                "Cuppa needs permission to install unknown apps first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { com.cuppa.app.update.UpdateManager.requestInstallPermission(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Permission")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { com.cuppa.app.update.UpdateManager.installApk(context, ds.file) },
                            enabled = canInstallPackages,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Install")
                        }
                    }
                    is com.cuppa.app.update.DownloadState.Failed -> {
                        Text(
                            text = "Download failed: ${ds.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { com.cuppa.app.update.UpdateManager.resetDownloadState() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        content()
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
    )
}

private fun getLocalIpAddress(): String =
    com.cuppa.app.util.NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
