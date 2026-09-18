package com.cuppa.app

import com.cuppa.app.util.PrinterNaming
import org.junit.Assert.assertEquals
import org.junit.Test

class PrinterNamingTest {
    // Expected values here must match sanitizeResourceName in cups_server.cpp exactly: the native
    // server resolves the queue a client asks for by comparing names in this form.
    @Test
    fun resourceName_replacesUnsafeCharactersAndCollapsesRuns() {
        assertEquals("Brother_MFC-L2717DW", PrinterNaming.resourceName("Brother MFC-L2717DW"))
        assertEquals("USB_Printer_0x09C5_0x0588", PrinterNaming.resourceName("USB Printer (0x09C5:0x0588)"))
        assertEquals("USB_Printer_0x09C5_0x0588_Cuppa_2", PrinterNaming.resourceName("USB Printer (0x09C5:0x0588) (Cuppa) (2)"))
        assertEquals("a.b-c_d", PrinterNaming.resourceName("a.b-c_d"))
    }

    @Test
    fun resourceName_hasNoLeadingOrTrailingUnderscoreAndNeverEmpty() {
        assertEquals("x", PrinterNaming.resourceName("  (x)  "))
        assertEquals("printer", PrinterNaming.resourceName("()"))
        assertEquals("printer", PrinterNaming.resourceName(""))
    }

    @Test
    fun comparableName_stripsMdnsDecorations() {
        assertEquals("brother mfc-l2717dw", PrinterNaming.comparableName("Brother MFC-L2717DW"))
        assertEquals("brother mfc-l2717dw", PrinterNaming.comparableName("Brother MFC-L2717DW [b422009483ed]"))
        assertEquals("brother mfc-l2717dw", PrinterNaming.comparableName("Brother MFC-L2717DW (Cuppa) (2)"))
        assertEquals("usb printer (0x09c5:0x0588)", PrinterNaming.comparableName("USB Printer (0x09C5:0x0588) (Cuppa) (2) (Cuppa)"))
    }
}
