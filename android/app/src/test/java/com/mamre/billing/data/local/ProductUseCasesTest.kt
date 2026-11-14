package com.mamre.billing.data.local

import com.mamre.billing.domain.admin.ProductForm
import com.mamre.billing.domain.setup.PriceKey
import com.mamre.billing.domain.setup.setupGaps
import com.mamre.billing.domain.usecase.AddProduct
import com.mamre.billing.domain.usecase.EditProduct
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.ui.worker.WorkerCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B5, Doc 1 s3 and s4.2: Add product and Edit product through the use cases, on the real database, read back AFTER THE DATABASE IS
 * CLOSED AND OPENED AGAIN. A new product has no price and cannot be sold; an inactive one is not offered; nothing is ever deleted.
 */
@RunWith(RobolectricTestRunner::class)
class ProductUseCasesTest {
    private val file = "product-use-cases-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private suspend fun first(): World = World(TestDatabase.file(file)).also { it.seed() }

    private fun reopen(w: World): World {
        w.db.close()
        return World(TestDatabase.file(file), w.clock)
    }

    private fun World.add() = AddProduct(unitOfWork, stock, changeLog, clock)

    private fun World.edit() = EditProduct(unitOfWork, stock, changeLog, clock)

    private fun form(name: String = "Mamre Roti", code: String = "roti", packet: Int = 10, yield: Int = 30, active: Boolean = true) =
        ProductForm(name, code, packet, yield, active)

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    // ---------------------------------------------------------------- AddProduct

    @Test fun aNewProductIsSavedWithItsFieldsTrimmedAndTheCodeUpperCasedAndSurvivesReopening() = runBlocking {
        var w = first()
        val before = w.stock.products().size
        val id = w.add()(form(name = "  Mamre   Roti ", code = " roti1 ", packet = 10, yield = 30, active = true))
        w = reopen(w)
        val row = w.stock.product(id)!!
        assertEquals(before + 1, w.stock.products().size)
        assertEquals("Mamre Roti", row.name)
        assertEquals("ROTI1", row.code)
        assertEquals(10, row.standardPacketSize)
        assertEquals(30, row.yieldPerKg)
        assertTrue(row.isActive)
        w.db.close()
    }

    @Test fun addingAProductWritesOneChangeLogEntryNamingIt() = runBlocking {
        val w = first()
        val logBefore = w.changeLog.all().size
        w.add()(form(name = "Mamre Roti"))
        val added = w.changeLog.all().drop(logBefore)
        assertEquals(1, added.size)
        assertEquals("Add product Mamre Roti", added.single().what)
        w.db.close()
    }

    @Test fun aRefusedProductChangesNothingNotEvenTheLog() = runBlocking {
        val w = first()
        val products = w.stock.products()
        val log = w.changeLog.all().size
        assertTrue(refused { w.add()(form(name = "R")) }.contains("name"))
        assertTrue(refused { w.add()(form(code = "")) }.contains("code"))
        assertTrue(refused { w.add()(form(name = "mamre  FRESH chapathi")) }.contains("name"))
        assertTrue(refused { w.add()(form(code = "fresh")) }.contains("code"))
        assertTrue(refused { w.add()(form(packet = 0)) }.contains("packet"))
        assertTrue(refused { w.add()(form(yield = 0)) }.contains("yield"))
        assertEquals(products, w.stock.products())
        assertEquals(log, w.changeLog.all().size)
        w.db.close()
    }

    @Test fun twoNewProductsCannotShareACodeEvenInAnotherCase() = runBlocking {
        val w = first()
        w.add()(form(name = "Mamre Roti", code = "ROTI"))
        assertTrue(refused { w.add()(form(name = "Mamre Roti Two", code = "roti")) }.contains("code"))
        assertTrue(refused { w.add()(form(name = "mamre roti", code = "ROTI2")) }.contains("name"))
        w.db.close()
    }

    // ---------------------------------------------------------------- a new product has no price

    @Test fun aNewProductWithoutAPriceCannotBeSoldAndTheMessageSaysSo() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
        val customer = w.customer("Cafe One")
        val id = w.add()(form(name = "Mamre Roti", code = "ROTI"))
        val line = w.line(product = id, name = "Mamre Roti", qty = 2, unit = 100)
        val message = refused { w.makeBill(w.draft(customer.id, listOf(line))) }
        assertEquals("No price set for Mamre Roti - contact admin", message)
        assertEquals(0, w.sales.state().invoices.size)
        w.db.close()
    }

    @Test fun aNewProductIsCountedAsMissingPricesByTheFinishSetupCard() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
        val types = w.customers.types().map { it.id }
        fun missing(): Int {
            val products = runBlocking { w.prices.products().filter { it.isActive }.map { it.id } }
            val keys = runBlocking { w.prices.defaults().map { PriceKey(it.productId, it.customerTypeId) } }
            return setupGaps(emptyMap(), products, types, keys).missingPrices
        }
        val before = missing()
        w.add()(form(name = "Mamre Roti", code = "ROTI"))
        assertEquals(before + types.size, missing())
        w.db.close()
    }

    @Test fun onceItHasAPriceTheNewProductCanBeSold() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
        val customer = w.customer("Cafe One")
        val id = w.add()(form(name = "Mamre Roti", code = "ROTI", packet = 12))
        w.setDefaultPrice(id, ReferenceIds.TYPE_RESTAURANT, 150, java.time.LocalDate.of(2026, 1, 1))
        val bill = w.makeBill(w.draft(customer.id, listOf(w.line(product = id, name = "Mamre Roti", qty = 2, unit = 150))))
        assertEquals(300L, bill.totalCents)
        w.db.close()
    }

    // ---------------------------------------------------------------- EditProduct

    @Test fun editingChangesNameCodeAndActiveWritesOneLogEntryWithBeforeAndAfterAndSurvivesReopening() = runBlocking {
        var w = first()
        val id = w.add()(form(name = "Mamre Roti", code = "ROTI"))
        val logBefore = w.changeLog.all().size
        w.edit()(id, " Mamre  Roti Large ", "rotil", false)
        w = reopen(w)
        val row = w.stock.product(id)!!
        assertEquals("Mamre Roti Large", row.name)
        assertEquals("ROTIL", row.code)
        assertFalse(row.isActive)
        val log = w.changeLog.all().drop(logBefore)
        assertEquals(1, log.size)
        assertEquals("Edit product Mamre Roti", log.single().what)
        assertTrue(log.single().beforeText.contains("Mamre Roti") && log.single().beforeText.contains("ROTI") && log.single().beforeText.contains("active"))
        assertTrue(log.single().afterText.contains("Mamre Roti Large") && log.single().afterText.contains("ROTIL") && log.single().afterText.contains("inactive"))
        w.db.close()
    }

    @Test fun editingNeverTouchesThePacketSizeOrTheYieldAndANoChangeEditIsRefused() = runBlocking {
        val w = first()
        val id = w.add()(form(name = "Mamre Roti", code = "ROTI", packet = 10, yield = 30))
        w.edit()(id, "Mamre Roti Two", "ROTI", true)
        val row = w.stock.product(id)!!
        assertEquals(10, row.standardPacketSize)
        assertEquals(30, row.yieldPerKg)
        assertTrue(refused { w.edit()(id, "Mamre Roti Two", "roti", true) }.contains("changed"))
        w.db.close()
    }

    @Test fun aRefusedEditChangesNothingAndAProductMayKeepItsOwnNameAndCode() = runBlocking {
        val w = first()
        val id = w.add()(form(name = "Mamre Roti", code = "ROTI"))
        val before = w.stock.product(id)
        val log = w.changeLog.all().size
        assertTrue(refused { w.edit()(id, "Mamre Fresh Chapathi", "ROTI", true) }.contains("name"))
        assertTrue(refused { w.edit()(id, "Mamre Roti", "fresh", true) }.contains("code"))
        assertTrue(refused { w.edit()(id, "R", "ROTI", true) }.contains("name"))
        assertTrue(refused { w.edit()("no-such-product", "Mamre Roti", "ROTI", true) }.contains("product"))
        assertEquals(before, w.stock.product(id))
        assertEquals(log, w.changeLog.all().size)
        w.edit()(id, "mamre roti", "roti", false) // the same name and code in another case, active switched off: allowed
        assertFalse(w.stock.product(id)!!.isActive)
        w.db.close()
    }

    @Test fun anInactiveProductIsNotOfferedInANewBillAndCannotBeBilledButOldBillsAreUnchanged() = runBlocking {
        val w = first()
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 240)
        val customer = w.customer("Cafe One")
        val oldBill = w.makeBill(w.draft(customer.id, listOf(w.line(qty = 2, unit = 250))))
        w.edit()(ReferenceIds.PRODUCT_FRESH, "Mamre Fresh Chapathi", "FRESH", false)
        val offered = WorkerCatalog(w.customers, w.prices).load().products.map { it.id }
        assertFalse(ReferenceIds.PRODUCT_FRESH in offered)
        assertTrue(ReferenceIds.PRODUCT_CHAPATHI in offered)
        val message = refused { w.makeBill(w.draft(customer.id, listOf(w.line(qty = 1, unit = 250)))) }
        assertTrue(message, message.contains("not an active product"))
        assertNotNull(w.sales.invoiceRecord(oldBill.id))
        assertEquals(500L, w.sales.invoiceRecord(oldBill.id)!!.totalCents)
        assertEquals("Mamre Fresh Chapathi", w.sales.invoiceRecord(oldBill.id)!!.lines.single().productName)
        w.db.close()
    }

    @Test fun aProductIsNeverDeleted() {
        val methods = ProductDao::class.java.methods.map { it.name.lowercase() }
        assertTrue(methods.none { it.contains("delete") || it.contains("remove") })
        assertNull(null)
    }
}
