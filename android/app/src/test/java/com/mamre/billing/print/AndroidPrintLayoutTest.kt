package com.mamre.billing.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 item 2c: 32 monospace columns fit the 48 mm printable width of a 58 mm page (pure calculation). */
class AndroidPrintLayoutTest {
    @Test fun thePageIs58mmWithAPrintableWidthOf48mm() {
        assertEquals(58.0, PrintPage.PAGE_MM, 0.0)
        assertEquals(48.0, PrintPage.PRINTABLE_MM, 0.0)
    }

    @Test fun thirtyTwoColumnsFitThePrintableWidthAtTheChosenSize() {
        val widthMm = RECEIPT_WIDTH * PrintPage.glyphWidthPt(PrintPage.FONT_PT) * PrintPage.MM_PER_PT
        assertTrue("$widthMm mm", widthMm <= PrintPage.PRINTABLE_MM)
        assertTrue("font is too small to read: ${PrintPage.FONT_PT}", PrintPage.FONT_PT >= 6f)
    }

    @Test fun halfAPointLargerWouldNotFit() {
        val bigger = PrintPage.FONT_PT + 0.5f
        assertTrue(RECEIPT_WIDTH * PrintPage.glyphWidthPt(bigger) * PrintPage.MM_PER_PT > PrintPage.PRINTABLE_MM)
    }

    @Test fun pageHeightGrowsWithTheLineCount() {
        assertTrue(PrintPage.heightMils(40) > PrintPage.heightMils(10))
    }
}
