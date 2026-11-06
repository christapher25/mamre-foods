package com.mamre.billing.domain.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4, Doc 1 s10.2: the Add category name rule, pure. Both sides of each limit; duplicates by case and by extra spaces. */
class CategoryRulesTest {
    private val existing = listOf("Electricity", "Water", "Delivery charge", "Other")

    private fun problems(name: String) = validateCategoryName(name, existing)

    @Test fun aGoodNameHasNoProblem() = assertTrue(problems("Rent").isEmpty())

    @Test fun theNameIsTwoToThirtyCharactersAfterTrimming() {
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems("R"))
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems("  R  "))
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems(""))
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems("     "))
        assertTrue(problems("Re").isEmpty())
        assertTrue(problems("x".repeat(30)).isEmpty())
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems("x".repeat(31)))
    }

    @Test fun innerSpacesAreCollapsedBeforeTheLengthIsCounted() {
        assertEquals("Pest control", cleanCategoryName("  Pest     control "))
        // 30 letters with a run of spaces inside collapses to a single space, so it still fits.
        assertTrue(problems("a".repeat(14) + "          " + "b".repeat(15)).isEmpty())
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), problems("a".repeat(16) + "          " + "b".repeat(15)))
    }

    @Test fun aDuplicateIsRefusedIgnoringCaseAndExtraSpaces() {
        assertEquals(setOf(CategoryProblem.NAME_TAKEN), problems("electricity"))
        assertEquals(setOf(CategoryProblem.NAME_TAKEN), problems("delivery  CHARGE"))
        assertEquals(setOf(CategoryProblem.NAME_TAKEN), problems("  Delivery charge "))
        assertTrue(problems("Delivery charges").isEmpty())
    }

    @Test fun aTooShortNameIsReportedOnlyAsTooShortNotAsADuplicate() {
        assertEquals(setOf(CategoryProblem.NAME_LENGTH), validateCategoryName("O", listOf("O")))
    }

    @Test fun theMessagesAreExactlyThePlainSentences() {
        assertEquals("The name is 2 to 30 characters", categoryProblemMessage(CategoryProblem.NAME_LENGTH))
        assertEquals("A category with this name already exists", categoryProblemMessage(CategoryProblem.NAME_TAKEN))
    }
}
