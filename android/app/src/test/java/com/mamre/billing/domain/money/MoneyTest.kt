package com.mamre.billing.domain.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun formatsWholeAndFractionalDollars() {
        assertEquals("$81.00", formatCents(8100))
        assertEquals("$0.00", formatCents(0))
        assertEquals("$0.05", formatCents(5))
        assertEquals("$0.99", formatCents(99))
        assertEquals("$1.00", formatCents(100))
        assertEquals("$120.00", formatCents(12000))
    }

    @Test fun formatsNegativeAmountsWithTheSignBeforeTheDollar() {
        assertEquals("-$1.50", formatCents(-150))
        assertEquals("-$0.01", formatCents(-1))
        assertEquals("-$30.00", formatCents(-3000))
    }

    @Test fun groupsThousands() {
        assertEquals("$1,234.56", formatCents(123456))
        assertEquals("$999.99", formatCents(99999))
        assertEquals("$1,000,000.00", formatCents(100_000_000))
        assertEquals("-$12,345.67", formatCents(-1_234_567))
    }

    @Test fun neverOverflowsOnTheExtremes() {
        assertEquals("$92,233,720,368,547,758.07", formatCents(Long.MAX_VALUE))
        assertEquals("-$92,233,720,368,547,758.08", formatCents(Long.MIN_VALUE))
    }

    @Test fun costPerPacketKeepsFourDecimals() {
        assertEquals("$0.6975", formatTenThousandths(6975))
        assertEquals("$0.5475", formatTenThousandths(5475))
        assertEquals("$0.8475", formatTenThousandths(8475))
        assertEquals("$0.0001", formatTenThousandths(1))
        assertEquals("$1.0000", formatTenThousandths(10_000))
        assertEquals("-$0.0500", formatTenThousandths(-500))
    }

    @Test fun parsesTypedAmountsWithoutFloatingPoint() {
        assertEquals(1250L, parseCents("12.50"))
        assertEquals(1250L, parseCents("12.5"))
        assertEquals(1200L, parseCents("12"))
        assertEquals(1200L, parseCents("12."))
        assertEquals(50L, parseCents(".5"))
        assertEquals(5L, parseCents("0.05"))
        assertEquals(0L, parseCents("0"))
        assertEquals(1250L, parseCents("  12.50 "))
        assertEquals(-300L, parseCents("-3"))
        assertEquals(10L, parseCents("0.10"))
        assertEquals(29L, parseCents("0.29")) // 0.29 * 100 is 28.999... as a Double
    }

    @Test fun rejectsInputThatIsNotAnAmount() {
        for (bad in listOf("", " ", ".", "-", "abc", "12.345", "1.2.3", "1,50", "$5", "1e3", "--1", "12 50")) {
            assertNull("'$bad' should be rejected", parseCents(bad))
        }
        assertNull(parseCents("1234567890")) // more than 9 whole digits
    }

    @Test fun parseThenFormatRoundTrips() {
        for (cents in listOf(0L, 1L, 99L, 100L, 8100L, 123456L)) {
            assertEquals(cents, parseCents(formatCents(cents).removePrefix("$").replace(",", "")))
        }
    }
}
