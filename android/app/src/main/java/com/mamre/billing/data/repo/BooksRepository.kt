package com.mamre.billing.data.repo

import com.mamre.billing.data.local.MamreDatabase
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.Stored
import com.mamre.billing.domain.admin.AdminCustomerType
import com.mamre.billing.domain.admin.AdminInvoice
import com.mamre.billing.domain.admin.AdminPayment
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.ChangeLogEntry
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.ExpenseKind
import com.mamre.billing.domain.admin.InvoiceItem
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.books.BooksSnapshot
import com.mamre.billing.domain.books.DamageRow
import com.mamre.billing.domain.books.OpeningStock
import com.mamre.billing.domain.books.RecipeEntry
import com.mamre.billing.domain.books.ReturnRow
import com.mamre.billing.domain.worker.InvoiceStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loads everything the books are calculated from as one [BooksSnapshot] (Doc 2 s4.2), for the Admin area. The
 * calculations are in BooksLogic; nothing here computes a balance, a cost or a report. Admin data includes the
 * balance of corporate accounts, which the Sales area never shows (Doc 1 s4.1).
 */
class BooksRepository(private val db: MamreDatabase, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    suspend fun snapshot(today: LocalDate): BooksSnapshot {
        val z = zone()
        val ownerName = db.settingDao().get(SettingKeys.OWNER_NAME).orEmpty().trim()
        val settings = db.settingDao().getAll().associate { it.key to it.value }
        val types = db.customerTypeDao().getAll()
        val typeName = types.associate { it.id to it.name }
        val productRows = db.productDao().getAll()
        val customerRows = db.customerDao().getAll()
        val customerById = customerRows.associateBy { it.id }
        val items = db.invoiceItemDao().getAll().groupBy { it.invoiceId }
        val payments = db.paymentDao().getAll()
        val materialRows = db.materialDao().getAll()
        val materialById = materialRows.associateBy { it.id }
        val categoryRows = db.expenseCategoryDao().getAll()
        val categoryById = categoryRows.associateBy { it.id }

        val invoices = db.invoiceDao().getAll().map { inv ->
            val customer = inv.customerId?.let { customerById[it] }
            AdminInvoice(
                id = inv.id,
                number = inv.number,
                customerId = inv.customerId,
                customerName = inv.customerName,
                typeName = customer?.let { typeName[it.typeId] } ?: "Retail",
                deviceCode = deviceCodeOf(inv.number),
                issuedAt = inv.issuedAt.toLocalDateTime(z),
                items = items[inv.id].orEmpty().map { item ->
                    val standard = productRows.firstOrNull { it.id == item.productId }?.standardPacketSize
                    InvoiceItem(
                        productId = item.productId,
                        productName = item.productName,
                        qtyPackets = item.qtyPackets,
                        unitPriceCents = item.unitPriceCents,
                        lineTotalCents = item.lineTotalCents,
                        chapathisPerPacket = item.chapathisPerPacket,
                        listPriceCents = item.listPriceCents,
                        isCustomPacket = standard != null && item.chapathisPerPacket != standard,
                    )
                },
                totalCents = inv.totalCents,
                status = if (inv.status == Stored.VOID) InvoiceStatus.VOID else InvoiceStatus.ACTIVE,
                voidReason = inv.voidReason,
                voidedBy = if (inv.status == Stored.VOID) ownerName.ifEmpty { OWNER_LABEL } else null,
                voidedAt = inv.voidedAt?.toLocalDateTime(z),
            )
        }

        return BooksSnapshot(
            types = types.map { AdminCustomerType(it.id, it.name, it.salesmanCanEditPrice) },
            products = productRows.map { AdminProduct(it.id, it.code, it.name, it.standardPacketSize, 0L, it.yieldPerKg) },
            customers = customerRows.map { c ->
                c.toAdmin(typeName[c.typeId].orEmpty())
            },
            invoices = invoices,
            payments = payments.map { p ->
                val customer = p.customerId?.let { customerById[it] }
                AdminPayment(
                    id = p.id,
                    receiptNumber = p.receiptNumber,
                    customerId = p.customerId,
                    customerName = customer?.name ?: invoices.firstOrNull { it.id == p.invoiceId }?.customerName.orEmpty(),
                    date = p.paidAt.toLocalDateTime(z).toLocalDate(),
                    amountCents = p.amountCents,
                    method = paymentMethodOf(p.method),
                    note = p.note,
                )
            },
            paymentInvoiceIds = payments.filter { it.invoiceId != null }.associate { it.id to it.invoiceId!! },
            returns = db.returnDao().getAll().map { r ->
                val customer = customerById.getValue(r.customerId)
                ReturnRow(
                    id = r.id,
                    date = r.occurredAt.toLocalDateTime(z).toLocalDate(),
                    customerId = r.customerId,
                    customerName = customer.name,
                    typeName = typeName[customer.typeId].orEmpty(),
                    invoiceId = r.invoiceId,
                    productId = r.productId,
                    productName = productRows.firstOrNull { it.id == r.productId }?.name.orEmpty(),
                    qtyPackets = r.qtyPackets,
                    chapathisPerPacket = r.chapathisPerPacket,
                    reason = returnReasonOf(r.reason),
                    resolution = returnResolutionOf(r.resolution),
                    unitPriceCents = r.unitPriceCents,
                    creditCents = r.creditCents,
                )
            },
            categories = categoryRows.map { it.toAdmin() },
            expenses = db.expenseDao().getAll().map { e ->
                val category = categoryById.getValue(e.categoryId)
                Expense(
                    id = e.id,
                    categoryId = e.categoryId,
                    categoryName = category.name,
                    kind = category.toAdmin().kind,
                    date = e.expenseDate,
                    amountCents = e.amountCents,
                    description = e.description,
                    enteredBy = ownerName.ifEmpty { OWNER_LABEL },
                    reversesId = e.reversesId,
                    reason = e.reason,
                )
            },
            overrideNotes = db.priceOverrideDao().getAll().associate { it.id to it.note },
            materials = materialRows.map { it.toAdmin() },
            recipes = db.recipeDao().getAll().groupBy { it.productId }
                .mapValues { (_, rows) -> rows.map { RecipeEntry(it.materialId, it.qtyMilliPerKgWheat) } },
            purchases = db.materialPurchaseDao().getAll().map { p ->
                Purchase(
                    id = p.id,
                    date = p.purchasedOn,
                    materialId = p.materialId,
                    materialName = materialById[p.materialId]?.name.orEmpty(),
                    qtyMb = p.qtyMilli,
                    totalCents = p.totalPaidCents,
                    note = p.note,
                    enteredBy = ownerName.ifEmpty { OWNER_LABEL },
                    reversesId = p.reversesId,
                    reason = p.reason,
                )
            },
            damage = db.productionDamageDao().getAll().map {
                DamageRow(it.id, it.damagedOn, it.productId, it.chapathis, it.note, ownerName.ifEmpty { OWNER_LABEL })
            },
            openingStock = db.openingStockDao().getAll().associate { it.materialId to OpeningStock(it.qtyMilli, it.valueCents) },
            wastageBp = settings[SettingKeys.WASTAGE_BP]?.toIntOrNull() ?: 0,
            settings = BusinessSettings(
                businessName = settings[SettingKeys.BUSINESS_NAME].orEmpty(),
                address = settings[SettingKeys.ADDRESS].orEmpty(),
                phone = settings[SettingKeys.PHONE].orEmpty(),
                footerText = settings[SettingKeys.FOOTER_TEXT].orEmpty(),
            ),
            workers = emptyList(),
            today = today,
        )
    }

    suspend fun changeLog(): List<ChangeLogEntry> {
        val z = zone()
        val owner = db.settingDao().get(SettingKeys.OWNER_NAME).orEmpty().trim().ifEmpty { OWNER_LABEL }
        return db.changeLogDao().getAll().mapIndexed { i, row ->
            ChangeLogEntry((i + 1).toLong(), row.at.toLocalDateTime(z), owner, row.what, row.beforeText, row.afterText)
        }.reversed()
    }

    companion object {
        /** Shown as the person of an entry while the Owner has not typed a name in Settings. */
        const val OWNER_LABEL = "Owner"
    }
}
