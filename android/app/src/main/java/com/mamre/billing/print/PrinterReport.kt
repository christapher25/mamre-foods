package com.mamre.billing.print

/** What the Printer check screen found on this phone (A1 item 6). Text only; nothing leaves the phone except by the share sheet. */
data class PrinterCheckInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkLevel: Int,
    /** "name (class)" per paired device, or empty. */
    val pairedDevices: List<String>,
    val bluetoothNote: String?,
    val printServices: List<String>,
    val printerPackages: List<String>,
    val driverAvailability: List<Pair<String, Boolean>>,
    val savedMethod: String?,
)

/** The report as plain text lines, in the order the brief names them. */
fun printerReportOf(info: PrinterCheckInfo): List<String> = buildList {
    add("PRINTER CHECK")
    add("Manufacturer: ${info.manufacturer}")
    add("Brand: ${info.brand}")
    add("Model: ${info.model}")
    add("Device: ${info.device}")
    add("Android: ${info.androidVersion} (SDK ${info.sdkLevel})")
    add("Saved printer method: ${info.savedMethod ?: "none yet"}")
    add("Paired Bluetooth devices:")
    info.bluetoothNote?.let { add("  $it") }
    if (info.pairedDevices.isEmpty() && info.bluetoothNote == null) add("  none")
    info.pairedDevices.forEach { add("  $it") }
    add("Print services:")
    if (info.printServices.isEmpty()) add("  none")
    info.printServices.forEach { add("  $it") }
    add("Printer packages:")
    if (info.printerPackages.isEmpty()) add("  none")
    info.printerPackages.forEach { add("  $it") }
    add("Drivers:")
    info.driverAvailability.forEach { (name, ok) -> add("  $name: ${if (ok) "available" else "not available"}") }
}

/** Package-name words the report looks for (A1 item 6). */
val PRINTER_PACKAGE_WORDS = listOf("print", "printer", "pos", "sunmi", "imin", "telpo", "urovo", "senraise", "ciontek", "iposprinter")

fun isPrinterPackage(name: String): Boolean = PRINTER_PACKAGE_WORDS.any { name.contains(it, ignoreCase = true) }

/** Wraps report lines to the 32 columns of the paper; long lines break at a space where they can. */
fun wrapTo32(lines: List<String>): List<String> = lines.flatMap { line ->
    if (line.length <= RECEIPT_WIDTH) {
        listOf(line)
    } else {
        val indent = line.takeWhile { it == ' ' }
        val out = mutableListOf<String>()
        var rest = line.trim()
        val room = RECEIPT_WIDTH - indent.length
        while (rest.length > room) {
            val cut = rest.lastIndexOf(' ', room).takeIf { it > 0 } ?: room
            out += indent + rest.take(cut).trimEnd()
            rest = rest.drop(cut).trimStart()
        }
        out + (indent + rest)
    }
}
