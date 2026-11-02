package com.mamre.billing.print

/** A paired Bluetooth device as plain data; [majorClass] is the Bluetooth major device class (0x0600 = imaging/printer). */
data class PairedDevice(val address: String, val name: String?, val majorClass: Int)

private const val MAJOR_IMAGING = 0x0600
private val PRINTER_NAME = Regex("""(?i)printer|pos|^pt-|^mpt""")

/** A1 item 2b: the built-in printer among already-paired devices, or null. A name match wins, then a printer class. */
fun pickPrinter(paired: List<PairedDevice>): PairedDevice? =
    paired.firstOrNull { it.name?.let(PRINTER_NAME::containsMatchIn) == true }
        ?: paired.firstOrNull { it.majorClass == MAJOR_IMAGING }
