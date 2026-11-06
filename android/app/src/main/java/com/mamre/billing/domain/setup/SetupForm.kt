package com.mamre.billing.domain.setup

import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.settingsProblemMessage
import com.mamre.billing.domain.admin.validateSettings

/** The Set up your business form (Doc 1 s2.1 FR-2): the same limits as Admin > Settings. */
data class SetupForm(val businessName: String, val address: String, val phone: String, val footer: String)

/** What is wrong with the form, or null. Nothing else is checked here. */
fun setupFormProblem(form: SetupForm): String? {
    validateSettings(BusinessSettings(form.businessName, form.address, form.phone, form.footer)).firstOrNull()?.let { return settingsProblemMessage(it) }
    return null
}
