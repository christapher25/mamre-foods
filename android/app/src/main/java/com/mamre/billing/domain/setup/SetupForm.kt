package com.mamre.billing.domain.setup

import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.cleanOwnerName
import com.mamre.billing.domain.admin.settingsProblemMessage
import com.mamre.billing.domain.admin.validateSettings

/** The Set up your business form (Doc 1 s2.1 FR-2): the same limits as Admin > Settings. */
data class SetupForm(val businessName: String, val ownerName: String, val address: String, val phone: String, val footer: String)

/** What is wrong with the form, or null. The owner name may be empty; when given it is 2 to 40 characters. */
fun setupFormProblem(form: SetupForm): String? {
    validateSettings(BusinessSettings(form.businessName, form.address, form.phone, form.footer)).firstOrNull()?.let { return settingsProblemMessage(it) }
    if (form.ownerName.isNotBlank() && cleanOwnerName(form.ownerName) == null) return "The owner name is 2 to 40 characters"
    return null
}
