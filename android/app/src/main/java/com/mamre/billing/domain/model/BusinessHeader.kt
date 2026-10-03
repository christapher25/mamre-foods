package com.mamre.billing.domain.model

// The bill header comes from the business settings the Admin edits in Settings and the catalog sync carries (change
// set E4). The salesman app stores them as key/value rows and never holds an address in code.

/** Keys of the business settings in the catalog sync (SettingDto.key). */
object SettingKeys {
    const val BUSINESS_NAME = "business_name"

    /** One printed line per text line. */
    const val ADDRESS = "business_address"
    const val PHONE = "business_phone"
    const val FOOTER = "receipt_footer"
}

/** What the bill header and footer print. Any value can be empty: the bill then prints a placeholder (or nothing for the footer). */
data class BusinessHeader(
    val name: String = "",
    val addressLines: List<String> = emptyList(),
    val phone: String = "",
    val footer: String = "",
)

/** Builds the header from the synced settings; a missing key is an empty value, never a made-up one. */
fun businessHeaderOf(settings: Map<String, String>): BusinessHeader = BusinessHeader(
    name = settings[SettingKeys.BUSINESS_NAME].orEmpty().trim(),
    addressLines = settings[SettingKeys.ADDRESS].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
    phone = settings[SettingKeys.PHONE].orEmpty().trim(),
    footer = settings[SettingKeys.FOOTER].orEmpty().trim(),
)
