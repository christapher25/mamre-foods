package com.mamre.billing.domain.usecase

import com.mamre.billing.data.local.CustomerEntity
import com.mamre.billing.data.local.ExpenseEntity
import com.mamre.billing.data.local.MaterialPurchaseEntity
import com.mamre.billing.data.local.OpeningStockEntity
import com.mamre.billing.data.local.PriceDefaultEntity
import com.mamre.billing.data.local.PriceOverrideEntity
import com.mamre.billing.data.local.ProductionDamageEntity
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.Stored
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.ExpenseRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.StockRepository
import com.mamre.billing.data.repo.toAdmin
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.admin.CustomerForm
import com.mamre.billing.domain.admin.ExpenseCheck
import com.mamre.billing.domain.admin.MAX_WASTAGE_BP
import com.mamre.billing.domain.admin.MAX_YIELD_PER_KG
import com.mamre.billing.domain.admin.PriceCheck
import com.mamre.billing.domain.admin.cleanVoidReason
import com.mamre.billing.domain.admin.formatQuantity
import com.mamre.billing.domain.admin.formatRecipeQuantity
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.priceProblemMessage
import com.mamre.billing.domain.admin.validateCustomerForm
import com.mamre.billing.domain.admin.validateExpense
import com.mamre.billing.domain.admin.validateNewPrice
import com.mamre.billing.domain.admin.validateSettings
import com.mamre.billing.domain.model.CustomerIdentity
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.checkCustomerIdentity
import com.mamre.billing.domain.model.identityKey
import com.mamre.billing.domain.model.identityProblemMessage
import com.mamre.billing.domain.model.normalizeSpaces
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.MAX_PACKET_SIZE
import com.mamre.billing.domain.worker.isValidPacketSize
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// The write side of the Admin area (Doc 1 s2, s4, s9, s10; Doc 2 s9). One transaction per use case; every rule is checked
// here and not only on the screen. Purchases, expenses and damage are add only: a mistake is a linked reversing entry with
// a reason, and a reversal cannot be reversed (Doc 1 s9.4, AT-15). Every change that Doc 2 s4.2 names is written to the
// change log in the same transaction.

private fun describe(c: CustomerEntity, typeName: String) =
    "${c.name}, location '${c.location}', ${if (c.isCorporate) "corporate account" else "not corporate"}, $typeName, " +
        "${if (c.paymentMode == Stored.CREDIT) "Credit" else "Cash"}, phone '${c.phone}', address '${c.address}', " +
        "notes '${c.notes}', ${if (c.isActive) "active" else "inactive"}"

private fun checkForm(form: CustomerForm, typeName: String?, others: List<CustomerIdentity>) {
    val problems = validateCustomerForm(form)
    if (problems.isNotEmpty()) refuse("Check the customer: ${problems.joinToString { it.name.lowercase().replace('_', ' ') }}")
    // Name plus location is unique, ignoring case and extra spaces (Doc 1 s4.1); a missing location follows the same section.
    checkCustomerIdentity(form.name, form.location, typeName ?: refuse("Unknown customer type"), others)
        ?.let { refuse(identityProblemMessage(it)) }
}

private fun CustomerForm.toEntity(id: String, opening: Long) = CustomerEntity(
    id = id,
    name = normalizeSpaces(name),
    nameKey = identityKey(name),
    location = normalizeSpaces(location),
    locationKey = identityKey(location),
    typeId = typeId,
    phone = phone.trim(),
    address = address.trim(),
    paymentMode = if (paymentMode == PaymentMode.CREDIT) Stored.CREDIT else Stored.CASH,
    isCorporate = isCorporate,
    openingBalanceCents = opening,
    notes = notes.trim(),
    isActive = isActive,
)

/** Adds a customer (Doc 1 s4.1, A-13): a name plus location that is new, a type, and an optional opening balance. */
class AddCustomer(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** [id] is a new UUID unless the caller needs a fixed one (the debug sample data keeps stable ids). */
    suspend operator fun invoke(form: CustomerForm, id: String = UUID.randomUUID().toString()): AdminCustomer = unitOfWork.run {
        val type = customers.type(form.typeId)
        checkForm(form, type?.name, customers.customers().map { CustomerIdentity(it.id, it.name, it.location) })
        val row = form.toEntity(id, form.openingBalanceCents)
        customers.insert(row)
        changeLog.add(clock.millis(), "Add customer ${row.name}", "-", describe(row, type!!.name))
        row.toAdmin(type.name)
    }
}

/** Edits a customer (Doc 1 s4.1): the opening balance is not editable, and an edit is not compared with the customer itself. */
class EditCustomer(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(id: String, form: CustomerForm): AdminCustomer = unitOfWork.run {
        val old = customers.customer(id) ?: refuse("Customer not found")
        val type = customers.type(form.typeId)
        checkForm(form, type?.name, customers.customers().filter { it.id != id }.map { CustomerIdentity(it.id, it.name, it.location) })
        val oldType = customers.type(old.typeId)?.name.orEmpty()
        val updated = form.toEntity(id, old.openingBalanceCents)
        customers.update(updated)
        changeLog.add(clock.millis(), "Edit customer ${old.name}", describe(old, oldType), describe(updated, type!!.name))
        updated.toAdmin(type.name)
    }
}

/**
 * Adds a default price for a customer type and product (Doc 1 s4.3): above zero, with an effective-from date LATER than the
 * current one for that type and product. History is kept: a price is added, never rewritten.
 */
class SetDefaultPrice(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(productId: String, typeId: String, priceCents: Long, from: LocalDate) {
        unitOfWork.run {
            val product = prices.products().firstOrNull { it.id == productId } ?: refuse("Unknown product")
            val type = customers.type(typeId) ?: refuse("Unknown customer type")
            val latest = prices.defaults().filter { it.productId == productId && it.customerTypeId == typeId }.maxByOrNull { it.effectiveFrom }
            val check = validateNewPrice(centsToPlain(priceCents), from, latest?.effectiveFrom)
            if (check is PriceCheck.Invalid) refuse(check.problems.joinToString { priceProblemMessage(it, latest?.effectiveFrom) })
            prices.addDefault(PriceDefaultEntity(UUID.randomUUID().toString(), productId, typeId, priceCents, from))
            val before = latest?.let { "${formatCents(it.unitPriceCents)} from ${it.effectiveFrom}" } ?: "no price"
            changeLog.add(clock.millis(), "Price ${product.name} / ${type.name}", before, "${formatCents(priceCents)} from $from")
        }
    }
}

/** Adds an override price for one customer and product (Doc 1 s4.2): same rules as a default price. */
class SetOverridePrice(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(customerId: String, productId: String, priceCents: Long, from: LocalDate, note: String) {
        unitOfWork.run {
            val customer = customers.customer(customerId) ?: refuse("Customer not found")
            val product = prices.products().firstOrNull { it.id == productId } ?: refuse("Unknown product")
            val latest = prices.overridesOf(customerId).filter { it.productId == productId && it.isActive }.maxByOrNull { it.effectiveFrom }
            val check = validateNewPrice(centsToPlain(priceCents), from, latest?.effectiveFrom)
            if (check is PriceCheck.Invalid) refuse(check.problems.joinToString { priceProblemMessage(it, latest?.effectiveFrom) })
            prices.addOverride(PriceOverrideEntity(UUID.randomUUID().toString(), customerId, productId, priceCents, from, note.trim(), true))
            val before = latest?.let { "${formatCents(it.unitPriceCents)} from ${it.effectiveFrom}" } ?: "no override"
            changeLog.add(clock.millis(), "Override ${customer.name} / ${product.name}", before, "${formatCents(priceCents)} from $from")
        }
    }
}

/** Switches an override off (the rows stay): the customer falls back to the type's default (Doc 1 s4.2). */
class ClearOverridePrice(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(customerId: String, productId: String) {
        unitOfWork.run {
            val customer = customers.customer(customerId) ?: refuse("Customer not found")
            val product = prices.products().firstOrNull { it.id == productId } ?: refuse("Unknown product")
            val active = prices.overridesOf(customerId).filter { it.productId == productId && it.isActive }
            if (active.isEmpty()) refuse("There is no override to clear")
            val latest = active.maxBy { it.effectiveFrom }
            prices.deactivateOverrides(active.map { it.id })
            changeLog.add(clock.millis(), "Clear override ${customer.name} / ${product.name}", "${formatCents(latest.unitPriceCents)} from ${latest.effectiveFrom}", "no override")
        }
    }
}

/** The "Salesman can edit price" switch of a customer type (Doc 1 s4.3): change-logged, enforced by [MakeBill]. */
class SetSalesmanCanEditPrice(
    private val unitOfWork: UnitOfWork,
    private val customers: CustomerRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(typeId: String, allowed: Boolean) {
        unitOfWork.run {
            val type = customers.type(typeId) ?: refuse("Unknown customer type")
            if (type.salesmanCanEditPrice == allowed) refuse("${type.name} already has that setting")
            customers.setSalesmanCanEditPrice(typeId, allowed)
            fun text(b: Boolean) = if (b) "salesman can edit price" else "salesman cannot edit price"
            changeLog.add(clock.millis(), "Price rule ${type.name}", text(type.salesmanCanEditPrice), text(allowed))
        }
    }
}

/** Adds a material purchase (Doc 1 s9.4): add only; quantity and total above zero. */
class AddPurchase(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(materialId: String, date: LocalDate, qtyMilli: Long, totalCents: Long, note: String): MaterialPurchaseEntity =
        unitOfWork.run {
            val material = stock.materials().firstOrNull { it.id == materialId } ?: refuse("Unknown material")
            if (qtyMilli <= 0) refuse("The quantity must be more than zero")
            if (totalCents <= 0) refuse("The total paid must be more than $0.00")
            val row = MaterialPurchaseEntity(UUID.randomUUID().toString(), materialId, date, qtyMilli, totalCents, note.trim(), null, "")
            stock.addPurchase(row)
            changeLog.add(clock.millis(), "Add purchase ${material.name}", "-", "${formatQuantity(qtyMilli, material.toAdmin())} for ${formatCents(totalCents)} on $date")
            row
        }
}

/**
 * Corrects a purchase without editing it (Doc 1 s9.4, AT-15): a new row linked to the original, with negative quantity and
 * total, dated today, with a required reason. The original stays visible. A purchase is reversed once and a reversal is
 * never reversed (the unique index on reverses_id backs the first rule).
 */
class ReversePurchase(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(purchaseId: String, reason: String): MaterialPurchaseEntity = unitOfWork.run {
        val clean = cleanVoidReason(reason) ?: refuse("A reversal needs a reason")
        val original = stock.purchase(purchaseId) ?: refuse("Purchase not found")
        if (original.reversesId != null) refuse("A reversing entry cannot be reversed; add a new purchase instead")
        if (stock.reversalOfPurchase(purchaseId) != null) refuse("This purchase is already reversed")
        val material = stock.materials().first { it.id == original.materialId }
        val today = LocalDate.now(clock)
        val row = MaterialPurchaseEntity(
            id = UUID.randomUUID().toString(),
            materialId = original.materialId,
            purchasedOn = today,
            qtyMilli = -original.qtyMilli,
            totalPaidCents = -original.totalPaidCents,
            note = "Reverses ${original.id}",
            reversesId = original.id,
            reason = clean,
        )
        stock.addPurchase(row)
        changeLog.add(
            clock.millis(),
            "Reverse purchase ${material.name}",
            "${formatQuantity(original.qtyMilli, material.toAdmin())} for ${formatCents(original.totalPaidCents)} on ${original.purchasedOn}",
            "reversed on $today: $clean",
        )
        row
    }
}

/** Adds an expense (Doc 1 s10.2): add only; a category, a date and an amount above zero. */
class AddExpense(
    private val unitOfWork: UnitOfWork,
    private val expenses: ExpenseRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(categoryId: String, date: LocalDate, amountCents: Long, description: String): ExpenseEntity =
        unitOfWork.run {
            val check = validateExpense(categoryId, date, centsToPlain(amountCents))
            if (check is ExpenseCheck.Invalid) refuse("Check the expense: ${check.problems.joinToString { it.name.lowercase().replace('_', ' ') }}")
            val category = expenses.categories().firstOrNull { it.id == categoryId } ?: refuse("Unknown category")
            val row = ExpenseEntity(UUID.randomUUID().toString(), categoryId, date, amountCents, description.trim(), null, "")
            expenses.add(row)
            changeLog.add(clock.millis(), "Add expense ${category.name}", "-", "${formatCents(amountCents)} on $date ${row.description}".trim())
            row
        }
}

/** Same rule as [ReversePurchase], for an expense (Doc 1 s10.2, AT-15). */
class ReverseExpense(
    private val unitOfWork: UnitOfWork,
    private val expenses: ExpenseRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(expenseId: String, reason: String): ExpenseEntity = unitOfWork.run {
        val clean = cleanVoidReason(reason) ?: refuse("A reversal needs a reason")
        val original = expenses.expense(expenseId) ?: refuse("Expense not found")
        if (original.reversesId != null) refuse("A reversing entry cannot be reversed; add a new expense instead")
        if (expenses.reversalOf(expenseId) != null) refuse("This expense is already reversed")
        val category = expenses.categories().first { it.id == original.categoryId }
        val today = LocalDate.now(clock)
        val row = ExpenseEntity(UUID.randomUUID().toString(), original.categoryId, today, -original.amountCents, "Reverses ${original.id}", original.id, clean)
        expenses.add(row)
        changeLog.add(
            clock.millis(),
            "Reverse expense ${category.name}",
            "${formatCents(original.amountCents)} on ${original.expenseDate} ${original.description}".trim(),
            "reversed on $today: $clean",
        )
        row
    }
}

/** Adds production damage in chapathis (Doc 1 s7.2): add only; it counts in material usage. */
class AddProductionDamage(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(productId: String, date: LocalDate, chapathis: Int, note: String): ProductionDamageEntity = unitOfWork.run {
        val product = stock.product(productId) ?: refuse("Unknown product")
        if (chapathis <= 0) refuse("Chapathis must be more than zero")
        val row = ProductionDamageEntity(UUID.randomUUID().toString(), productId, date, chapathis, note.trim())
        stock.addDamage(row)
        changeLog.add(clock.millis(), "Add production damage ${product.name}", "-", "$chapathis chapathis on $date ${row.note}".trim())
        row
    }
}

/** Opening stock, set once per material when records start (Doc 1 s9.4): a second entry for the same material is refused. */
class SetOpeningStock(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(materialId: String, month: YearMonth, qtyMilli: Long, valueCents: Long) {
        unitOfWork.run {
            val material = stock.materials().firstOrNull { it.id == materialId } ?: refuse("Unknown material")
            if (stock.openingStock().any { it.materialId == materialId }) refuse("The opening stock of ${material.name} is already set")
            if (qtyMilli < 0 || valueCents < 0) refuse("Opening stock cannot be negative")
            stock.addOpeningStock(OpeningStockEntity(UUID.randomUUID().toString(), materialId, month.toString(), qtyMilli, valueCents))
            changeLog.add(clock.millis(), "Opening stock ${material.name}", "-", "${formatQuantity(qtyMilli, material.toAdmin())} worth ${formatCents(valueCents)} for $month")
        }
    }
}

/** Sets a recipe quantity per 1 kg of wheat (Doc 1 s9.2), above zero, for a material already on the product's recipe. */
class SetRecipeQuantity(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(productId: String, materialId: String, qtyMilli: Long) {
        unitOfWork.run {
            val product = stock.product(productId) ?: refuse("Unknown product")
            val material = stock.materials().firstOrNull { it.id == materialId } ?: refuse("Unknown material")
            val line = stock.recipe().firstOrNull { it.productId == productId && it.materialId == materialId }
                ?: refuse("${material.name} is not in the recipe of ${product.name}")
            if (qtyMilli <= 0) refuse("The quantity must be more than zero")
            fun text(q: Long?) = q?.let { formatRecipeQuantity(it, material.baseUnit) } ?: "not set"
            stock.setRecipeQuantity(productId, materialId, qtyMilli)
            changeLog.add(clock.millis(), "Recipe ${product.name} / ${material.name}", text(line.qtyMilliPerKgWheat), text(qtyMilli))
        }
    }
}

/** Wastage in basis points, 0 to 5% (Doc 1 A-20). */
class SetWastage(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(basisPoints: Int) {
        unitOfWork.run {
            if (basisPoints < 0 || basisPoints > MAX_WASTAGE_BP) refuse("Wastage must be between 0% and 5%")
            val old = settings.wastageBp()
            settings.put(SettingKeys.WASTAGE_BP, basisPoints.toString())
            changeLog.add(clock.millis(), "Wastage", "${formatWastage(old)}%", "${formatWastage(basisPoints)}%")
        }
    }
}

/** The standard packet size of a product, 1 to 200 chapathis (Doc 1 s3, A-19). Custom packet prices follow it. */
class SetStandardPacketSize(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(productId: String, chapathis: Int) {
        unitOfWork.run {
            val product = stock.product(productId) ?: refuse("Unknown product")
            if (!isValidPacketSize(chapathis)) refuse("A packet holds 1 to $MAX_PACKET_SIZE chapathis")
            if (chapathis == product.standardPacketSize) refuse("That is already the standard packet size")
            stock.setStandardPacketSize(productId, chapathis)
            changeLog.add(clock.millis(), "Standard packet ${product.name}", "${product.standardPacketSize} chapathis", "$chapathis chapathis")
        }
    }
}

/** Chapathis made from 1 kg of wheat (Doc 1 s9.2), 1 to 200. */
class SetYieldPerKg(
    private val unitOfWork: UnitOfWork,
    private val stock: StockRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(productId: String, chapathisPerKg: Int) {
        unitOfWork.run {
            val product = stock.product(productId) ?: refuse("Unknown product")
            if (chapathisPerKg < 1 || chapathisPerKg > MAX_YIELD_PER_KG) refuse("The yield is 1 to $MAX_YIELD_PER_KG chapathis per kg of wheat")
            stock.setYieldPerKg(productId, chapathisPerKg)
            changeLog.add(clock.millis(), "Yield ${product.name}", "${product.yieldPerKg} per kg", "$chapathisPerKg per kg")
        }
    }
}

/** Saves the business header (Doc 1 A-31): the name is required; the bill reads these settings and nothing in code. */
class SaveBusinessSettings(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(new: BusinessSettings) {
        unitOfWork.run {
            if (validateSettings(new).isNotEmpty()) refuse("The business needs a name")
            val old = settings.all()
            fun text(name: String?, address: String?, phone: String?, footer: String?) = "$name | $address | $phone | $footer"
            val before = text(old[SettingKeys.BUSINESS_NAME].orEmpty(), old[SettingKeys.ADDRESS].orEmpty(), old[SettingKeys.PHONE].orEmpty(), old[SettingKeys.FOOTER_TEXT].orEmpty())
            settings.put(SettingKeys.BUSINESS_NAME, new.businessName.trim())
            settings.put(SettingKeys.ADDRESS, new.address.trim())
            settings.put(SettingKeys.PHONE, new.phone.trim())
            settings.put(SettingKeys.FOOTER_TEXT, new.footerText.trim())
            changeLog.add(clock.millis(), "Edit business settings", before, text(new.businessName, new.address, new.phone, new.footerText))
        }
    }
}
