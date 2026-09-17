package com.cuppa.cups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CupsModelsTest {

    @Test
    fun testPrintJobStatusMapping() {
        assertEquals(PrintJobStatus.PENDING, PrintJobStatus.fromCode(3))
        assertEquals(PrintJobStatus.HELD, PrintJobStatus.fromCode(4))
        assertEquals(PrintJobStatus.PROCESSING, PrintJobStatus.fromCode(5))
        assertEquals(PrintJobStatus.STOPPED, PrintJobStatus.fromCode(6))
        assertEquals(PrintJobStatus.CANCELED, PrintJobStatus.fromCode(7))
        assertEquals(PrintJobStatus.ABORTED, PrintJobStatus.fromCode(8))
        assertEquals(PrintJobStatus.COMPLETED, PrintJobStatus.fromCode(9))
        assertEquals(PrintJobStatus.PENDING, PrintJobStatus.fromCode(99)) // fallback
    }

    @Test
    fun testPrinterInfoStateName() {
        val idlePrinter = PrinterInfo(
            name = "Rollo_X1038",
            uri = "ipp://192.168.1.50:631/ipp/print",
            makeAndModel = "Rollo Wireless",
            state = 3
        )
        assertEquals("Idle", idlePrinter.stateName)

        val processingPrinter = idlePrinter.copy(state = 4)
        assertEquals("Processing", processingPrinter.stateName)

        val stoppedPrinter = idlePrinter.copy(state = 5)
        assertEquals("Stopped", stoppedPrinter.stateName)
    }

    @Test
    fun testPrintJobProperties() {
        val job = PrintJob(
            jobId = 42,
            jobName = "Shipping_Label_4x6.pdf",
            printerUri = "ipp://192.168.1.50:631/ipp/print",
            user = "cuppa_user",
            documentFormat = "application/pdf",
            state = 9,
            sizeBytes = 1048576,
            createdAt = 1700000000000L
        )

        assertEquals(42, job.jobId)
        assertEquals("Shipping_Label_4x6.pdf", job.jobName)
        assertEquals(PrintJobStatus.COMPLETED, job.status)
        assertEquals(1048576L, job.sizeBytes)
    }
}
