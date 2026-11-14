package com.mamre.billing.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1 item 2b and AT-5: exact ESC/POS bytes for the four golden bills. Golden byte files live in resources/golden-escpos. */
class EscPosTest {
    private fun goldenLines(name: String) =
        javaClass.getResourceAsStream("/golden/$name")!!.bufferedReader().readLines()

    private fun goldenBytes(name: String): ByteArray = javaClass.getResourceAsStream("/golden-escpos/$name.bin")!!.readBytes()

    private val names = listOf(
        "bill_corporate_credit", "bill_credit_part_payment", "bill_cash_walk_in", "payment_receipt_zelle",
    )

    @Test fun theFourGoldenBillsEncodeToTheExactGoldenBytes() {
        for (n in names) assertArrayEquals(n, goldenBytes(n), EscPos.encode(goldenLines("$n.txt")))
    }

    @Test fun theStreamStartsWithInitAndTheHeaderIsBoldAndCentred() {
        val bytes = EscPos.encode(goldenLines("bill_cash_walk_in.txt"))
        assertArrayEquals(byteArrayOf(0x1B, 0x40), bytes.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01), bytes.copyOfRange(2, 8))
        assertEquals("MAMRE FOODS\n", String(bytes.copyOfRange(8, 20), Charsets.US_ASCII))
    }

    @Test fun theHeaderEndsAtTheFirstRuleThenBodyIsLeftAlignedAndNotBold() {
        val bytes = EscPos.encode(listOf("  HEAD", "--------", "Body"))
        val expected = byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 1, 0x1B, 0x45, 1) + "HEAD\n".toByteArray() +
            byteArrayOf(0x1B, 0x45, 0, 0x1B, 0x61, 0) + "--------\nBody\n\n\n\n".toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test fun nonAsciiBecomesAQuestionMark() {
        val bytes = EscPos.encode(listOf("Café ₹ 5"))
        assertTrue(String(bytes, Charsets.US_ASCII).contains("Caf? ? 5\n"))
        assertTrue(bytes.all { it >= 0 })
    }
}
