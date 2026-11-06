package com.mamre.billing.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A1 item 2b: choosing the built-in printer among already-paired devices. */
class BluetoothPrinterPickerTest {
    private fun dev(name: String?, major: Int = 0x0000, addr: String = name ?: "x") = PairedDevice(addr, name, major)

    @Test fun oneNamedPrinterIsPicked() {
        val list = listOf(dev("Galaxy Buds"), dev("InnerPrinter"), dev("Car"))
        assertEquals("InnerPrinter", pickPrinter(list)?.name)
    }

    @Test fun eachNamePatternMatches() {
        for (n in listOf("InnerPrinter", "BT Printer", "POS-58", "PT-210", "MPT-II", "mpt-3")) {
            assertEquals(n, pickPrinter(listOf(dev("Phone"), dev(n)))?.name)
        }
    }

    @Test fun aNonPrinterNameContainingPosIsNotPicked() {
        for (n in listOf("Compost Speaker", "Positive Audio", "Deposit")) {
            assertEquals(n, null, pickPrinter(listOf(dev(n), dev("Car")))?.name)
        }
    }

    @Test fun noPairedDevicesGivesNothing() {
        assertNull(pickPrinter(emptyList()))
    }

    @Test fun noPrinterAmongSeveralDevicesGivesNothing() {
        assertNull(pickPrinter(listOf(dev("Galaxy Buds"), dev("Car"))))
    }

    @Test fun aPrinterByClassOnlyIsPicked() {
        val list = listOf(dev("Galaxy Buds", major = 0x0400), dev("XY-1", major = 0x0600, addr = "AA"))
        assertEquals("XY-1", pickPrinter(list)?.name)
    }

    @Test fun aDeviceWithoutANameButPrinterClassIsPicked() {
        assertEquals("AA", pickPrinter(listOf(dev(null, major = 0x0600, addr = "AA")))?.address)
    }

    @Test fun twoNameMatchesPickTheFirstInTheList() {
        val list = listOf(dev("Printer B", addr = "B"), dev("InnerPrinter", addr = "A"))
        assertEquals("B", pickPrinter(list)?.address)
    }

    @Test fun exactlyOnePairedDeviceThatIsAPrinterIsTakenEvenWithAnOddName() {
        assertEquals("ZZ9", pickPrinter(listOf(dev("ZZ9", major = 0x0600)))?.name)
    }
}
