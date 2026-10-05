package com.mamre.billing.domain.model

// The bill header comes from the business settings the Admin edits in Settings (Doc 1 A-31). The app holds no address in code.

/** What the bill header and footer print. Any value can be empty: the bill then prints a placeholder (or nothing for the footer). */
data class BusinessHeader(
    val name: String = "",
    val addressLines: List<String> = emptyList(),
    val phone: String = "",
    val footer: String = "",
)
