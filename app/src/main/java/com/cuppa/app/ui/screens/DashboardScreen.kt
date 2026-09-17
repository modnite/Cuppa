package com.cuppa.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.collectAsState
import com.cuppa.app.util.PermissionManager
import com.cuppa.app.util.UsbPermissionHelper
import com.cuppa.app.util.safeDescription
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cuppa.app.data.ServerState
import com.cuppa.app.ui.theme.ServerRunning
import com.cuppa.app.ui.theme.ServerStopped
import com.cuppa.app.viewmodel.DashboardViewModel

/**
 * DashboardScreen — Main overview of the Cuppa print server.
 *
 * Phase 1: Real native CUPS integration:
 * - Real CUPS version string and server state from CupsEngine
 * - Start/Stop foreground service control
 * - Real printer & job statistics
 * - Direct IPP printer connectivity testing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isRunning = uiState.serverState is ServerState.Running

    val statusColor by animateColorAsState(
        targetValue = if (isRunning) ServerRunning else ServerStopped,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "statusColor",
    )

    val statusScale by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "statusScale",
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("cuppa_settings", android.content.Context.MODE_PRIVATE) }
    val currentPort = when (val s = uiState.serverState) {
        is ServerState.Running -> s.port
        else -> prefs.getInt("server_port", 631)
    }
    val deviceIp = remember { com.cuppa.app.util.NetworkUtils.getLocalIpAddress() ?: "127.0.0.1" }
    val defaultUri = remember(currentPort, deviceIp, uiState.printers) {
        val first = uiState.printers.firstOrNull()
        if (first != null) {
            com.cuppa.app.util.NetworkUtils.getPrinterIppUri(first.name, currentPort)
        } else {
            "ipp://$deviceIp:$currentPort/ipp/print"
        }
    }

    var testUriInput by remember(defaultUri) { mutableStateOf(defaultUri) }

    var hasNotifPermission by remember { mutableStateOf(PermissionManager.hasNotificationPermission(context)) }
    var isBatteryExempt by remember { mutableStateOf(PermissionManager.isBatteryOptimizationIgnored(context)) }
    val permEvent by UsbPermissionHelper.permissionEvent.collectAsState()
    val unpermittedPrinters = remember(permEvent) { PermissionManager.getUnpermittedUsbPrinters(context) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
    }

    // Battery-optimization exemption has no result-callback API (unlike the notification
    // permission launcher above) — the request intent is fire-and-forget, so re-checking right
    // after starting it (as this used to do) always reads the pre-grant value since the system
    // dialog hasn't even appeared yet. Re-check on resume instead, with a delayed second check
    // since PowerManager.isIgnoringBatteryOptimizations() can lag briefly behind the dialog's
    // own confirmation (most noticeable on Samsung's battery management layer).
    val lifecycleOwner = LocalLifecycleOwner.current
    val permScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryExempt = PermissionManager.isBatteryOptimizationIgnored(context)
                permScope.launch {
                    delay(700)
                    isBatteryExempt = PermissionManager.isBatteryOptimizationIgnored(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        LargeTopAppBar(
            title = {
                Text(
                    text = "Dashboard",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Permission & Device Access Warning Banner ----
            if (unpermittedPrinters.isNotEmpty() || !hasNotifPermission || !isBatteryExempt) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Permissions & Device Access",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (unpermittedPrinters.isNotEmpty()) {
                            for (device in unpermittedPrinters) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "USB Printer Detected",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = device.safeDescription(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = { UsbPermissionHelper.requestUsbPermission(context, device) }
                                    ) {
                                        Text("Grant")
                                    }
                                }
                            }
                        }

                        if (!hasNotifPermission) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Notifications",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Required for CUPS foreground print service",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                ) {
                                    Text("Allow")
                                }
                            }
                        }

                        if (!isBatteryExempt) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.BatteryAlert, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Background Execution",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Exempt from battery optimization for 24/7 server",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        PermissionManager.requestIgnoreBatteryOptimization(context)
                                    }
                                ) {
                                    Text("Exempt")
                                }
                            }
                        }
                    }
                }
            }

            // ---- Server Status Card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(statusScale)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        statusColor.copy(alpha = 0.8f),
                                        statusColor.copy(alpha = 0.3f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (uiState.serverState) {
                            is ServerState.Running -> "Print Subsystem Active"
                            is ServerState.Starting -> "Starting Subsystem..."
                            is ServerState.Error -> "Subsystem Error"
                            is ServerState.Stopped -> "Print Subsystem Stopped"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    Text(
                        text = when (val state = uiState.serverState) {
                            is ServerState.Running -> if (state.port != state.requestedPort) {
                                "Listening on port ${state.port} — port ${state.requestedPort} needs root/Shizuku privilege, unavailable on this device"
                            } else {
                                "Listening for IPP requests on port ${state.port}"
                            }
                            is ServerState.Starting -> "Initializing CUPS client context..."
                            is ServerState.Error -> state.message
                            is ServerState.Stopped -> "Tap Start to launch CUPS foreground service"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    FilledTonalButton(
                        onClick = { viewModel.toggleServer() },
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRunning) "Stop Service" else "Start Service")
                    }
                }
            }

            // ---- Quick Stats Row ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Print,
                    label = "Printers",
                    value = "${uiState.printerCount}",
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Description,
                    label = "Active Jobs",
                    value = "${uiState.activeJobCount}",
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Wifi,
                    label = "IPP Port",
                    value = "$currentPort",
                )
            }

            // ---- Direct IPP Printer Test Card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test IPP Printer Connection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Text(
                        text = "Query attributes of any network IPP printer directly using libcups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    // Quick Target Selector Chips
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.SuggestionChip(
                            onClick = { testUriInput = "ipp://$deviceIp:$currentPort/ipp/print" },
                            label = { Text("Local Server (/ipp/print)", style = MaterialTheme.typography.labelSmall) }
                        )
                        for (p in uiState.printers) {
                            val pUri = com.cuppa.app.util.NetworkUtils.getPrinterIppUri(p.name, currentPort)
                            SuggestionChip(
                                onClick = { testUriInput = pUri },
                                label = { Text(p.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = testUriInput,
                        onValueChange = { testUriInput = it },
                        label = { Text("Printer IPP URI (Active Port: $currentPort)") },
                        placeholder = { Text("ipp://$deviceIp:$currentPort/ipp/print") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = { viewModel.testPrinterConnection(testUriInput) },
                            enabled = !uiState.isTestingPrinter && testUriInput.isNotBlank(),
                        ) {
                            if (uiState.isTestingPrinter) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Querying...")
                            } else {
                                Text("Query Attributes")
                            }
                        }
                    }

                    uiState.printerTestResult?.let { result ->
                        val isSuccess = result.startsWith("Connected")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // ---- Native Library Info Card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Native Engine (CUPS Core)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    InfoRow(
                        label = "Library",
                        value = "libcuppa_native.so",
                    )
                    InfoRow(
                        label = "CUPS Version",
                        value = uiState.nativeVersion.ifEmpty { "Loading..." },
                    )
                    InfoRow(
                        label = "Build Target",
                        value = "AOSP CUPS 2.2.9 Client",
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Compiled with NDK and linked against zlib/liblog",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact stat card for the dashboard quick stats row.
 */
@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Simple key-value info row for detail cards.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
