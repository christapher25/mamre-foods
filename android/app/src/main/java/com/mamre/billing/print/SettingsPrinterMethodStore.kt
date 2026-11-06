package com.mamre.billing.print

import com.mamre.billing.data.repo.SettingsRepository

private const val PRINTER_METHOD = "printer_method"

/** Keeps the winning driver's name in the settings table under printer_method (no schema change). */
class SettingsPrinterMethodStore(private val settings: SettingsRepository) : PrinterMethodStore {
    override suspend fun saved(): String? = settings.get(PRINTER_METHOD)

    override suspend fun save(name: String) = settings.put(PRINTER_METHOD, name)
}
