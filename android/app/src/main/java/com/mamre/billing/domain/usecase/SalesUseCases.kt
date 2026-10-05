package com.mamre.billing.domain.usecase

import com.mamre.billing.data.local.InvoiceEntity
import com.mamre.billing.data.local.InvoiceItemEntity
import com.mamre.billing.data.local.PaymentEntity
import com.mamre.billing.data.local.ReturnEntity
import com.mamre.billing.data.local.Stored
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.ChangeLogRepository
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.data.repo.stored
import com.mamre.billing.data.repo.toCustomer
import com.mamre.billing.domain.admin.cleanVoidReason
import com.mamre.billing.domain.model.Customer
import com.mamre.billing.domain.model.Product
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.pricing.PriceResult
import com.mamre.billing.domain.pricing.resolvePrice
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.MAX_PACKET_SIZE
import com.mamre.billing.domain.worker.MAX_PRICE_FACTOR
import com.mamre.billing.domain.worker.isValidPacketSize
import com.mamre.billing.domain.worker.PayerKind
import com.mamre.billing.domain.worker.PaymentCheck
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.PaymentRecord
import com.mamre.billing.domain.worker.ReturnReason
import com.mamre.billing.domain.worker.ReturnRecord
import com.mamre.billing.domain.worker.ReturnResolution
import com.mamre.billing.domain.worker.checkInvoicePayment
import com.mamre.billing.domain.worker.checkStandalonePayment
import com.mamre.billing.domain.worker.customPacketPriceCents
import com.mamre.billing.domain.worker.invoiceTotal
import com.mamre.billing.domain.worker.noteMissingForOther
import com.mamre.billing.domain.worker.payerKind
import com.mamre.billing.domain.worker.paymentMessage
import com.mamre.billing.domain.worker.priceLimitProblem
import com.mamre.billing.domain.worker.returnCreditCents
import com.mamre.billing.domain.worker.typeAllowsPriceEdit
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// The write side of the Sales area and the Admin's void (Doc 1 s5, s6, s7; Doc 2 s5.1, s10). Every use case runs in ONE
// transaction ([UnitOfWork]) and enforces its rules itself, not only the screen: a rule broken here writes nothing.
// Each takes a [Clock], so the bill date, payment date and return date can be fixed (the debug demo seeder and tests).

/** What the Owner confirmed at the payment step (Doc 1 s5.1). [id] is made once per bill, so confirming twice stores one. */
data class BillDraft(
    val id: String,
    /** Null for a walk-in sale (Doc 1 s4.1). */
    val customerId: String?,
    val lines: List<InvoiceLine>,
    val paidNowCents: Long,
    val method: PaymentMethod?,
)

/**
 * Makes a bill (Doc 1 s5.1, s5.2): the bill, its items, the payment taken with it and the number sequence are saved in one
 * transaction (Doc 2 I-3). Enforced here:
 *  - at least one packet; every product exists and has a price: no price blocks the sale, never zero (Doc 1 s4.2);
 *  - the list price on each line is the price list's price now, including the half-up custom packet price (s4.2, AT-14);
 *  - a charged price differs from the list price only when the customer type's switch is on (a walk-in follows Retail),
 *    and then above 0 and at most 10 times the list price (Doc 1 s4.3, I-13, AT-13);
 *  - a walk-in pays in full; a cash customer pays at least the total (Doc 1 s4.1, s6.1);
 *  - the customer's name, location and corporate flag are read from the database and saved on the bill (I-7).
 * A bill that fails any rule burns no number. Repeating a draft id returns the bill already stored (Doc 2 I-11).
 */
class MakeBill(
    private val unitOfWork: UnitOfWork,
    private val sales: SalesRepository,
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(draft: BillDraft): InvoiceRecord {
        unitOfWork.run {
            if (sales.invoice(draft.id) != null) return@run
            if (draft.lines.isEmpty()) refuse("A bill needs at least one packet")
            val customerRow = draft.customerId?.let { id ->
                customers.customer(id)?.takeIf { it.isActive } ?: refuse("The customer was not found or is not active")
            }
            val customer: Customer? = customerRow?.toCustomer()
            val book = prices.priceBook()
            val products = prices.products().associateBy { it.id }
            val date = LocalDate.now(clock)
            val priceEditAllowed = typeAllowsPriceEdit(book.customerTypes, customer?.typeId, walkIn = customer == null)

            for (line in draft.lines) {
                val product = products[line.productId]?.takeIf { it.isActive } ?: refuse("${line.productName} is not an active product")
                val domainProduct = Product(product.id, product.code, product.name, product.standardPacketSize, product.isActive)
                val standard = (resolvePrice(customer, domainProduct, date, book) as? PriceResult.Found)?.unitPriceCents
                    ?: refuse("No price set for ${product.name} - contact admin")
                val list = customPacketPriceCents(standard, line.chapathisPerPacket, product.standardPacketSize)
                if (line.listPriceCents != list) refuse("The price of ${product.name} has changed; start the bill again")
                if (line.unitPriceCents != line.listPriceCents) {
                    if (!priceEditAllowed) refuse("The price of this customer type cannot be changed")
                    if (priceLimitProblem(line.unitPriceCents, line.listPriceCents) != null) {
                        refuse("A changed price must be above $0.00 and at most ${formatCents(line.listPriceCents * MAX_PRICE_FACTOR)} (10 times the list price)")
                    }
                }
            }

            val total = invoiceTotal(draft.lines)
            val kind = payerKind(customer)
            val check = checkInvoicePayment(kind, total, 0L, centsToPlain(draft.paidNowCents))
            if (check is PaymentCheck.Rejected) refuse(paymentMessage(check.problem, kind, total))
            if (draft.paidNowCents > 0 && draft.method == null) refuse("Choose how the customer paid")
            if (draft.paidNowCents == 0L && draft.method != null) refuse("A bill with nothing paid has no payment method")

            val now = clock.millis()
            val number = settings.takeNextBillNumber()
            val invoice = InvoiceEntity(
                id = draft.id,
                number = number,
                customerId = customer?.id,
                customerName = customerRow?.name ?: WALK_IN_NAME,
                customerLocation = customerRow?.location.orEmpty(),
                isCorporate = customerRow?.isCorporate == true,
                salesmanName = settings.ownerName(),
                issuedAt = now,
                totalCents = total,
                status = Stored.ACTIVE,
                voidReason = null,
                voidedAt = null,
            )
            val items = draft.lines.map { line ->
                InvoiceItemEntity(
                    id = UUID.randomUUID().toString(),
                    invoiceId = draft.id,
                    productId = line.productId,
                    productName = line.productName,
                    qtyPackets = line.qtyPackets,
                    chapathisPerPacket = line.chapathisPerPacket,
                    unitPriceCents = line.unitPriceCents,
                    listPriceCents = line.listPriceCents,
                    priceOverridden = line.unitPriceCents != line.listPriceCents,
                    lineTotalCents = line.lineTotalCents,
                )
            }
            val payment = draft.method?.takeIf { draft.paidNowCents > 0 }?.let {
                PaymentEntity(
                    id = UUID.randomUUID().toString(),
                    receiptNumber = settings.takeNextReceiptNumber(),
                    customerId = customer?.id,
                    invoiceId = draft.id,
                    amountCents = draft.paidNowCents,
                    method = it.stored(),
                    paidAt = now,
                    note = "",
                )
            }
            sales.insertBill(invoice, items, payment)
        }
        return sales.invoiceRecord(draft.id)!!
    }

    companion object {
        /** The name saved on a walk-in bill: an anonymous Retail sale (Doc 1 s4.1). */
        const val WALK_IN_NAME = "Walk-in"
    }
}

/** What the Owner confirmed on the Record payment screen (Doc 1 A-16). */
data class PaymentDraft(
    val id: String,
    val customerId: String,
    val amountCents: Long,
    val method: PaymentMethod,
    val note: String,
)

/**
 * Records a payment with no new bill (Doc 1 s5.4, A-16): saved customers only, more than zero, and a note when the method
 * is Other (Doc 1 s6.1). The receipt number comes from the saved sequence in the same transaction. The same id stores once.
 */
class RecordPayment(
    private val unitOfWork: UnitOfWork,
    private val sales: SalesRepository,
    private val customers: CustomerRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(draft: PaymentDraft): PaymentRecord {
        unitOfWork.run {
            if (sales.payment(draft.id) != null) return@run
            customers.customer(draft.customerId) ?: refuse("The customer was not found")
            val check = checkStandalonePayment(0L, centsToPlain(draft.amountCents))
            if (check is PaymentCheck.Rejected) refuse(paymentMessage(check.problem, PayerKind.CREDIT_CUSTOMER, draft.amountCents))
            if (noteMissingForOther(draft.method, draft.note)) refuse("Enter a note for a payment made with Other")
            sales.insertPayment(
                PaymentEntity(
                    id = draft.id,
                    receiptNumber = settings.takeNextReceiptNumber(),
                    customerId = draft.customerId,
                    invoiceId = null,
                    amountCents = draft.amountCents,
                    method = draft.method.stored(),
                    paidAt = clock.millis(),
                    note = draft.note.trim(),
                ),
            )
        }
        return sales.paymentRecord(draft.id)!!
    }
}

/**
 * What the Owner confirmed on the Return screen (Doc 1 s7.1). There is no price in it: the use case works the price out
 * ([RecordReturn]). [chapathisPerPacket] is the size of the returned packets (12 for a standard packet).
 */
data class ReturnDraft(
    val id: String,
    val customerId: String,
    val productId: String,
    val qtyPackets: Int,
    val chapathisPerPacket: Int,
    val reason: ReturnReason,
    val resolution: ReturnResolution,
    val invoiceId: String? = null,
    val note: String = "",
)

/**
 * Records a customer return (Doc 1 s7.1, AT-9). Enforced here:
 *  - the credit is worth packets x the LINKED bill's unit price for that product and packet size, or, with no bill linked,
 *    the customer's CURRENT price (override or type default, a custom size priced half up); no price means no return;
 *  - the returned packet size is stored (not the product's standard size), 1 to 200 chapathis;
 *  - a linked bill must be the customer's, must hold that product in that packet size, and the quantity returned cannot
 *    exceed what the bill held less what was already returned against it (credits and replacements both count);
 *  - a Credit lowers the balance, a Replacement changes none.
 * Repeating a draft id stores one return (Doc 2 I-11).
 */
class RecordReturn(
    private val unitOfWork: UnitOfWork,
    private val sales: SalesRepository,
    private val customers: CustomerRepository,
    private val prices: PriceRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(draft: ReturnDraft): ReturnRecord {
        unitOfWork.run {
            if (sales.returnRow(draft.id) != null) return@run
            val customerRow = customers.customer(draft.customerId) ?: refuse("The customer was not found")
            val product = prices.products().firstOrNull { it.id == draft.productId } ?: refuse("The product was not found")
            if (draft.qtyPackets <= 0) refuse("A return needs at least one packet")
            if (!isValidPacketSize(draft.chapathisPerPacket)) refuse("A packet holds 1 to $MAX_PACKET_SIZE chapathis")
            val unitPrice: Long
            val invoiceId = draft.invoiceId
            if (invoiceId != null) {
                val bill = sales.invoice(invoiceId) ?: refuse("The bill was not found")
                if (bill.customerId != draft.customerId) refuse("That bill belongs to another customer")
                val lines = sales.itemsOf(invoiceId).filter { it.productId == draft.productId && it.chapathisPerPacket == draft.chapathisPerPacket }
                if (lines.isEmpty()) refuse("That bill does not contain ${product.name} in packets of ${draft.chapathisPerPacket}")
                val held = lines.sumOf { it.qtyPackets }
                val returned = sales.returnsOfInvoice(invoiceId)
                    .filter { it.productId == draft.productId && it.chapathisPerPacket == draft.chapathisPerPacket }.sumOf { it.qtyPackets }
                val left = held - returned
                if (draft.qtyPackets > left) refuse("Only $left of the $held packets on that bill can still be returned")
                // The packets were sold at this price on this bill (Doc 1 s7.1).
                unitPrice = lines.first().unitPriceCents
            } else {
                val standardProduct = Product(product.id, product.code, product.name, product.standardPacketSize, product.isActive)
                val found = resolvePrice(customerRow.toCustomer(), standardProduct, LocalDate.now(clock), prices.priceBook()) as? PriceResult.Found
                val standard = found?.unitPriceCents ?: refuse("A return needs a price: no price is set for ${product.name}")
                unitPrice = customPacketPriceCents(standard, draft.chapathisPerPacket, product.standardPacketSize)
            }
            if (unitPrice <= 0) refuse("A return needs a price: no price means no credit")
            sales.insertReturn(
                ReturnEntity(
                    id = draft.id,
                    customerId = draft.customerId,
                    invoiceId = invoiceId,
                    productId = draft.productId,
                    qtyPackets = draft.qtyPackets,
                    chapathisPerPacket = draft.chapathisPerPacket,
                    reason = draft.reason.stored(),
                    resolution = draft.resolution.stored(),
                    unitPriceCents = unitPrice,
                    creditCents = returnCreditCents(draft.resolution, draft.qtyPackets, unitPrice),
                    occurredAt = clock.millis(),
                    note = draft.note.trim(),
                ),
            )
        }
        return sales.returnRecord(draft.id)!!
    }
}

/**
 * Voids a bill in the Admin area (Doc 1 s5.4, A-17, Doc 2 I-4): a reason is required, the number stays and the bill shows
 * VOID, and the void is written to the change log. It is the only change a bill ever gets (active to void).
 */
class VoidBill(
    private val unitOfWork: UnitOfWork,
    private val sales: SalesRepository,
    private val changeLog: ChangeLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend operator fun invoke(id: String, reason: String) {
        unitOfWork.run {
            val clean = cleanVoidReason(reason) ?: refuse("A void needs a reason")
            val bill = sales.invoice(id) ?: refuse("Bill not found")
            if (bill.status == Stored.VOID) refuse("Bill ${bill.number} is already void")
            val at = clock.millis()
            if (!sales.markVoid(id, clean, at)) refuse("Bill ${bill.number} could not be voided")
            changeLog.add(at, "Void bill ${bill.number}", "Active, ${formatCents(bill.totalCents)}", "Void: $clean")
        }
    }
}
