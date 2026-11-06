package com.mamre.billing.domain.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5, Doc 1 s3: the Add and Edit product rules, pure. Both sides of every limit, and the duplicates by case and spaces. */
class ProductRulesTest {
    private val existing = listOf(
        ExistingProduct("p1", "Mamre Fresh Chapathi", "FRESH"),
        ExistingProduct("p2", "Mamre Chapathi", "CHAPATHI"),
    )

    private fun form(
        name: String = "Roti",
        code: String = "ROTI",
        packet: Int = 12,
        yield: Int = 32,
        active: Boolean = true,
    ) = ProductForm(name, code, packet, yield, active)

    private fun problems(f: ProductForm, selfId: String? = null) = validateProductForm(f, existing, selfId)

    @Test fun aGoodFormHasNoProblem() = assertTrue(problems(form()).isEmpty())

    @Test fun theNameIsTwoToFortyCharactersAfterTrimming() {
        assertEquals(setOf(ProductProblem.NAME_LENGTH), problems(form(name = "R")))
        assertEquals(setOf(ProductProblem.NAME_LENGTH), problems(form(name = "  R  ")))
        assertEquals(setOf(ProductProblem.NAME_LENGTH), problems(form(name = "   ")))
        assertTrue(problems(form(name = "Ro")).isEmpty())
        assertTrue(problems(form(name = "x".repeat(40))).isEmpty())
        assertEquals(setOf(ProductProblem.NAME_LENGTH), problems(form(name = "x".repeat(41))))
    }

    @Test fun theNameIsUniqueIgnoringCaseAndExtraSpaces() {
        assertEquals(setOf(ProductProblem.NAME_TAKEN), problems(form(name = "mamre   FRESH chapathi ")))
        assertEquals(setOf(ProductProblem.NAME_TAKEN), problems(form(name = "MAMRE CHAPATHI")))
        assertTrue(problems(form(name = "Mamre Chapathi Large")).isEmpty())
    }

    @Test fun theCodeIsOneToTwelveLettersOrDigitsAndIsUpperCased() {
        assertEquals("ROTI1", cleanProductCode("  roti1 "))
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "")))
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "   ")))
        assertTrue(problems(form(code = "R")).isEmpty())
        assertTrue(problems(form(code = "ABCDEF123456")).isEmpty())
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "ABCDEF1234567")))
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "RO TI")))
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "RO-TI")))
        assertEquals(setOf(ProductProblem.CODE_INVALID), problems(form(code = "RÖTI")))
    }

    @Test fun theCodeIsUniqueIgnoringCase() {
        assertEquals(setOf(ProductProblem.CODE_TAKEN), problems(form(code = "fresh")))
        assertEquals(setOf(ProductProblem.CODE_TAKEN), problems(form(code = " Chapathi ")))
    }

    @Test fun anEditMayKeepItsOwnNameAndCodeButNotTakeAnothersProduct() {
        assertTrue(problems(form(name = "mamre fresh chapathi", code = "fresh"), selfId = "p1").isEmpty())
        assertEquals(
            setOf(ProductProblem.NAME_TAKEN, ProductProblem.CODE_TAKEN),
            problems(form(name = "Mamre Chapathi", code = "CHAPATHI"), selfId = "p1"),
        )
    }

    @Test fun thePacketSizeIsOneToTwoHundred() {
        assertEquals(setOf(ProductProblem.PACKET_SIZE), problems(form(packet = 0)))
        assertTrue(problems(form(packet = 1)).isEmpty())
        assertTrue(problems(form(packet = 200)).isEmpty())
        assertEquals(setOf(ProductProblem.PACKET_SIZE), problems(form(packet = 201)))
        assertEquals(setOf(ProductProblem.PACKET_SIZE), problems(form(packet = -3)))
    }

    @Test fun theYieldPerKgIsOneToTheMaximum() {
        assertEquals(setOf(ProductProblem.YIELD), problems(form(yield = 0)))
        assertTrue(problems(form(yield = 1)).isEmpty())
        assertTrue(problems(form(yield = MAX_YIELD_PER_KG)).isEmpty())
        assertEquals(setOf(ProductProblem.YIELD), problems(form(yield = MAX_YIELD_PER_KG + 1)))
    }

    @Test fun severalProblemsAreAllReportedAndEveryOneHasAPlainMessage() {
        val all = problems(form(name = "R", code = "", packet = 0, yield = 0))
        assertEquals(setOf(ProductProblem.NAME_LENGTH, ProductProblem.CODE_INVALID, ProductProblem.PACKET_SIZE, ProductProblem.YIELD), all)
        for (p in ProductProblem.entries) assertTrue(productProblemMessage(p).isNotBlank())
    }
}
