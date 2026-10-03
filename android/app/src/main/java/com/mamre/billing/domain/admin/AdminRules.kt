package com.mamre.billing.domain.admin

import com.mamre.billing.domain.money.parseCents
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Rules the Admin screens enforce before anything reaches the server. The server checks them
// again (Doc 2 s8). All pure, so they are tested without a screen.

// --- price edits (B3 override, B4 prices, B5 ingredient prices) ---

enum class PriceProblem { PRICE_REQUIRED, PRICE_NOT_POSITIVE, DATE_REQUIRED, DATE_NOT_LATER }

sealed interface PriceCheck {
    data class Ok(val priceCents: Long, val effectiveFrom: LocalDate) : PriceCheck

    data class Invalid(val problems: Set<PriceProblem>) : PriceCheck
}

/**
 * A new price needs an amount above zero and an effective-from date later than the current row's
 * (Doc 1 s4.3, Doc 2 price history rule). History is kept: a price is added, never rewritten.
 * With no current row any date will do.
 */
fun validateNewPrice(priceText: String, effectiveFrom: LocalDate?, currentEffectiveFrom: LocalDate?): PriceCheck {
    val problems = mutableSetOf<PriceProblem>()
    val cents = parseCents(priceText)
    when {
        cents == null -> problems += PriceProblem.PRICE_REQUIRED
        cents <= 0 -> problems += PriceProblem.PRICE_NOT_POSITIVE
    }
    when {
        effectiveFrom == null -> problems += PriceProblem.DATE_REQUIRED
        currentEffectiveFrom != null && !effectiveFrom.isAfter(currentEffectiveFrom) ->
            problems += PriceProblem.DATE_NOT_LATER
    }
    return if (problems.isEmpty()) PriceCheck.Ok(cents!!, effectiveFrom!!) else PriceCheck.Invalid(problems)
}

fun priceProblemMessage(problem: PriceProblem, currentEffectiveFrom: LocalDate?): String = when (problem) {
    PriceProblem.PRICE_REQUIRED -> "Enter a price like 2.95"
    PriceProblem.PRICE_NOT_POSITIVE -> "The price must be above $0.00"
    PriceProblem.DATE_REQUIRED -> "Choose the date the price starts"
    PriceProblem.DATE_NOT_LATER ->
        "The date must be later than ${currentEffectiveFrom ?: "the current price date"}"
}

// --- void ---

/** A void needs a reason (Doc 1 s5.4). Returns it trimmed, or null when blank. */
fun cleanVoidReason(reason: String): String? = reason.trim().ifEmpty { null }

// --- customers (B3) ---

enum class CustomerProblem { NAME_REQUIRED, TYPE_REQUIRED, OPENING_NEGATIVE }

fun validateCustomerForm(form: CustomerForm): Set<CustomerProblem> = buildSet {
    if (form.name.isBlank()) add(CustomerProblem.NAME_REQUIRED)
    if (form.typeId.isBlank()) add(CustomerProblem.TYPE_REQUIRED)
    if (form.openingBalanceCents < 0) add(CustomerProblem.OPENING_NEGATIVE)
}

// --- expenses (B6, add only) ---

enum class ExpenseProblem { CATEGORY_REQUIRED, DATE_REQUIRED, AMOUNT_INVALID, AMOUNT_NOT_POSITIVE }

sealed interface ExpenseCheck {
    data class Ok(val categoryId: String, val date: LocalDate, val amountCents: Long) : ExpenseCheck

    data class Invalid(val problems: Set<ExpenseProblem>) : ExpenseCheck
}

fun validateExpense(categoryId: String?, date: LocalDate?, amountText: String): ExpenseCheck {
    val problems = mutableSetOf<ExpenseProblem>()
    if (categoryId.isNullOrBlank()) problems += ExpenseProblem.CATEGORY_REQUIRED
    if (date == null) problems += ExpenseProblem.DATE_REQUIRED
    val cents = parseCents(amountText)
    when {
        cents == null -> problems += ExpenseProblem.AMOUNT_INVALID
        cents <= 0 -> problems += ExpenseProblem.AMOUNT_NOT_POSITIVE
    }
    return if (problems.isEmpty()) ExpenseCheck.Ok(categoryId!!, date!!, cents!!) else ExpenseCheck.Invalid(problems)
}

// --- settings (B9) ---

enum class SettingsProblem { NAME_REQUIRED }

fun validateSettings(settings: BusinessSettings): Set<SettingsProblem> =
    if (settings.businessName.isBlank()) setOf(SettingsProblem.NAME_REQUIRED) else emptySet()

// --- invoice list (B2) ---

/** Search by number or customer, newest first, narrowed by month, customer and status. */
fun filterInvoices(invoices: List<AdminInvoice>, filter: InvoiceFilter): List<AdminInvoice> {
    val q = filter.query.trim().lowercase()
    return invoices
        .filter { inv ->
            (q.isEmpty() || inv.number.lowercase().contains(q) || inv.customerName.lowercase().contains(q)) &&
                (filter.month == null || YearMonth.from(inv.issuedAt) == filter.month) &&
                (filter.customerId == null || inv.customerId == filter.customerId) &&
                (!filter.priceChangedOnly || inv.hasChangedPrice) &&
                when (filter.status) {
                    InvoiceStatusFilter.ALL -> true
                    InvoiceStatusFilter.ACTIVE -> !inv.isVoid
                    InvoiceStatusFilter.VOID -> inv.isVoid
                }
        }
        .sortedByDescending { it.issuedAt }
}

// --- months (B1) ---

/** Up to [count] months ending at [selected], never earlier than [earliest]. */
fun monthWindow(selected: YearMonth, count: Int, earliest: YearMonth): List<YearMonth> =
    (count - 1 downTo 0).map { selected.minusMonths(it.toLong()) }.filter { !it.isBefore(earliest) }

fun monthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.US)} ${month.year}"

fun monthShort(month: YearMonth): String = month.month.getDisplayName(TextStyle.SHORT, Locale.US)

// --- chart helpers (B1) ---

/** [part] as a whole percent of [total], 0 to 100, integer arithmetic only; a negative or empty slice is 0. */
fun sharePercent(part: Long, total: Long): Int {
    if (total <= 0 || part <= 0) return 0
    return minOf(part * 100 / total, 100L).toInt()
}
