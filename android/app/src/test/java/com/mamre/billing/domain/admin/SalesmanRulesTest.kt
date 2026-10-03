package com.mamre.billing.domain.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Change set D1: a salesman name is trimmed, 2 to 40 characters, letters, spaces and dots, unique ignoring case. */
class SalesmanRulesTest {
    private fun name(raw: String, existing: List<String> = emptyList()) = validateSalesmanName(raw, existing)

    @Test fun aNormalNameIsAcceptedAndTrimmed() {
        assertEquals(SalesmanNameCheck.Ok("Rajesh Thomas"), name("  Rajesh Thomas  "))
        assertEquals(SalesmanNameCheck.Ok("A. K. Nair"), name("A. K. Nair"))
    }

    @Test fun insideSpacesAreCollapsedToOne() {
        assertEquals(SalesmanNameCheck.Ok("Rajesh Thomas"), name("Rajesh    Thomas"))
    }

    @Test fun lengthIsTwoToFortyAfterTrimming() {
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_TOO_SHORT), name(" A "))
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_TOO_SHORT), name("   "))
        assertEquals(SalesmanNameCheck.Ok("Al"), name("Al"))
        assertEquals(SalesmanNameCheck.Ok("A".repeat(40)), name("A".repeat(40)))
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_TOO_LONG), name("A".repeat(41)))
    }

    @Test fun onlyLettersSpacesAndDotsAreAllowed() {
        for (bad in listOf("Rajesh1", "Raj_esh", "Rajesh-Thomas", "Raj@esh", "Rajesh,T", "Raj/esh")) {
            assertEquals(bad, SalesmanNameCheck.Invalid(SalesmanProblem.NAME_BAD_CHARACTERS), name(bad))
        }
    }

    @Test fun lettersOutsideEnglishAreStillLetters() {
        assertEquals(SalesmanNameCheck.Ok("José Núñez"), name("José Núñez"))
    }

    @Test fun aDuplicateIsRefusedIgnoringCaseAndExtraSpaces() {
        val existing = listOf("Rajesh Thomas", "Suresh")
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_DUPLICATE), name("rajesh thomas", existing))
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_DUPLICATE), name("  SURESH ", existing))
        assertEquals(SalesmanNameCheck.Invalid(SalesmanProblem.NAME_DUPLICATE), name("Rajesh   Thomas", existing))
        assertEquals(SalesmanNameCheck.Ok("Rajesh"), name("Rajesh", existing)) // a prefix is a different name
    }

    @Test fun theDuplicateMessageSaysWhatToDo() {
        val text = salesmanProblemMessage(SalesmanProblem.NAME_DUPLICATE)
        assertTrue(text, text.contains("already exists"))
        assertTrue(text, text.contains("capital"))
    }

    @Test fun aLoginNameIsRequiredLettersAndDigitsAndUniqueIgnoringCase() {
        fun login(raw: String, existing: List<String> = emptyList()) =
            validateNewSalesman("Rajesh", raw, emptyList(), existing)
        assertEquals(SalesmanProblem.LOGIN_REQUIRED, (login("  ") as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanProblem.LOGIN_TOO_SHORT, (login("ab") as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanProblem.LOGIN_BAD_CHARACTERS, (login("raj esh") as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanProblem.LOGIN_BAD_CHARACTERS, (login("raj.esh") as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanProblem.LOGIN_TOO_LONG, (login("a".repeat(21)) as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanProblem.LOGIN_DUPLICATE, (login("USER1", listOf("user1")) as SalesmanCheck.Invalid).problem)
        assertEquals(SalesmanCheck.Ok("Rajesh", "raj2"), login(" raj2 "))
    }

    @Test fun theNameIsCheckedBeforeTheLogin() {
        val bad = validateNewSalesman("R", "user1", emptyList(), listOf("user1")) as SalesmanCheck.Invalid
        assertEquals(SalesmanProblem.NAME_TOO_SHORT, bad.problem)
    }
}
