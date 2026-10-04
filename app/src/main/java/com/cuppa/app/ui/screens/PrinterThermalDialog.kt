package com.cuppa.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuppa.app.data.thermal.PrinterThermalConfig
import com.cuppa.app.data.thermal.ThermalConfigStore
import com.cuppa.app.data.thermal.ThermalDialect
import com.cuppa.app.data.thermal.ThermalPreferences
import com.cuppa.cups.PrinterInfo
import com.cuppa.cups.thermal.ThermalRasterizer
import kotlin.math.abs

/**
 * PrinterThermalDialog — per-printer thermal overrides.
 *
 * The values start from the printer's saved override when there is one, otherwise from the global
 * [ThermalPreferences], which remain the fallback for every printer that has no override.
 * "Use defaults" deletes the override so the printer returns to the globals.
 */
@Composable
fun PrinterThermalDialog(
    printer: PrinterInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ThermalConfigStore.getInstance(context) }
    val globalPrefs = remember { ThermalPreferences(context) }
    val global by globalPrefs.settings.collectAsState()

    val existing = remember(printer.uri) { store.get(printer.uri) }

    var dialect by remember(printer.uri) { mutableStateOf(existing?.dialect ?: ThermalDialect.AUTO) }
    var labelSize by remember(printer.uri) {
        mutableStateOf(if (existing == null || abs(existing.labelHeightMm - 152.4) < 1.0) "4x6" else "4x4")
    }
    var density by remember(printer.uri) { mutableStateOf(existing?.density ?: global.darkness) }
    var speed by remember(printer.uri) { mutableStateOf(existing?.speed ?: global.speedIps) }
    var dither by remember(printer.uri) { mutableStateOf(existing?.ditherMode ?: global.ditherMode) }
    var invert by remember(printer.uri) { mutableStateOf(existing?.invertPolarity ?: global.invertPolarity) }

    val labelWidthMm = 101.6
    val labelHeightMm = if (labelSize == "4x4") 101.6 else 152.4

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thermal settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Overrides for “${printer.name.ifBlank { "this printer" }}”. " +
                        "Printers without an override use the global thermal defaults.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(14.dp))

                SettingLabel("Dialect")
                listOf(
                    ThermalDialect.AUTO to "Auto-detect",
                    ThermalDialect.TSPL to "TSPL — Rollo X1038",
                    ThermalDialect.ZPL to "ZPL — Zebra",
                    ThermalDialect.EPL to "EPL — Eltron",
                    ThermalDialect.ESCPOS to "ESC/POS — receipt",
                    ThermalDialect.PCL to "PCL — laser / inkjet",
                ).forEach { (value, label) ->
                    RadioRow(selected = dialect == value, label = label, onSelect = { dialect = value })
                }

                Spacer(modifier = Modifier.height(14.dp))

                SettingLabel("Label size")
                listOf(
                    "4x6" to "4 × 6 in (101.6 × 152.4 mm)",
                    "4x4" to "4 × 4 in (101.6 × 101.6 mm)",
                ).forEach { (value, label) ->
                    RadioRow(selected = labelSize == value, label = label, onSelect = { labelSize = value })
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingLabel("Density")
                    Text(
                        text = "$density / 30",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = density.toFloat(),
                    onValueChange = { density = it.toInt() },
                    valueRange = 0f..30f,
                    steps = 29,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingLabel("Speed")
                    Text(
                        text = "$speed IPS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = speed.toFloat(),
                    onValueChange = { speed = it.toInt() },
                    valueRange = 2f..6f,
                    steps = 3,
                )

                Spacer(modifier = Modifier.height(14.dp))

                SettingLabel("Dither")
                listOf(
                    ThermalRasterizer.DitherMode.FLOYD_STEINBERG to "Floyd-Steinberg",
                    ThermalRasterizer.DitherMode.ATKINSON to "Atkinson",
                    ThermalRasterizer.DitherMode.THRESHOLD to "Threshold",
                ).forEach { (value, label) ->
                    RadioRow(selected = dither == value, label = label, onSelect = { dither = value })
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Invert polarity",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Swap black and white for printers that render labels inverted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = invert, onCheckedChange = { invert = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    store.set(
                        printer.uri,
                        PrinterThermalConfig(
                            dialect = dialect,
                            labelWidthMm = labelWidthMm,
                            labelHeightMm = labelHeightMm,
                            density = density,
                            speed = speed,
                            gapMm = existing?.gapMm ?: 3.0,
                            ditherMode = dither,
                            invertPolarity = invert,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        store.remove(printer.uri)
                        onDismiss()
                    },
                ) { Text("Use defaults") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun RadioRow(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
