package com.cuppa.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cuppa.app.discovery.DiscoveredPrinter
import com.cuppa.app.discovery.PrinterTransport

/**
 * AddPrinterSheet — Modal bottom sheet for adding printers.
 *
 * Two tabs:
 * 1. **Discovered**: Lists all USB and network printers found via discovery,
 *    with an "Add" button on each.
 * 2. **Manual**: Text field for entering an IPP URI, with "Test & Add" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPrinterSheet(
    discoveredUsbPrinters: List<DiscoveredPrinter>,
    discoveredNetworkPrinters: List<DiscoveredPrinter>,
    isAdding: Boolean,
    operationResult: String?,
    onAddPrinter: (DiscoveredPrinter) -> Unit,
    onAddManualPrinter: (String) -> Unit,
    onForceAddManualPrinter: (String) -> Unit = {},
    onRequestUsbPermission: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Title
            Text(
                text = "Add Printer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Tab selector
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("Discovered") },
                    icon = {
                        SegmentedButtonDefaults.Icon(active = selectedTab == 0) {
                            Icon(
                                imageVector = Icons.Outlined.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        }
                    },
                )
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("Manual") },
                    icon = {
                        SegmentedButtonDefaults.Icon(active = selectedTab == 1) {
                            Icon(
                                imageVector = Icons.Outlined.Lan,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Result message
            AnimatedVisibility(
                visible = operationResult != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                operationResult?.let { result ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.startsWith("✓"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = result,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.startsWith("✓"))
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Loading indicator
            if (isAdding) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Verifying connection...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> DiscoveredTab(
                    usbPrinters = discoveredUsbPrinters.filter { !it.isAlreadyAdded },
                    networkPrinters = discoveredNetworkPrinters.filter { !it.isAlreadyAdded },
                    isAdding = isAdding,
                    onAddPrinter = onAddPrinter,
                    onRequestUsbPermission = onRequestUsbPermission,
                )
                1 -> ManualTab(
                    isAdding = isAdding,
                    operationResult = operationResult,
                    onAddManualPrinter = onAddManualPrinter,
                    onForceAddManualPrinter = onForceAddManualPrinter,
                )
            }
        }
    }
}

/**
 * Discovered printers tab — lists USB and network printers available to add.
 */
@Composable
private fun DiscoveredTab(
    usbPrinters: List<DiscoveredPrinter>,
    networkPrinters: List<DiscoveredPrinter>,
    isAdding: Boolean,
    onAddPrinter: (DiscoveredPrinter) -> Unit,
    onRequestUsbPermission: (String) -> Unit,
) {
    val allPrinters = usbPrinters + networkPrinters

    if (allPrinters.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Print,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Scanning for printers...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Make sure printers are connected via USB or on the same network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // USB printers section
        if (usbPrinters.isNotEmpty()) {
            item {
                Text(
                    text = "USB Printers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(usbPrinters, key = { it.id }) { printer ->
                DiscoveredPrinterCard(
                    printer = printer,
                    isAdding = isAdding,
                    onAdd = { onAddPrinter(printer) },
                    onRequestPermission = { onRequestUsbPermission(printer.id) },
                )
            }
        }

        // Network printers section
        if (networkPrinters.isNotEmpty()) {
            item {
                if (usbPrinters.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    text = "Network Printers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(networkPrinters, key = { it.id }) { printer ->
                DiscoveredPrinterCard(
                    printer = printer,
                    isAdding = isAdding,
                    onAdd = { onAddPrinter(printer) },
                    onRequestPermission = { /* network doesn't need permission */ },
                )
            }
        }
    }
}

/**
 * Card showing a single discovered printer with transport icon and Add button.
 */
@Composable
private fun DiscoveredPrinterCard(
    printer: DiscoveredPrinter,
    isAdding: Boolean,
    onAdd: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val transportIcon: ImageVector = when (printer.transport) {
        PrinterTransport.USB -> Icons.Outlined.Usb
        PrinterTransport.NETWORK -> Icons.Outlined.Wifi
        PrinterTransport.MANUAL -> Icons.Outlined.Lan
    }

    val needsPermission = printer.transport == PrinterTransport.USB &&
        printer.capabilities["hasPermission"] == "false"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Transport icon
            Icon(
                imageVector = transportIcon,
                contentDescription = printer.transportLabel,
                modifier = Modifier.size(28.dp),
                tint = if (printer.isThermal)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Printer info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = printer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = printer.makeAndModel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Driver chip
                Text(
                    text = printer.suggestedDriver,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (printer.isThermal)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action button
            if (needsPermission) {
                FilledTonalButton(
                    onClick = onRequestPermission,
                    enabled = !isAdding,
                ) {
                    Text("Allow")
                }
            } else {
                FilledTonalButton(
                    onClick = onAdd,
                    enabled = !isAdding,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}

/**
 * Manual entry tab — text field for IPP URI and "Test & Add" button.
 */
@Composable
private fun ManualTab(
    isAdding: Boolean,
    operationResult: String?,
    onAddManualPrinter: (String) -> Unit,
    onForceAddManualPrinter: (String) -> Unit,
) {
    var uri by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(
            text = "Enter the printer's IPP address:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val localIp = remember { com.cuppa.app.util.NetworkUtils.getLocalIpAddress() }
        val subnetExample = remember(localIp) {
            if (localIp != null) {
                val prefix = localIp.substringBeforeLast(".")
                "ipp://$prefix.X:631/ipp/print"
            } else {
                "ipp://192.168.1.X:631/ipp/print"
            }
        }

        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            label = { Text("Printer URI") },
            placeholder = { Text(subnetExample) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text("Examples: $subnetExample or ipp://hostname:631/ipp/print")
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onAddManualPrinter(uri) },
            enabled = uri.isNotBlank() && !isAdding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isAdding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Test & Add Printer")
        }

        if (operationResult?.startsWith("✗") == true && uri.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onForceAddManualPrinter(uri) },
                enabled = !isAdding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add Anyway (Skip Verification)")
            }
        }
    }
}
