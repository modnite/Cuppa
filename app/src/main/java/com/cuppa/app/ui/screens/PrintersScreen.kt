package com.cuppa.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.PrintDisabled
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import com.cuppa.app.util.UsbPermissionHelper
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuppa.app.discovery.DiscoveredPrinter
import com.cuppa.app.discovery.PrinterTransport
import com.cuppa.app.viewmodel.PrintersViewModel
import com.cuppa.cups.PrinterInfo

/**
 * PrintersScreen — Manage connected and configured printers.
 *
 * Shows:
 * - Added (managed) printers at the top with status and actions
 * - Discovered (available) printers in a collapsible section below
 * - Empty state when no printers are found
 * - FAB to open the Add Printer sheet
 * - Pull-to-refresh to re-scan
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(
    viewModel: PrintersViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var testPrintPrinter by remember { mutableStateOf<PrinterInfo?>(null) }

    // Show snackbar for operation results
    LaunchedEffect(uiState.operationResult) {
        uiState.operationResult?.let { result ->
            snackbarHostState.showSnackbar(result)
            viewModel.clearOperationResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Printers",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.setShowAddSheet(true) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("Add Printer") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->

        val context = androidx.compose.ui.platform.LocalContext.current
        val permEvent by UsbPermissionHelper.permissionEvent.collectAsState()
        val usbManager = remember { context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager }
        val unpermittedPrinters = remember(usbManager, permEvent) {
            try {
                usbManager?.deviceList?.values?.filter { device ->
                    !UsbPermissionHelper.hasPermission(context, device) && UsbPermissionHelper.isPrinterDevice(device)
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        val hasAnyPrinters = uiState.addedPrinters.isNotEmpty() ||
            uiState.discoveredUsbPrinters.isNotEmpty() ||
            uiState.discoveredNetworkPrinters.isNotEmpty() ||
            unpermittedPrinters.isNotEmpty()

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshDiscovery() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!hasAnyPrinters && !uiState.isScanning) {
                // Empty state
                EmptyPrintersState()
            } else {
                PrintersContent(
                    addedPrinters = uiState.addedPrinters,
                    activePort = uiState.activePort,
                    discoveredUsbPrinters = uiState.discoveredUsbPrinters.filter { !it.isAlreadyAdded },
                    discoveredNetworkPrinters = uiState.discoveredNetworkPrinters.filter { !it.isAlreadyAdded },
                    unpermittedPrinters = unpermittedPrinters,
                    isScanning = uiState.isScanning,
                    onRemovePrinter = { viewModel.removePrinter(it) },
                    onAddPrinter = { viewModel.addPrinter(it) },
                    onTestPrint = { testPrintPrinter = it },
                )
            }
        }

        // Add Printer bottom sheet
        if (uiState.showAddSheet) {
            AddPrinterSheet(
                discoveredUsbPrinters = uiState.discoveredUsbPrinters.filter { !it.isAlreadyAdded },
                discoveredNetworkPrinters = uiState.discoveredNetworkPrinters.filter { !it.isAlreadyAdded },
                isAdding = uiState.isAdding,
                operationResult = uiState.operationResult,
                onAddPrinter = { viewModel.addPrinter(it) },
                onAddManualPrinter = { viewModel.addManualPrinter(it) },
                onForceAddManualPrinter = { viewModel.addManualPrinter(it, forceAdd = true) },
                onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                onDismiss = { viewModel.setShowAddSheet(false) },
            )
        }

        // Test Print bottom sheet
        testPrintPrinter?.let { printer ->
            TestPrintSheet(
                printer = printer,
                onDismiss = { testPrintPrinter = null }
            )
        }
    }
}

/**
 * Main printers content with added and discovered sections.
 */
@Composable
private fun PrintersContent(
    addedPrinters: List<PrinterInfo>,
    activePort: Int,
    discoveredUsbPrinters: List<DiscoveredPrinter>,
    discoveredNetworkPrinters: List<DiscoveredPrinter>,
    unpermittedPrinters: List<android.hardware.usb.UsbDevice>,
    isScanning: Boolean,
    onRemovePrinter: (String) -> Unit,
    onAddPrinter: (DiscoveredPrinter) -> Unit,
    onTestPrint: (PrinterInfo) -> Unit,
) {
    var showDiscovered by rememberSaveable { mutableStateOf(true) }
    val discoveredCount = discoveredUsbPrinters.size + discoveredNetworkPrinters.size

    val context = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (unpermittedPrinters.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Usb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "USB Printer Detected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Grant Android permission to enable communication and printing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = {
                                for (d in unpermittedPrinters) {
                                    UsbPermissionHelper.requestUsbPermission(context, d)
                                }
                            }
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }
        }

        // ---- Added Printers ----
        if (addedPrinters.isNotEmpty()) {
            item {
                Text(
                    text = "My Printers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(addedPrinters, key = { it.uri }) { printer ->
                AddedPrinterCard(
                    printer = printer,
                    activePort = activePort,
                    onRemove = { onRemovePrinter(printer.uri) },
                    onTestPrint = { onTestPrint(printer) },
                )
            }
        }

        // ---- Discovered Printers (collapsible) ----
        if (discoveredCount > 0 || isScanning) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDiscovered = !showDiscovered }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Available Printers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (discoveredCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($discoveredCount)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (isScanning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showDiscovered) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showDiscovered) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showDiscovered) {
                items(discoveredUsbPrinters + discoveredNetworkPrinters, key = { it.id }) { printer ->
                    DiscoveredPrinterRow(
                        printer = printer,
                        onAdd = { onAddPrinter(printer) },
                    )
                }
            }
        }

        // Bottom spacer for FAB clearance
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

/**
 * Card for an added (managed) printer.
 */
@Composable
private fun AddedPrinterCard(
    printer: PrinterInfo,
    activePort: Int,
    onRemove: () -> Unit,
    onTestPrint: () -> Unit,
) {
    val stateColor = when (printer.state) {
        3 -> MaterialTheme.colorScheme.primary          // idle
        4 -> MaterialTheme.colorScheme.tertiary         // processing
        5 -> MaterialTheme.colorScheme.error            // stopped
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Printer icon with state color
            Icon(
                imageVector = Icons.Outlined.Print,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = stateColor,
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Printer info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // The " (Cuppa)" suffix is the name other devices see this printer under, i.e. it marks it
                    // as shared through this print server. It appears only here, never on the
                    // discovered/available entries.
                    text = "${printer.name.ifBlank { "Unnamed Printer" }} (Cuppa)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (printer.makeAndModel.isNotBlank()) {
                    Text(
                        text = printer.makeAndModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                val shareUri = remember(printer.name, activePort) {
                    com.cuppa.app.util.NetworkUtils.getPrinterIppUri(printer.name, activePort)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IPP URI", shareUri))
                            android.widget.Toast.makeText(context, "Copied IPP Share URI to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Wifi,
                        contentDescription = "Copy IPP URI",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = shareUri,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    SuggestionChip(
                        onClick = { },
                        label = {
                            Text(
                                text = printer.stateName,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = stateColor.copy(alpha = 0.12f),
                            labelColor = stateColor,
                        ),
                        modifier = Modifier.height(24.dp),
                    )
                    if (printer.colorSupported) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    text = "Color",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.height(24.dp),
                        )
                    }
                }
            }

            // Test Print button
            IconButton(onClick = onTestPrint) {
                Icon(
                    imageVector = Icons.Outlined.Print,
                    contentDescription = "Test Print",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove printer",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Compact row for a discovered (not yet added) printer in the list.
 */
@Composable
private fun DiscoveredPrinterRow(
    printer: DiscoveredPrinter,
    onAdd: () -> Unit,
) {
    val transportIcon = when (printer.transport) {
        PrinterTransport.USB -> Icons.Outlined.Usb
        PrinterTransport.NETWORK -> Icons.Outlined.Wifi
        PrinterTransport.MANUAL -> Icons.Outlined.Print
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = transportIcon,
                contentDescription = printer.transportLabel,
                modifier = Modifier.size(24.dp),
                tint = if (printer.isThermal)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = printer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${printer.makeAndModel} · ${printer.suggestedDriver}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add printer",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Empty state when no printers are configured or discovered.
 */
@Composable
private fun EmptyPrintersState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PrintDisabled,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No Printers Found",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect a USB printer or discover network printers. Tap the + button to add your first printer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Supports USB, IPP, AirPrint, and Mopria printers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
