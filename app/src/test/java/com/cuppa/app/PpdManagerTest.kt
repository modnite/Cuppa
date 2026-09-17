package com.cuppa.app

import com.cuppa.app.data.ppd.DriverCategory
import com.cuppa.app.data.ppd.PpdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PpdManagerTest {

    @Test
    fun testParseRolloPpd() {
        val ppdContent = """
            *PPD-Adobe: "4.3"
            *FormatVersion: "4.3"
            *Manufacturer: "Rollo"
            *ModelName: "Rollo X1038 Thermal"
            *NickName: "Rollo X1038 Direct Thermal (ZPL)"
            *ShortNickName: "Rollo X1038"
            *ColorDevice: False
            *OpenUI *Resolution/Resolution: PickOne
            *Resolution 203dpi/203 DPI: "<</HWResolution[203 203]>>setpagedevice"
            *CloseUI: *Resolution
            *OpenUI *PageSize/Media Size: PickOne
            *PageSize w288h432/4x6 Shipping Label: "<</PageSize[288 432]>>setpagedevice"
            *PageSize w162h90/2.25x1.25 Address Label: "<</PageSize[162 90]>>setpagedevice"
            *CloseUI: *PageSize
        """.trimIndent()

        val tempFile = File.createTempFile("rollo_test", ".ppd")
        try {
            tempFile.writeText(ppdContent)
            val ppdManager = PpdManager()

            val info = ppdManager.parsePpd(tempFile, isBundled = true)
            assertNotNull(info)
            assertEquals("Rollo", info!!.manufacturer)
            assertEquals("Rollo X1038 Thermal", info.modelName)
            assertEquals("Rollo X1038 Direct Thermal (ZPL)", info.nickName)
            assertEquals("Rollo X1038", info.shortNickName)
            assertFalse(info.isColor)
            assertTrue(info.isBundled)
            assertEquals(DriverCategory.THERMAL, info.category)
            assertTrue(info.resolutions.contains("203 DPI"))
            assertTrue(info.pageSizes.contains("4x6 Shipping Label"))
            assertTrue(info.pageSizes.contains("2.25x1.25 Address Label"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testParseCustomPpd() {
        val ppdContent = """
            *PPD-Adobe: "4.3"
            *Manufacturer: "CustomMaker"
            *ModelName: "Special Laser 5000"
            *NickName: "CustomMaker Special Laser 5000 PS"
            *ColorDevice: True
            *Resolution 600dpi/600 DPI: ""
            *PageSize Letter/US Letter: ""
        """.trimIndent()

        val tempFile = File.createTempFile("custom_laser", ".ppd")
        try {
            tempFile.writeText(ppdContent)
            val ppdManager = PpdManager()

            val info = ppdManager.parsePpd(tempFile, isBundled = false)
            assertNotNull(info)
            assertEquals("CustomMaker", info!!.manufacturer)
            assertEquals("Special Laser 5000", info.modelName)
            assertTrue(info.isColor)
            assertFalse(info.isBundled)
            assertEquals(DriverCategory.CUSTOM, info.category)
            assertTrue(info.resolutions.contains("600 DPI"))
            assertTrue(info.pageSizes.contains("US Letter"))
        } finally {
            tempFile.delete()
        }
    }
}
