package com.mamre.billing.domain.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quantities are Long thousandths of a unit: 7.5 g is 7500, 5.625 g is 5625 (owner rule: Long units). */
class MilliTest {
    @Test fun parsesDecimalsWithoutFloatingPoint() {
        assertEquals(375_000L, parseMilli("375"))
        assertEquals(7_500L, parseMilli("7.5"))
        assertEquals(5_625L, parseMilli("5.625"))
        assertEquals(750L, parseMilli("0.75"))
        assertEquals(750L, parseMilli(".75"))
        assertEquals(30_000L, parseMilli("30."))
        assertEquals(0L, parseMilli("0"))
        assertEquals(17_500L, parseMilli(" 17.5 "))
        assertEquals(-1_000L, parseMilli("-1"))
        assertEquals(1_001L, parseMilli("1.001")) // 1.001 * 1000 is 1000.9999999999999 as a Double
    }

    @Test fun rejectsWhatIsNotAQuantity() {
        for (bad in listOf("", " ", ".", "-", "abc", "1.2345", "1.2.3", "1,5", "5 kg", "1e3", "--1")) {
            assertNull("'$bad'", parseMilli(bad))
        }
        assertNull(parseMilli("1234567890")) // more than 9 whole digits
    }

    @Test fun formatsBackWithoutTrailingZeros() {
        assertEquals("375", formatMilli(375_000))
        assertEquals("7.5", formatMilli(7_500))
        assertEquals("5.625", formatMilli(5_625))
        assertEquals("0.75", formatMilli(750))
        assertEquals("0.001", formatMilli(1))
        assertEquals("0", formatMilli(0))
        assertEquals("17.5", formatMilli(17_500))
        assertEquals("-1.5", formatMilli(-1_500))
        assertEquals("1234.567", formatMilli(1_234_567))
    }

    @Test fun parseThenFormatRoundTrips() {
        for (milli in listOf(0L, 1L, 750L, 5_625L, 7_500L, 375_000L, 1_234_567L)) {
            assertEquals(milli, parseMilli(formatMilli(milli)))
        }
    }
}
