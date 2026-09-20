package com.cuppa.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuppa.app.data.ServerState
import com.cuppa.app.server.PrinterReachability
import com.cuppa.app.ui.theme.ServerRunning
import com.cuppa.app.ui.theme.ServerStopped
import com.cuppa.app.util.NetworkUtils
import com.cuppa.app.util.PermissionManager
import com.cuppa.app.util.UsbPermissionHelper
import com.cuppa.app.util.safeDescription
import com.cuppa.app.viewmodel.DashboardViewModel

/**
 * DashboardScreen — the home screen. Leads with what matters to someone sharing a printer: is
 * Cuppa on, which printers are being shared, and how another device connects. Developer tools
 * (the raw IPP query, native engine details) live under "Advanced" and start collapsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onOpenPrinters: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRunning = uiState.serverState is ServerState.Running

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("cuppa_settings", android.content.Context.MODE_PRIVATE) }
    val currentPort = when (val s = uiState.serverState) {
        is ServerState.Running -> s.port
        else -> prefs.getInt("server_port", 631)
    }
    val deviceIp = remember { NetworkUtils.getLocalIpAddress() ?: "127.0.0.1" }
    val offlineNames by PrinterReachability.getInstance(context).offline.collectAsState()
    val sharedCount = uiState.printers.count { it.name !in offlineNames }

    // Notification and battery-optimization permissions are requested at app launch and managed
    // from Settings. Only USB printer access is surfaced here, because it needs a tap per device.
    val permEvent by UsbPermissionHelper.permissionEvent.collectAsState()
    val unpermittedPrinters = remember(permEvent) { PermissionManager.getUnpermittedUsbPrinters(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Cuppa", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusHero(
                state = uiState.serverState,
                sharedCount = sharedCount,
                activeJobs = uiState.activeJobCount,
                onToggle = { viewModel.toggleServer() },
            )

            // First-run guide: stays until all three steps are done.
            if (!isRunning || uiState.printers.isEmpty()) {
                GetStartedCard(
                    running = isRunning,
                    hasPrinters = uiState.printers.isNotEmpty(),
                    onOpenPrinters = onOpenPrinters,
                )
            }

            if (unpermittedPrinters.isNotEmpty()) {
                UsbAccessCard(devices = unpermittedPrinters.map { it.safeDescription() to it }) { device ->
                    UsbPermissionHelper.requestUsbPermission(context, device)
                }
            }

            if (uiState.printers.isNotEmpty()) {
                SharedPrintersCard(
                    printers = uiState.printers.map { it.name to (it.name !in offlineNames) },
                    running = isRunning,
                )
            }

            if (isRunning) {
                ConnectCard(deviceIp = deviceIp, port = currentPort, firstPrinter = uiState.printers.firstOrNull()?.name)
            }

            AdvancedCard(
                viewModel = viewModel,
                uiState = uiState,
                deviceIp = deviceIp,
                port = currentPort,
            )
        }
    }
}

@Composable
private fun StatusHero(
    state: ServerState,
    sharedCount: Int,
    activeJobs: Int,
    onToggle: () -> Unit,
) {
    val isRunning = state is ServerState.Running
    val dot by animateColorAsState(
        targetValue = when (state) {
            is ServerState.Running -> ServerRunning
            is ServerState.Starting -> MaterialTheme.colorScheme.tertiary
            is ServerState.Error -> MaterialTheme.colorScheme.error
            is ServerState.Stopped -> ServerStopped
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "statusDot",
    )
    val container = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when (state) {
                        is ServerState.Running -> "Cuppa is on"
                        is ServerState.Starting -> "Starting"
                        is ServerState.Error -> "Something went wrong"
                        is ServerState.Stopped -> "Cuppa is off"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = onContainer.copy(alpha = 0.75f),
                )
            }
            Text(
                text = when (state) {
                    is ServerState.Running -> when (sharedCount) {
                        0 -> "No printers to share yet"
                        1 -> "Sharing 1 printer"
                        else -> "Sharing $sharedCount printers"
                    }
                    is ServerState.Starting -> "Getting ready"
                    is ServerState.Error -> "Cuppa could not start"
                    is ServerState.Stopped -> "Turn on to share your printers"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer,
            )
            Text(
                text = when (state) {
                    is ServerState.Running -> buildString {
                        append("Phones, laptops and tablets on this network can print through this phone.")
                        if (state.port != state.requestedPort) append(" Using port ${state.port} because ${state.requestedPort} is taken.")
                        if (activeJobs > 0) append(" $activeJobs ${if (activeJobs == 1) "job" else "jobs"} printing now.")
                    }
                    is ServerState.Error -> state.message
                    else -> "Other devices can only see your printers while Cuppa is on."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(10.dp))
            if (isRunning) {
                FilledTonalButton(onClick = onToggle) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Turn off")
                }
            } else {
                Button(onClick = onToggle, enabled = state !is ServerState.Starting) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state is ServerState.Starting) "Starting…" else "Turn on")
                }
            }
        }
    }
}

@Composable
private fun GetStartedCard(running: Boolean, hasPrinters: Boolean, onOpenPrinters: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Step(done = running, title = "Turn Cuppa on", detail = "Use the button above. It runs in the background.")
            Step(
                done = hasPrinters,
                title = "Add a printer",
                detail = "Cuppa finds network printers and USB printers plugged into this phone.",
                action = if (!hasPrinters) "Add printer" else null,
                onAction = onOpenPrinters,
            )
            Step(
                done = false,
                title = "Connect your devices",
                detail = "Once it is on, the connection details appear below. Most devices find the printer on their own.",
                showCheck = false,
            )
        }
    }
}

@Composable
private fun Step(
    done: Boolean,
    title: String,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
    showCheck: Boolean = true,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(action) }
        }
    }
}

@Composable
private fun UsbAccessCard(devices: List<Pair<String, android.hardware.usb.UsbDevice>>, onGrant: (android.hardware.usb.UsbDevice) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("A USB printer needs permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "Android shows the approval prompt on the phone's own screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            for ((label, device) in devices) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Button(onClick = { onGrant(device) }) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun SharedPrintersCard(printers: List<Pair<String, Boolean>>, running: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your printers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for ((name, online) in printers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(
                            if (online) ServerRunning else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        text = if (!online) "Not reachable" else if (running) "Shared" else "Ready",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(deviceIp: String, port: Int, firstPrinter: String?) {
    val clipboard = LocalClipboardManager.current
    val address = NetworkUtils.getPrinterIppUri(firstPrinter ?: "printer", port).replace("//$deviceIp", "//$deviceIp")
    var copied by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect a device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Most devices find your printers on their own. Look for a printer marked (Cuppa) in the print dialog or in the printer settings. If yours does not, add it with this address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                }) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.CheckCircle else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy address",
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HowTo("iPhone, iPad and Mac", "Open the print dialog and pick the printer marked (Cuppa). On a Mac you can also add it in System Settings, Printers & Scanners. No driver is needed.")
            HowTo("Windows", "Open Settings, Bluetooth & devices, Printers & scanners, then Add device. Pick the printer marked (Cuppa).")
            HowTo("Linux", "It shows up in the print dialog. Or run: lpadmin -p cuppa -E -v $address -m everywhere")
            HowTo("Other Android phones", "Open Settings, Connected devices, Printing, then Default Print Service. Add a printer and use the address above.")
        }
    }
}

@Composable
private fun HowTo(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun AdvancedCard(
    viewModel: DashboardViewModel,
    uiState: com.cuppa.app.viewmodel.DashboardUiState,
    deviceIp: String,
    port: Int,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var testUriInput by remember(deviceIp, port) { mutableStateOf("ipp://$deviceIp:$port/ipp/print") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { open = !open }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(modifier = Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Check a printer address", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Ask any IPP printer for its capabilities. Useful for finding out why a device will not connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        SuggestionChip(
                            onClick = { testUriInput = "ipp://$deviceIp:$port/ipp/print" },
                            label = { Text("This phone", style = MaterialTheme.typography.labelSmall) },
                        )
                        for (p in uiState.printers) {
                            SuggestionChip(
                                onClick = { testUriInput = NetworkUtils.getPrinterIppUri(p.name, port) },
                                label = { Text(p.name, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = testUriInput,
                        onValueChange = { testUriInput = it },
                        label = { Text("Printer address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { viewModel.testPrinterConnection(testUriInput) },
                            enabled = !uiState.isTestingPrinter && testUriInput.isNotBlank(),
                        ) {
                            if (uiState.isTestingPrinter) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("Checking…")
                            } else {
                                Text("Check")
                            }
                        }
                    }
                    uiState.printerTestResult?.let { result ->
                        val ok = result.startsWith("Connected")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (ok) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .padding(12.dp),
                        ) {
                            Icon(
                                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(result, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    InfoRow("Print engine", uiState.nativeVersion.ifEmpty { "Loading…" })
                    InfoRow("Phone address", "$deviceIp:$port")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
