package com.mamre.billing.print

import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doc 2 s7 and s7.1: 32 columns, pure function, golden file, DUPLICATE COPY on a reprint. */
class ReceiptLayoutTest {
    /** The numbers of the Doc 2 s7.1 sample (prices invented for illustration there). */
    private val sample = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLine = null,
        number = "MAM-W1-0042",
        issuedAt = LocalDateTime.of(2026, 10, 10, 14, 20),
        salesmanName = "Rajesh",
        customerName = "Spice Garden",
        customerTypeName = "Restaurant",
        items = listOf(
            ReceiptItem("Mamre Chapathi", 10, 250, 2500),
            ReceiptItem("Mamre Fresh Chapathi", 20, 280, 5600),
        ),
        totalCents = 8100,
        paidNowCents = 3000,
        method = PaymentMethod.CASH,
        balanceAfterCents = 12100,
        month = MonthSummary(
            monthLabel = "Oct 2026",
            broughtForwardCents = 0,
            invoicedCents = 20100,
            creditsCents = 0,
            payments = listOf(
                MonthPayment(LocalDate.of(2026, 10, 5), PaymentMethod.ZELLE, 5000),
                MonthPayment(LocalDate.of(2026, 10, 10), PaymentMethod.CASH, 3000),
            ),
            totalDueCents = 12100,
        ),
    )

    private fun golden(): List<String> =
        javaClass.getResourceAsStream("/golden/receipt_doc2_s7_1.txt")!!
            .bufferedReader().readText().replace("\r\n", "\n").trimEnd('\n').split("\n")

    @Test fun reproducesTheDoc2Section71SampleExactly() {
        assertEquals(golden(), layoutInvoiceReceipt(sample))
    }

    @Test fun noLineIsWiderThan32Columns() {
        val worst = sample.copy(
            businessName = "MAMRE FOODS INCORPORATED OF TEXAS AND OHIO",
            customerName = "A Very Long Restaurant Name That Goes On And On",
            items = listOf(ReceiptItem("Mamre Fresh Chapathi Extra Large Family Pack", 12345, 99999, 1_234_555_155)),
            totalCents = 1_234_555_155,
            balanceAfterCents = -1_234_555_155,
            duplicate = true,
            isVoid = true,
        )
        for (line in layoutInvoiceReceipt(worst)) assertTrue("'$line' is ${line.length} wide", line.length <= RECEIPT_WIDTH)
        for (line in layoutInvoiceReceipt(sample)) assertEquals(line.trimEnd(), line) // no trailing spaces
    }

    @Test fun aReprintCarriesTheLineDuplicateCopy() {
        val lines = layoutInvoiceReceipt(sample.copy(duplicate = true))
        assertTrue(lines.any { it.trim() == "DUPLICATE COPY" })
        assertFalse(layoutInvoiceReceipt(sample).any { it.contains("DUPLICATE") })
        // Nothing else changes: removing that one line gives the original.
        assertEquals(layoutInvoiceReceipt(sample), lines.filter { it.trim() != "DUPLICATE COPY" })
    }


    @Test fun theReceiptPrintsTheSalesmansNameAndNeverTheDeviceCode() {
        val lines = layoutInvoiceReceipt(sample)
        assertTrue(lines.contains("Salesman: Rajesh"))
        assertTrue(lines.none { it.startsWith("Worker") })
        assertTrue(lines.none { it.contains("W1") && !it.contains("MAM-W1-") }) // the device code appears only inside the number
        val long = layoutInvoiceReceipt(sample.copy(salesmanName = "Rajesh Thomas Kuruvilla Mathew Panicker"))
        assertTrue(long.all { it.length <= RECEIPT_WIDTH })
        val at = long.indexOfFirst { it.startsWith("Salesman: ") }
        assertEquals(listOf("Salesman: Rajesh Thomas", "          Kuruvilla Mathew", "          Panicker"), long.subList(at, at + 3))
    }
    @Test fun aVoidInvoiceIsMarkedVoidAndKeepsItsNumber() {
        val lines = layoutInvoiceReceipt(sample.copy(isVoid = true))
        assertTrue(lines.any { it.trim() == "*** VOID ***" })
        assertTrue(lines.any { it == "Invoice: MAM-W1-0042" })
    }

    @Test fun aWalkInReceiptHasNoBalanceAndNoMonthSummary() {
        val walkIn = sample.copy(
            customerName = "Walk-in",
            customerTypeName = "Retail",
            paidNowCents = 8100,
            balanceAfterCents = null,
            month = null,
        )
        val lines = layoutInvoiceReceipt(walkIn)
        assertTrue(lines.contains("Customer: Walk-in"))
        assertTrue(lines.contains("Paid now (Cash)           $81.00"))
        assertTrue(lines.none { it.startsWith("Balance after") || it.startsWith("THIS MONTH") })
        assertEquals("           Thank you!", lines.last())
    }

    @Test fun anInvoiceWithNothingPaidShowsNoMethod() {
        val lines = layoutInvoiceReceipt(sample.copy(paidNowCents = 0, method = null, balanceAfterCents = 20100))
        assertTrue(lines.contains("Paid now                   $0.00"))
        assertTrue(lines.contains("Balance after            $201.00"))
    }

    @Test fun aReturnCreditAppearsInTheMonthSummaryOnlyWhenThereIsOne() {
        val withCredit = sample.copy(month = sample.month!!.copy(creditsCents = 500, totalDueCents = 11600))
        assertTrue(layoutInvoiceReceipt(withCredit).contains("Credits                   -$5.00"))
        assertTrue(layoutInvoiceReceipt(sample).none { it.startsWith("Credits") })
    }

    @Test fun anAddressAndPhoneReplaceThePendingLine() {
        val lines = layoutInvoiceReceipt(sample.copy(addressLine = "12 Main St 555-0100"))
        assertEquals("      12 Main St 555-0100", lines[1])
        assertTrue(lines.none { it.contains("pending") })
    }

    @Test fun aCreditBalanceOnAccountShowsAsNegative() {
        val lines = layoutInvoiceReceipt(sample.copy(balanceAfterCents = -1500))
        assertTrue(lines.contains("Balance after            -$15.00"))
    }

    @Test fun aLongCustomerNameWrapsUnderItsLabel() {
        val lines = layoutInvoiceReceipt(sample.copy(customerName = "Spice Garden Indian Restaurant Group"))
        val i = lines.indexOfFirst { it.startsWith("Customer: ") }
        assertEquals("Customer: Spice Garden Indian", lines[i])
        assertEquals("          Restaurant Group", lines[i + 1])
    }

    @Test fun thePaymentReceiptFitsAndMatchesItsLayout() {
        val receipt = PaymentReceipt(
            businessName = "MAMRE FOODS",
            addressLine = null,
            receiptNumber = "RCP-W1-0001",
            paidAt = LocalDateTime.of(2026, 10, 10, 14, 25),
            salesmanName = "Rajesh",
            customerName = "Spice Garden",
            customerTypeName = "Restaurant",
            amountCents = 5000,
            method = PaymentMethod.ZELLE,
            balanceAfterCents = 7000,
            note = "",
        )
        val expected = listOf(
            "          MAMRE FOODS",
            "   (address / phone pending)",
            "--------------------------------",
            "Receipt: RCP-W1-0001",
            "Date:    10/10/2026 14:25",
            "Salesman: Rajesh",
            "Customer: Spice Garden",
            "Type:     Restaurant",
            "--------------------------------",
            "Payment received (Zelle)  $50.00",
            "Balance after             $70.00",
            "--------------------------------",
            "           Thank you!",
        )
        assertEquals(expected, layoutPaymentReceipt(receipt))
        val dup = layoutPaymentReceipt(receipt.copy(duplicate = true, note = "Paid at the door"))
        assertTrue(dup.any { it.trim() == "DUPLICATE COPY" })
        assertTrue(dup.contains("Note: Paid at the door"))
        for (line in dup) assertTrue(line.length <= RECEIPT_WIDTH)
    }
}
