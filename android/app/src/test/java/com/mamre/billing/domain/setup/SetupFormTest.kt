package com.mamre.billing.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Doc 1 s2.1 FR-2: name required, address/phone/footer optional with the Settings limits. */
class SetupFormTest {
    private val ok = SetupForm("Mamre Foods", "1 Main Road\nIrving", "9876543210", "Thank you")

    @Test fun aCompleteFormIsFine() = assertNull(setupFormProblem(ok))

    @Test fun onlyTheBusinessNameIsRequired() = assertNull(setupFormProblem(SetupForm("Mamre Foods", "", "", "")))

    @Test fun aMissingBusinessNameIsRefused() = assertNotNull(setupFormProblem(ok.copy(businessName = "  ")))

    @Test fun theAddressIsAtMostFourLinesAndThePhoneAndFooterHaveTheSettingsLimits() {
        assertNotNull(setupFormProblem(ok.copy(address = "a\nb\nc\nd\ne")))
        assertNull(setupFormProblem(ok.copy(address = "a\nb\nc\nd")))
        assertNotNull(setupFormProblem(ok.copy(phone = "1".repeat(21))))
        assertNotNull(setupFormProblem(ok.copy(footer = "f".repeat(81))))
        assertEquals(null, setupFormProblem(ok.copy(footer = "f".repeat(80))))
    }
}
