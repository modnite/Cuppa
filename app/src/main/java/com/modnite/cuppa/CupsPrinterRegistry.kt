package com.modnite.cuppa

import com.hp.jipp.model.PrinterState
import java.util.concurrent.ConcurrentHashMap

data class CupsPrinter(
    val id: String,
    val name: String,
    val uri: String,
    var state: PrinterState = PrinterState.idle,
    var isAcceptingJobs: Boolean = true,
    var isShared: Boolean = true
)

object CupsPrinterRegistry {
    private val printers = ConcurrentHashMap<String, CupsPrinter>()

    init {
        // Register default Rollo X1038
        val defaultPrinter = CupsPrinter(
            id = "Rollo_X1038",
            name = "Rollo Thermal Printer 4x6",
            uri = "usb://Rollo/X1038"
        )
        printers[defaultPrinter.id] = defaultPrinter
    }

    fun getAllPrinters(): List<CupsPrinter> = printers.values.toList()

    fun getPrinter(id: String): CupsPrinter? = printers[id]

    fun addPrinter(printer: CupsPrinter) {
        printers[printer.id] = printer
    }

    fun removePrinter(id: String) {
        if (id != "Rollo_X1038") {
            printers.remove(id)
        }
    }

    fun updatePrinterState(id: String, state: PrinterState) {
        printers[id]?.state = state
    }

    fun toggleAcceptingJobs(id: String, accepting: Boolean) {
        printers[id]?.isAcceptingJobs = accepting
    }
}
