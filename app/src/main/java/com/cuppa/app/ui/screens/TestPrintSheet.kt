package com.cuppa.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuppa.app.backend.usb.UsbPrinterBackend
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.thermal.ThermalPreferences
import com.cuppa.app.ui.util.horizontalScrollWithWheel
import com.cuppa.app.util.CuppaLog
import com.cuppa.app.util.NetworkUtils
import com.cuppa.cups.PrinterInfo
import com.cuppa.cups.thermal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class TestPrintFormat(val displayName: String, val description: String, val isDocument: Boolean) {
    PDF("Vector PDF", "Standard CUPS test page with alignment grid, color ramps & font ladder", true),
    PWG_RASTER("PWG Raster", "Driverless IPP Everywhere & AirPrint raster format", true),
    POSTSCRIPT("PostScript (PS)", "Standard Level 2/3 PostScript document", true),
    PCL("HP PCL 5/6", "PCL command stream for HP LaserJet & office printers", true),
    TSPL("TSPL / Rollo", "4\" × 6\" Label for Rollo X1038 & TSC printers", false),
    ZPL("ZPL II", "4\" × 6\" Label for Zebra ZD / GK series", false),
    ESC_POS("ESC/POS", "80mm Receipt for Epson, Star & POS printers", false),
    EPL2("EPL2", "Legacy 4\" × 6\" Eltron / Zebra format", false),
    CUPS_RAW("Plain Text", "ASCII diagnostic text with Form Feed", true)
}

/**
 * TestPrintSheet — Interactive modal bottom sheet for executing multi-format test prints
 * on office printers (Letter, A4, Legal) and thermal label/receipt printers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestPrintSheet(
    printer: PrinterInfo,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbBackend = remember { UsbPrinterBackend(context) }
    val cupsRepo = remember { CupsRepository.getInstance(context) }
    val thermalPrefs = remember { ThermalPreferences(context) }

    val prefs = remember { context.getSharedPreferences("cuppa_settings", android.content.Context.MODE_PRIVATE) }
    val serverState by cupsRepo.serverState.collectAsState()
    val currentPort = when (val s = serverState) {
        is com.cuppa.app.data.ServerState.Running -> s.port
        else -> prefs.getInt("server_port", 631)
    }
    val activeCupsVersion = remember { cupsRepo.getNativeVersion().ifBlank { "CUPS" } }
    val shareUri = remember(printer.name, currentPort) {
        NetworkUtils.getPrinterIppUri(printer.name, currentPort)
    }

    val permEvent by com.cuppa.app.util.UsbPermissionHelper.permissionEvent.collectAsState()
    val isUsb = printer.uri.startsWith("usb", ignoreCase = true)

    // A network printer whose host is this device's own IP is one of Cuppa's own USB printers,
    // re-advertised over mDNS so other devices on the network can print to it. We know exactly
    // what's on the other end in that case (our own USB backend), so it should get the same
    // format treatment as adding it directly over USB would. A genuine external network printer
    // (a real Brother/Epson/etc.) doesn't get this since we can't assume it understands a
    // thermal/label command language just because it happens to share a keyword with one.
    val isSelfHostedUsb = remember(printer.uri) {
        if (isUsb) {
            false
        } else {
            val host = Regex("^[a-zA-Z]+://\\[?([^\\]/:?]+)\\]?").find(printer.uri)?.groupValues?.getOrNull(1)
            host != null && host == NetworkUtils.getLocalIpAddress()
        }
    }
    val usbDevice = remember(printer.uri, permEvent) {
        if (isUsb) usbBackend.findDeviceByUri(printer.uri) else null
    }

    val hasUsbPermission = remember(usbDevice, permEvent) {
        if (isUsb && usbDevice != null) {
            usbBackend.hasPermission(usbDevice)
        } else {
            true
        }
    }

    LaunchedEffect(usbDevice) {
        if (isUsb && usbDevice != null && !usbBackend.hasPermission(usbDevice)) {
            usbBackend.requestPermission(usbDevice)
        }
    }

    // Formats selectable for a Network/IPP target. Cuppa relays network-printer jobs as raw
    // bytes without any local conversion (see PrintJobDispatcher.dispatchNetworkIppJob), so only
    // formats that are broadly understood by real third-party IPP printers belong here — sending
    // a thermal/label language (ZPL/ESC-POS/EPL/TSPL) or PCL to an arbitrary network printer that
    // never advertised support for it isn't just "probably wrong," it can leave the printer's own
    // embedded IPP daemon stuck trying to parse bytes it doesn't recognize as any format it knows,
    // which was reproduced live against a real Epson network printer (a raw PCL stream sent with
    // no document-format hint hung the connection for minutes with no response either way).
    val networkSafeFormats = remember { setOf(TestPrintFormat.PDF, TestPrintFormat.POSTSCRIPT) }

    // Auto-detect best initial format and media size. Thermal/label heuristics only make sense
    // when we know the physical device's real language — either Cuppa is driving it directly
    // over USB, or it's one of Cuppa's own USB printers re-advertised over the network
    // (isSelfHostedUsb). For a genuine external network target we don't actually know its real
    // capabilities, so default to the one format nearly every IPP/AirPrint printer accepts
    // regardless of what it advertises.
    val (initialFormat, initialSize) = remember(printer, isUsb, isSelfHostedUsb) {
        if (!isUsb && !isSelfHostedUsb) {
            Pair(TestPrintFormat.PDF, StandardTestPageGenerator.PaperSize.LETTER)
        } else {
            val lower = "${printer.name} ${printer.makeAndModel} ${printer.uri}".lowercase()
            when {
                "rollo" in lower || "x1038" in lower || "tspl" in lower || "xpp" in lower || "0x09c5" in lower ->
                    Pair(TestPrintFormat.TSPL, StandardTestPageGenerator.PaperSize.LABEL_4X6)
                "zebra" in lower || "zpl" in lower ->
                    Pair(TestPrintFormat.ZPL, StandardTestPageGenerator.PaperSize.LABEL_4X6)
                "star" in lower || "escpos" in lower || "receipt" in lower || "tm-" in lower ->
                    Pair(TestPrintFormat.ESC_POS, StandardTestPageGenerator.PaperSize.RECEIPT_80MM)
                "epl" in lower || "eltron" in lower ->
                    Pair(TestPrintFormat.EPL2, StandardTestPageGenerator.PaperSize.LABEL_4X6)
                else ->
                    Pair(TestPrintFormat.TSPL, StandardTestPageGenerator.PaperSize.LABEL_4X6)
            }
        }
    }

    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var selectedSize by remember { mutableStateOf(initialSize) }
    // Lets the user force a grayscale test print regardless of the printer's own color
    // capability — useful for verifying grayscale rendering fidelity, or testing a color
    // printer's black-only behavior specifically. Only affects the PWG-Raster network path;
    // thermal/USB formats (ESC/POS, ZPL, TSPL, EPL) are inherently monochrome already.
    var colorMode by remember(printer) { mutableStateOf(printer.colorSupported) }

    var isPrinting by remember { mutableStateOf(false) }
    var printSuccessMessage by remember { mutableStateOf<String?>(null) }
    var printErrorMessage by remember { mutableStateOf<String?>(null) }

    fun runTestPrint() {
        scope.launch {
            isPrinting = true
            printSuccessMessage = null
            printErrorMessage = null

            try {
                val transportStr = if (isUsb) "USB Direct" else "IPP Everywhere"

                if (isUsb) {
                    val dev = usbDevice ?: usbBackend.findDeviceByUri(printer.uri)
                    if (dev == null) {
                        throw IllegalStateException("USB printer device not found for URI: ${printer.uri}. Please ensure the USB cable is connected.")
                    }

                    if (!usbBackend.hasPermission(dev)) {
                        usbBackend.requestPermission(dev)
                        throw SecurityException("USB permission requested. Please tap Allow on the system prompt on your screen, then tap Print again.")
                    }

                    CuppaLog.i("TestPrintSheet", "Generating USB test print in format ${selectedFormat.name} for ${printer.name}")
                    usbBackend.queryHardwareStatus(dev).let { r ->
                        CuppaLog.i("TestPrintSheet", "Pre-print printer status: ${r.getOrNull() ?: r.exceptionOrNull()?.message}")
                    }

                    // Generate bytes for USB
                    val bytesToSend: ByteArray = when (selectedFormat) {
                        TestPrintFormat.TSPL -> buildTsplRasterTestLabel(
                            context = context,
                            printerName = printer.name,
                            cupsVersion = "CUPS v2.2.9",
                            transport = transportStr,
                            serverUri = shareUri,
                            paperSize = selectedSize,
                            thermal = thermalPrefs.settings.value
                        )
                        TestPrintFormat.ZPL -> ZplDriver.generateTestLabel(
                            printerName = printer.name,
                            cupsVersion = "CUPS v2.2.9",
                            transport = transportStr
                        )
                        TestPrintFormat.ESC_POS -> EscPosDriver.generateTestReceipt(
                            printerName = printer.name,
                            characterWidth = 48
                        )
                        TestPrintFormat.EPL2 -> EplDriver.generateTestLabel(
                            printerName = printer.name,
                            transport = transportStr
                        )
                        TestPrintFormat.PCL -> {
                            // Exercise the real PclDriver raster path (the same one
                            // PrintJobDispatcher falls back to for unrecognized USB printers)
                            // rather than the static text-only PCL test document, so this test
                            // actually verifies what a real print job would send.
                            val pdfBytes = StandardTestPageGenerator.generatePdfTestPage(
                                paperSize = selectedSize,
                                printerName = printer.name,
                                cupsVersion = activeCupsVersion,
                                transport = transportStr,
                                serverUri = shareUri
                            )
                            val bmp = renderFirstPageToBitmap(context, pdfBytes, widthPx = 2550)
                            PclDriver.fromBitmap(bmp, dpi = 300, ditherMode = thermalPrefs.settings.value.ditherMode)
                        }
                        TestPrintFormat.POSTSCRIPT -> StandardTestPageGenerator.generatePostScriptTestPage(
                            paperSize = selectedSize,
                            printerName = printer.name,
                            transport = transportStr
                        )
                        TestPrintFormat.PDF -> {
                            // If user selected PDF on a direct USB Rollo printer, render to bitmap and convert to TSPL
                            val pdfBytes = StandardTestPageGenerator.generatePdfTestPage(
                                paperSize = selectedSize,
                                printerName = printer.name,
                                cupsVersion = activeCupsVersion,
                                transport = transportStr,
                                serverUri = shareUri
                            )
                            val isRollo = printer.name.contains("rollo", ignoreCase = true) ||
                                    printer.makeAndModel.contains("rollo", ignoreCase = true) ||
                                    printer.uri.contains("1038", ignoreCase = true)

                            if (isRollo) {
                                val bmp = TsplDriver.centerOnPrintHead(renderFirstPageToBitmap(context, pdfBytes, widthPx = 812))
                                val tp = thermalPrefs.settings.value
                                TsplDriver.fromBitmap(
                                    bmp,
                                    density = (tp.darkness / 2).coerceIn(0, 15),
                                    speed = tp.speedIps,
                                    ditherMode = tp.ditherMode,
                                    invertPolarity = true xor tp.invertPolarity
                                )
                            } else {
                                pdfBytes
                            }
                        }
                        TestPrintFormat.PWG_RASTER, TestPrintFormat.CUPS_RAW ->
                            StandardTestPageGenerator.generateRawTextTestPage(
                                printerName = printer.name,
                                transport = transportStr,
                                paperSize = selectedSize
                            )
                    }

                    // Driverless USB printers (IPP-over-USB) take the PDF as a real job and report how
                    // it ended. Anything else falls through to raw bytes below.
                    val ippUsb = if (selectedFormat == TestPrintFormat.PDF) {
                        usbBackend.printViaIppUsb(dev, bytesToSend, "Cuppa test page", 1, colorMode)
                    } else null
                    if (ippUsb != null) {
                        if (ippUsb.isSuccess) {
                            printSuccessMessage = "✓ ${ippUsb.getOrNull()} (${if (colorMode) "color" else "black and white"})"
                            CuppaLog.i("TestPrintSheet", "IPP-over-USB print: ${ippUsb.getOrNull()}")
                            return@launch
                        } else {
                            throw ippUsb.exceptionOrNull() ?: Exception("IPP-over-USB print failed")
                        }
                    }

                    val sendResult = usbBackend.sendRawBytes(dev, bytesToSend)
                    if (sendResult.isSuccess) {
                        printSuccessMessage = "✓ Printed successfully! Sent ${sendResult.getOrNull()} bytes via USB bulk transfer."
                        CuppaLog.i("TestPrintSheet", "USB print success: ${sendResult.getOrNull()} bytes sent")
                    } else {
                        throw sendResult.exceptionOrNull() ?: Exception("Failed USB transfer")
                    }

                } else {
                    // Send to Network / IPP Printer
                    CuppaLog.i("TestPrintSheet", "Generating Network test print in format ${selectedFormat.name} for ${printer.name}")

                    val bytesToSend: ByteArray = when (selectedFormat) {
                        TestPrintFormat.PDF -> StandardTestPageGenerator.generatePdfTestPage(
                            paperSize = selectedSize,
                            printerName = printer.name,
                            cupsVersion = activeCupsVersion,
                            transport = transportStr,
                            serverUri = shareUri
                        )
                        TestPrintFormat.POSTSCRIPT -> StandardTestPageGenerator.generatePostScriptTestPage(
                            paperSize = selectedSize,
                            printerName = printer.name,
                            transport = transportStr
                        )
                        TestPrintFormat.PCL -> StandardTestPageGenerator.generatePclTestPage(
                            printerName = printer.name,
                            transport = transportStr
                        )
                        TestPrintFormat.TSPL -> buildTsplRasterTestLabel(
                            context = context,
                            printerName = printer.name,
                            cupsVersion = activeCupsVersion,
                            transport = transportStr,
                            serverUri = shareUri,
                            paperSize = selectedSize,
                            thermal = thermalPrefs.settings.value
                        )
                        TestPrintFormat.ZPL -> ZplDriver.generateTestLabel(
                            printerName = printer.name,
                            cupsVersion = activeCupsVersion,
                            transport = transportStr
                        )
                        TestPrintFormat.ESC_POS -> EscPosDriver.generateTestReceipt(
                            printerName = printer.name,
                            characterWidth = 48
                        )
                        TestPrintFormat.EPL2 -> EplDriver.generateTestLabel(
                            printerName = printer.name,
                            transport = transportStr
                        )
                        TestPrintFormat.PWG_RASTER, TestPrintFormat.CUPS_RAW ->
                            StandardTestPageGenerator.generateRawTextTestPage(
                                printerName = printer.name,
                                transport = transportStr,
                                paperSize = selectedSize
                            )
                    }

                    val tempFile = File(context.cacheDir, "test_print_${System.currentTimeMillis()}.bin")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(tempFile).use { it.write(bytesToSend) }
                    }

                    // A real PDF sent to a printer that only understands PWG-Raster gets
                    // accepted at the IPP layer but silently produces no output (confirmed live
                    // against an Epson with no onboard PDF interpreter) — convert first, the way
                    // a real AirPrint client would. Also convert when the user explicitly forced
                    // grayscale via the Color Mode toggle below, since that can only be honored
                    // through our own raster encoder — the PDF itself is always drawn in color.
                    val supportsPwgRaster = printer.supportedFormats.any { it.equals("image/pwg-raster", ignoreCase = true) }
                    val supportsPdf = printer.supportedFormats.any { it.equals("application/pdf", ignoreCase = true) }
                    var fileToSend = tempFile
                    var convertedFile: File? = null
                    if (selectedFormat == TestPrintFormat.PDF && supportsPwgRaster && (!supportsPdf || !colorMode)) {
                        val rasterFile = File(context.cacheDir, "${tempFile.nameWithoutExtension}.ras")
                        if (com.cuppa.app.server.PwgRasterConverter.convertFirstPageToPwgRaster(tempFile, rasterFile, colorMode)) {
                            fileToSend = rasterFile
                            convertedFile = rasterFile
                            CuppaLog.i("TestPrintSheet", "Converted test PDF to PWG-Raster for ${printer.name} (color=$colorMode)")
                        }
                    }

                    val jobId = cupsRepo.printFile(printer.uri, fileToSend.absolutePath, "Cuppa Test Page")
                    tempFile.delete()
                    convertedFile?.delete()

                    if (jobId > 0) {
                        printSuccessMessage = "✓ Test print spooled to ${printer.name}! Job ID #$jobId"
                        CuppaLog.i("TestPrintSheet", "Network print spooled with Job ID #$jobId")
                    } else {
                        throw IllegalStateException("CUPS printFile returned code $jobId (spool failure)")
                    }
                }
            } catch (e: Exception) {
                CuppaLog.e("TestPrintSheet", "Test print error: ${e.message}", e)
                printErrorMessage = e.message ?: "Failed to execute test print"
            } finally {
                isPrinting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Test Print",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${printer.name} (${if (isUsb) "Direct USB" else "Network IPP"})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // USB Device Status & Permission Banner
            if (isUsb) {
                if (usbDevice == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "USB Printer Not Detected",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Check your OTG adapter/cable and ensure the printer is turned on.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (!hasUsbPermission) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "USB Permission Required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Tap 'Grant' and choose 'Allow' on the system prompt.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Button(
                                onClick = {
                                    usbBackend.requestPermission(usbDevice)
                                    Toast.makeText(context, "Permission dialog requested", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Grant")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Paper Size Selector
            Text(
                text = "Media / Paper Size",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScrollWithWheel(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StandardTestPageGenerator.PaperSize.entries.forEach { size ->
                    FilterChip(
                        selected = selectedSize == size,
                        onClick = { selectedSize = size },
                        label = { Text(size.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Protocol / Format Selector
            Text(
                text = "Document / Driver Format",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScrollWithWheel(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val selectableFormats = if (isUsb || isSelfHostedUsb) TestPrintFormat.entries else TestPrintFormat.entries.filter { it in networkSafeFormats }
                selectableFormats.forEach { fmt ->
                    FilterChip(
                        selected = selectedFormat == fmt,
                        onClick = { selectedFormat = fmt },
                        label = { Text(fmt.displayName) }
                    )
                }
            }

            Text(
                text = selectedFormat.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )

            // Color Mode toggle — only meaningful for the PDF/PWG-Raster path; thermal/USB
            // formats (ESC/POS, ZPL, TSPL, EPL) are inherently monochrome already.
            if (selectedFormat == TestPrintFormat.PDF) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Color Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = colorMode,
                        onClick = { colorMode = true },
                        label = { Text("Color") },
                        leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    FilterChip(
                        selected = !colorMode,
                        onClick = { colorMode = false },
                        label = { Text("Black & White") },
                        leadingIcon = { Icon(Icons.Outlined.Contrast, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Simulation Preview Card
            Text(
                text = "Document Preview Summary",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1976D2), RoundedCornerShape(4.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "CUPPA CUPS PRINT SERVER",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                            Text(
                                text = "Driverless IPP Everywhere & Thermal Subsystem",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PreviewRow("PRINTER:", printer.name)
                        PreviewRow("FORMAT:", selectedFormat.displayName)
                        PreviewRow("PAPER SIZE:", selectedSize.displayName)
                        PreviewRow("TRANSPORT:", if (isUsb) "USB Direct Bulk OUT" else "IPP Everywhere Spool")
                        PreviewRow("SHARE URI:", shareUri)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status feedback
            printSuccessMessage?.let { msg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            printErrorMessage?.let { err ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Print Action Button
            Button(
                onClick = { runTestPrint() },
                enabled = !isPrinting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Sending Test Print...")
                } else {
                    Icon(Icons.Outlined.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print Test Document (${selectedSize.displayName.split(" ")[0]})", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Renders the first page of [pdfBytes] to a white-backed ARGB bitmap [widthPx] wide, preserving
 * aspect ratio — shared by every test-print path that needs to rasterize before handing bytes to
 * a printer-language driver (TSPL, PCL, etc).
 */
/**
 * Builds the TSPL test label as a rendered BITMAP raster rather than TEXT/BOX/BARCODE/QRCODE
 * commands. A real Rollo X1038 (IEEE 1284 CMD:XPP,XL) is only proven with BITMAP jobs; the
 * higher-level drawing commands silently print nothing on it.
 */
private fun buildTsplRasterTestLabel(
    context: android.content.Context,
    printerName: String,
    cupsVersion: String,
    transport: String,
    serverUri: String,
    paperSize: StandardTestPageGenerator.PaperSize,
    thermal: com.cuppa.app.data.thermal.ThermalSettingsState
): ByteArray {
    val pdfBytes = StandardTestPageGenerator.generatePdfTestPage(
        paperSize = paperSize,
        printerName = printerName,
        cupsVersion = cupsVersion,
        transport = transport,
        serverUri = serverUri
    )
    val bmp = TsplDriver.centerOnPrintHead(renderFirstPageToBitmap(context, pdfBytes, widthPx = 812))
    return TsplDriver.fromBitmap(
        bmp,
        density = (thermal.darkness / 2).coerceIn(0, 15),
        speed = thermal.speedIps,
        ditherMode = thermal.ditherMode,
        invertPolarity = true xor thermal.invertPolarity
    )
}

private fun renderFirstPageToBitmap(context: android.content.Context, pdfBytes: ByteArray, widthPx: Int): Bitmap {
    val tempPdf = File(context.cacheDir, "temp_render_${System.currentTimeMillis()}.pdf")
    tempPdf.writeBytes(pdfBytes)
    try {
        ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(0)
                val aspectRatio = page.height.toFloat() / page.width.toFloat()
                val heightPx = (widthPx * aspectRatio).toInt().coerceAtLeast(100)
                val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(AndroidColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                return bmp
            }
        }
    } finally {
        tempPdf.delete()
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.DarkGray
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.Black
        )
    }
}
