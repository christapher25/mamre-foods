package com.mamre.billing.print

import com.mamre.billing.domain.worker.PaymentMethod
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doc 2 s7 and s7.1 with change sets D1 to D4: 32 columns, a pure function, golden files, Bill No, Date and Time on
 * their own lines, the salesman's name, customer name and location, the item table, PAID and CREDIT lines, and for a
 * corporate account a signature block and no balance. The customer type is never printed.
 */
class ReceiptLayoutTest {
    private val address = BUSINESS_ADDRESS_LINES

    private val chapathi12 = ReceiptItem("Mamre Chapathi", 70, 250, 17_500, 12)
    private val fresh12 = ReceiptItem("Mamre Fresh Chapathi", 25, 280, 7_000, 12)

    /** The owner's reference bill: a corporate credit bill for FreshMart, Downtown (1). */
    private val corporate = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLines = address,
        number = "MAM-W1-0042",
        issuedAt = LocalDateTime.of(2026, 10, 3, 7, 32),
        salesmanName = "Rajesh Thomas",
        customerName = "FreshMart",
        customerLocation = "Downtown (1)",
        items = listOf(chapathi12, fresh12),
        totalCents = 24_500,
        paidNowCents = 0,
        method = null,
        // Data a corporate bill must ignore even if it is handed over:
        balanceAfterCents = 24_500,
        month = MonthSummary("Oct 2026", 0, 24_500, 0, emptyList(), 24_500),
        isCorporate = true,
    )

    /** A normal credit customer who paid part of the bill now, with the month summary. */
    private val credit = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLines = address,
        number = "MAM-W1-0042",
        issuedAt = LocalDateTime.of(2026, 10, 10, 14, 20),
        salesmanName = "Rajesh",
        customerName = "Spice Garden",
        customerLocation = "Irving",
        items = listOf(ReceiptItem("Mamre Chapathi", 10, 250, 2_500, 12), ReceiptItem("Mamre Fresh Chapathi", 20, 280, 5_600, 12)),
        totalCents = 8_100,
        paidNowCents = 3_000,
        method = PaymentMethod.CASH,
        balanceAfterCents = 12_100,
        month = MonthSummary(
            monthLabel = "Oct 2026",
            broughtForwardCents = 0,
            invoicedCents = 20_100,
            creditsCents = 0,
            payments = listOf(
                MonthPayment(LocalDate.of(2026, 10, 5), PaymentMethod.ZELLE, 5_000),
                MonthPayment(LocalDate.of(2026, 10, 10), PaymentMethod.CASH, 3_000),
            ),
            totalDueCents = 12_100,
        ),
    )

    /** A walk-in who paid cash for a standard and a custom packet. */
    private val walkIn = InvoiceReceipt(
        businessName = "MAMRE FOODS",
        addressLines = address,
        number = "MAM-W1-0043",
        issuedAt = LocalDateTime.of(2026, 10, 10, 14, 25),
        salesmanName = "Rajesh",
        customerName = "Walk-in",
        items = listOf(ReceiptItem("Mamre Fresh Chapathi", 3, 350, 1_050, 12), ReceiptItem("Mamre Chapathi", 2, 400, 800, 10)),
        totalCents = 1_850,
        paidNowCents = 1_850,
        method = PaymentMethod.CASH,
        balanceAfterCents = null,
        month = null,
    )

    /** A left and a right text on one 32-column line, built independently of the production code. */
    private fun lr(left: String, right: String) = left + " ".repeat(32 - left.length - right.length) + right

    private fun golden(name: String): List<String> =
        javaClass.getResourceAsStream("/golden/$name")!!
            .bufferedReader().readText().replace("\r\n", "\n").trimEnd('\n').split("\n")

    // ------------------------------------------------------------------ the three golden bills

    @Test fun aCorporateCreditBillMatchesItsGoldenFile() {
        assertEquals(golden("bill_corporate_credit.txt"), layoutInvoiceReceipt(corporate))
    }

    @Test fun aNormalCreditBillWithAPartPaymentAndAMonthSummaryMatchesItsGoldenFile() {
        assertEquals(golden("bill_credit_part_payment.txt"), layoutInvoiceReceipt(credit))
    }

    @Test fun aCashWalkInBillMatchesItsGoldenFile() {
        assertEquals(golden("bill_cash_walk_in.txt"), layoutInvoiceReceipt(walkIn))
    }

    // ------------------------------------------------------------------ the header block

    @Test fun theTypeLineIsNeverPrinted() {
        for (r in listOf(corporate, credit, walkIn)) {
            assertTrue(layoutInvoiceReceipt(r).none { it.startsWith("Type") })
        }
    }

    @Test fun billNoDateAndTimeAreSeparateLinesInThatOrderThenSalesmanAndCustomer() {
        val lines = layoutInvoiceReceipt(credit)
        val i = lines.indexOf("Bill No:  MAM-W1-0042")
        assertEquals(listOf("Date:     10/10/2026", "Time:     14:20", "Salesman: Rajesh", "Customer: Spice Garden", "          Irving"), lines.subList(i + 1, i + 6))
        assertTrue(lines.none { it.startsWith("Invoice:") })
    }

    @Test fun theSalesmansNameIsPrintedNeverTheDeviceCode() {
        val lines = layoutInvoiceReceipt(credit)
        assertTrue(lines.contains("Salesman: Rajesh"))
        assertTrue(lines.none { it.startsWith("Worker") })
        assertTrue(lines.none { it.contains("W1") && !it.contains("MAM-W1-") })
        val long = layoutInvoiceReceipt(credit.copy(salesmanName = "Rajesh Thomas Kuruvilla Mathew Panicker"))
        val at = long.indexOfFirst { it.startsWith("Salesman: ") }
        assertEquals(listOf("Salesman: Rajesh Thomas", "          Kuruvilla Mathew", "          Panicker"), long.subList(at, at + 3))
        assertTrue(long.all { it.length <= RECEIPT_WIDTH })
    }

    @Test fun theLocationIsOnTheNextLineIndentedTenAndWrapsUnderItself() {
        val lines = layoutInvoiceReceipt(credit)
        val i = lines.indexOf("Customer: Spice Garden")
        assertEquals("          Irving", lines[i + 1])
        val noLocation = layoutInvoiceReceipt(credit.copy(customerLocation = ""))
        val j = noLocation.indexOf("Customer: Spice Garden")
        assertEquals("--------------------------------", noLocation[j + 1]) // no empty location line
        val long = layoutInvoiceReceipt(credit.copy(customerLocation = "North Dallas Tollway Service Road East"))
        val at = long.indexOfFirst { it.startsWith("Customer: ") }
        assertEquals(listOf("          North Dallas Tollway", "          Service Road East"), long.subList(at + 1, at + 3))
    }

    @Test fun aLongCustomerNameWrapsUnderItsLabel() {
        val lines = layoutInvoiceReceipt(credit.copy(customerName = "Spice Garden Indian Restaurant Group"))
        val i = lines.indexOfFirst { it.startsWith("Customer: ") }
        assertEquals("Customer: Spice Garden Indian", lines[i])
        assertEquals("          Restaurant Group", lines[i + 1])
    }

    @Test fun anAddressReplacesThePendingLineAndNoAddressSaysSo() {
        assertTrue(layoutInvoiceReceipt(credit).none { it.contains("pending") })
        val none = layoutInvoiceReceipt(credit.copy(addressLines = emptyList()))
        assertEquals("   (address / phone pending)", none[1])
        val one = layoutInvoiceReceipt(credit.copy(addressLines = listOf("12 Main St 555-0100")))
        assertEquals("      12 Main St 555-0100", one[1])
    }

    // ------------------------------------------------------------------ the item table

    @Test fun theItemNameIsInCapitalsWithTheChapathisPerPacketForStandardAndCustomPackets() {
        val lines = layoutInvoiceReceipt(walkIn)
        assertTrue(lines.contains("MAMRE FRESH CHAPATHI 12NOS")) // a standard packet
        assertTrue(lines.contains("MAMRE CHAPATHI 10NOS")) // a custom packet
        assertEquals("Item Desc     Qty  Price     Amt", lines[lines.indexOf("MAMRE FRESH CHAPATHI 12NOS") - 1])
    }

    @Test fun qtyPriceAndAmountAreRightAlignedWithNoDollarSign() {
        val lines = layoutInvoiceReceipt(corporate)
        val row = lines[lines.indexOf("MAMRE CHAPATHI 12NOS") + 1]
        assertEquals("               70   2.50  175.00", row)
        assertTrue(lines.takeWhile { it != lr("TOTAL (USD)", "245.00") }.none { it.contains("$") })
    }

    @Test fun aWideAmountSqueezesTheRowInsteadOfCuttingIt() {
        val big = credit.copy(items = listOf(ReceiptItem("Mamre Fresh Chapathi", 12345, 99999, 1_234_555_155, 6)), totalCents = 1_234_555_155)
        val lines = layoutInvoiceReceipt(big)
        assertTrue(lines.any { it.trim() == "12345 999.99 12345551.55" })
        assertTrue(lines.all { it.length <= RECEIPT_WIDTH })
    }

    // ------------------------------------------------------------------ the payment block

    @Test fun totalIsLabelledUsdAndThePaidAndCreditLinesShowWhatWasTakenAndWhatIsOwed() {
        val lines = layoutInvoiceReceipt(credit)
        assertTrue(lines.contains(lr("TOTAL (USD)", "81.00")))
        assertTrue(lines.contains(lr("PAID (Cash)", "30.00")))
        assertTrue(lines.contains(lr("CREDIT", "51.00")))
        assertTrue(lines.contains(lr("Balance after", "121.00")))
    }

    @Test fun thereIsNoCreditLineWhenEverythingWasPaidAndNoPaidLineWhenNothingWas() {
        val paidInFull = layoutInvoiceReceipt(credit.copy(paidNowCents = 8_100, balanceAfterCents = 20_100))
        assertTrue(paidInFull.none { it.startsWith("CREDIT") })
        assertTrue(paidInFull.contains(lr("PAID (Cash)", "81.00")))
        val nothingPaid = layoutInvoiceReceipt(credit.copy(paidNowCents = 0, method = null, balanceAfterCents = 20_100))
        assertTrue(nothingPaid.none { it.startsWith("PAID") })
        assertTrue(nothingPaid.contains(lr("CREDIT", "81.00")))
    }

    @Test fun aCashWalkInHasNoBalanceAndNoMonthSummary() {
        val lines = layoutInvoiceReceipt(walkIn)
        assertTrue(lines.none { it.startsWith("Balance after") || it.startsWith("THIS MONTH") || it.startsWith("CREDIT") })
        assertEquals("           Thank you!", lines.last())
    }

    @Test fun aReturnCreditAppearsInTheMonthSummaryOnlyWhenThereIsOne() {
        val withCredit = credit.copy(month = credit.month!!.copy(creditsCents = 500, totalDueCents = 11_600))
        assertTrue(layoutInvoiceReceipt(withCredit).contains(lr("Credits", "-\$5.00")))
        assertTrue(layoutInvoiceReceipt(credit).none { it.startsWith("Credits") })
    }

    @Test fun aCreditBalanceOnAccountShowsAsNegative() {
        assertTrue(layoutInvoiceReceipt(credit.copy(balanceAfterCents = -1_500)).contains(lr("Balance after", "-15.00")))
    }

    // ------------------------------------------------------------------ the corporate account (D4)

    @Test fun aCorporateBillHasNoBalanceTotalDueMonthSummaryOrBroughtForward() {
        val text = layoutInvoiceReceipt(corporate).joinToString("\n")
        for (banned in listOf("Balance", "TOTAL DUE", "THIS MONTH", "Brought forward")) {
            assertFalse("$banned must not be printed for a corporate account", text.contains(banned, ignoreCase = true))
        }
    }

    @Test fun aCorporateBillEndsWithTheSignatureBlock() {
        val lines = layoutInvoiceReceipt(corporate)
        val i = lines.indexOf("Received by:")
        assertEquals(listOf("Received by:", "", "_".repeat(32), "Sign and stamp"), lines.subList(i, i + 4))
        assertTrue(layoutInvoiceReceipt(credit).none { it == "Received by:" }) // a normal customer has none
    }

    @Test fun aCorporateBillWithAPartPaymentStillShowsPaidAndCredit() {
        val lines = layoutInvoiceReceipt(corporate.copy(paidNowCents = 10_000, method = PaymentMethod.CHECK))
        assertTrue(lines.contains(lr("PAID (Check)", "100.00")))
        assertTrue(lines.contains(lr("CREDIT", "145.00")))
        assertTrue(lines.none { it.contains("Balance") })
    }

    @Test fun aNormalCustomerIsUnaffectedByTheCorporateRules() {
        val lines = layoutInvoiceReceipt(credit)
        assertTrue(lines.any { it.startsWith("THIS MONTH") } && lines.any { it.startsWith("TOTAL DUE") })
        assertTrue(lines.any { it.startsWith("Brought forward") })
    }

    // ------------------------------------------------------------------ shape rules

    @Test fun noLineIsWiderThan32ColumnsAndNoLineEndsInASpace() {
        val worst = credit.copy(
            businessName = "MAMRE FOODS INCORPORATED OF TEXAS AND OHIO",
            customerName = "A Very Long Restaurant Name That Goes On And On",
            items = listOf(ReceiptItem("Mamre Fresh Chapathi Extra Large Family Pack", 12345, 99999, 1_234_555_155, 200)),
            totalCents = 1_234_555_155,
            balanceAfterCents = -1_234_555_155,
            duplicate = true,
            isVoid = true,
        )
        for (line in layoutInvoiceReceipt(worst)) assertTrue("'$line' is ${line.length} wide", line.length <= RECEIPT_WIDTH)
        for (r in listOf(corporate, credit, walkIn)) for (line in layoutInvoiceReceipt(r)) assertEquals(line.trimEnd(), line)
    }

    @Test fun aReprintCarriesTheLineDuplicateCopyAndNothingElseChanges() {
        val lines = layoutInvoiceReceipt(credit.copy(duplicate = true))
        assertTrue(lines.any { it.trim() == "DUPLICATE COPY" })
        assertFalse(layoutInvoiceReceipt(credit).any { it.contains("DUPLICATE") })
        assertEquals(layoutInvoiceReceipt(credit), lines.filter { it.trim() != "DUPLICATE COPY" })
        assertTrue(layoutInvoiceReceipt(corporate.copy(duplicate = true)).any { it.trim() == "DUPLICATE COPY" })
    }

    @Test fun aVoidInvoiceIsMarkedVoidAndKeepsItsNumber() {
        val lines = layoutInvoiceReceipt(credit.copy(isVoid = true))
        assertTrue(lines.any { it.trim() == "*** VOID ***" })
        assertTrue(lines.any { it == "Bill No:  MAM-W1-0042" })
    }

    // ------------------------------------------------------------------ the payment receipt

    private val payment = PaymentReceipt(
        businessName = "MAMRE FOODS",
        addressLines = address,
        receiptNumber = "RCP-W1-0001",
        paidAt = LocalDateTime.of(2026, 10, 10, 14, 25),
        salesmanName = "Rajesh",
        customerName = "Spice Garden",
        customerLocation = "Irving",
        amountCents = 5_000,
        method = PaymentMethod.ZELLE,
        balanceAfterCents = 7_000,
        note = "",
    )

    @Test fun thePaymentReceiptHasTheSameHeaderBlockAndNoTypeLine() {
        val expected = listOf(
            "          MAMRE FOODS",
            "    1461 E Branch Hollow Dr",
            "   Carrollton , Texas , 75007",
            "     Ph: +1 (972) 927-2119",
            "--------------------------------",
            "Receipt:  RCP-W1-0001",
            "Date:     10/10/2026",
            "Time:     14:25",
            "Salesman: Rajesh",
            "Customer: Spice Garden",
            "          Irving",
            "--------------------------------",
            "Payment received (Zelle)  \$50.00",
            "Balance after             \$70.00",
            "--------------------------------",
            "           Thank you!",
        )
        assertEquals(expected, layoutPaymentReceipt(payment))
        val dup = layoutPaymentReceipt(payment.copy(duplicate = true, note = "Paid at the door"))
        assertTrue(dup.any { it.trim() == "DUPLICATE COPY" })
        assertTrue(dup.contains("Note: Paid at the door"))
        for (line in dup) assertTrue(line.length <= RECEIPT_WIDTH)
    }

    @Test fun aCorporatePaymentReceiptShowsNoBalance() {
        val lines = layoutPaymentReceipt(payment.copy(isCorporate = true))
        assertTrue(lines.none { it.contains("Balance") })
        assertTrue(lines.contains("Payment received (Zelle)  \$50.00"))
    }
}
