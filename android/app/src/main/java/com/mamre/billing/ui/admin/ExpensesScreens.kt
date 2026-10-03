package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.data.admin.DataSpan
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.CategoryTotal
import com.mamre.billing.domain.admin.Expense
import com.mamre.billing.domain.admin.ExpenseCategory
import com.mamre.billing.domain.admin.ExpenseCheck
import com.mamre.billing.domain.admin.ExpenseProblem
import com.mamre.billing.domain.admin.ExpensesReport
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.Material
import com.mamre.billing.domain.admin.MaterialUnit
import com.mamre.billing.domain.admin.Purchase
import com.mamre.billing.domain.admin.PurchaseCheck
import com.mamre.billing.domain.admin.PurchaseProblem
import com.mamre.billing.domain.admin.StockReport
import com.mamre.billing.domain.admin.StockRow
import com.mamre.billing.domain.admin.bagsTimes
import com.mamre.billing.domain.admin.formatQuantity
import com.mamre.billing.domain.admin.purchaseProblemMessage
import com.mamre.billing.domain.admin.validateExpense
import com.mamre.billing.domain.admin.validatePurchase
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.money.formatTenThousandths
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B6 Expenses (Doc 1 s10; owner costing spec 3 and 5). Purchases and other expenses are add only: a mistake
// is corrected by a reversing entry with a reason. Every figure on the Materials tab comes from the server.

private const val INCOMPLETE = "INCOMPLETE"
private const val NEGATIVE_STOCK = "NEGATIVE STOCK"
private const val NOT_AVAILABLE = "—"

data class ExpensesUi(
    val loading: Boolean = true,
    val span: DataSpan? = null,
    val month: YearMonth? = null,
    val tab: Int = 0,
    val stock: StockReport? = null,
    val purchases: List<Purchase> = emptyList(),
    val expenses: ExpensesReport? = null,
    val error: String? = null,
)

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(ExpensesUi())
    val ui: StateFlow<ExpensesUi> = _ui.asStateFlow()
    private val by get() = session.profile?.fullName ?: DEFAULT_ADMIN_NAME

    init {
        viewModelScope.launch {
            val span = api.span()
            _ui.update { it.copy(span = span, month = span.last) }
            api.revision.collect { load() }
        }
    }

    private suspend fun load() {
        val month = _ui.value.month ?: return
        _ui.update { it.copy(loading = false, stock = api.stock(month), purchases = api.purchases(month), expenses = api.expenses(month)) }
    }

    fun selectMonth(month: YearMonth) {
        _ui.update { it.copy(month = month) }
        viewModelScope.launch { load() }
    }

    fun selectTab(tab: Int) = _ui.update { it.copy(tab = tab, error = null) }

    fun reversePurchase(id: String, reason: String) = attempt { api.reversePurchase(id, reason, by) }

    fun reverseExpense(id: String, reason: String) = attempt { api.reverseExpense(id, reason, by) }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun ExpensesScreen(
    onAddPurchase: () -> Unit,
    onAddExpense: () -> Unit,
    onBack: () -> Unit,
    viewModel: ExpensesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ExpensesContent(
        ui, onBack, viewModel::selectTab, viewModel::selectMonth, onAddPurchase, onAddExpense,
        viewModel::reversePurchase, viewModel::reverseExpense,
    )
}

@Composable
fun ExpensesContent(
    ui: ExpensesUi,
    onBack: () -> Unit,
    onTab: (Int) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onAddPurchase: () -> Unit,
    onAddExpense: () -> Unit,
    onReversePurchase: (String, String) -> Unit,
    onReverseExpense: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Expenses", onBack = onBack, actions = { DemoChip() })
        TabRow(selectedTabIndex = ui.tab, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = ui.tab == 0, onClick = { onTab(0) }, text = { Text("MATERIALS", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = ui.tab == 1, onClick = { onTab(1) }, text = { Text("OTHER EXPENSES", style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) })
        }
        val span = ui.span
        val month = ui.month
        if (span == null || month == null) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MonthSelector(month, span.first, span.last, onMonth, inProgress = month == span.last)
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (ui.tab == 0) {
                ui.stock?.let { MaterialsTab(it, ui.purchases, onAddPurchase, onReversePurchase) }
            } else {
                ui.expenses?.let { OtherExpensesTab(it, onAddExpense, onReverseExpense) }
            }
        }
    }
}

// --- materials ---

@Composable
private fun MaterialsTab(
    report: StockReport,
    purchases: List<Purchase>,
    onAdd: () -> Unit,
    onReverse: (String, String) -> Unit,
) {
    var reversing by remember { mutableStateOf<Purchase?>(null) }
    var reason by remember { mutableStateOf("") }
    PrimaryButton("Add purchase", onClick = onAdd)

    AppCard {
        Text("Direct expense this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FigureLine(report.costConsumedTotal, style = MaterialTheme.typography.headlineSmall) { formatCents(it) }
        Text(
            "Cost of the materials consumed, packing included. Calculated, never typed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionHeader("Materials this month")
    report.rows.forEach { MaterialCard(it) }
    AppCard {
        Text("Totals", style = MaterialTheme.typography.titleSmall)
        LabelValueRow("Opening value") { FigureLine(report.openingValueTotal) { formatCents(it) } }
        LabelValueRow("Bought") { Text(formatCents(report.boughtTotalCents)) }
        LabelValueRow("Cost consumed") { FigureLine(report.costConsumedTotal) { formatCents(it) } }
        LabelValueRow("Closing value") { FigureLine(report.closingValueTotal) { formatCents(it) } }
    }

    SectionHeader("Purchases this month")
    val reversed = purchases.mapNotNull { it.reversesId }.toSet()
    AppCard {
        if (purchases.isEmpty()) Text("No purchases this month.", style = MaterialTheme.typography.bodyMedium)
        purchases.forEachIndexed { i, p ->
            val material = report.rows.firstOrNull { it.material.id == p.materialId }?.material
            Column(Modifier.padding(top = if (i == 0) 0.dp else Spacing.md)) {
                LabelValueRow("${formatDate(p.date)} - ${p.materialName}") {
                    Text(formatCents(p.totalCents), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    (material?.let { formatQuantity(p.qtyMb, it) } ?: "") + (if (p.note.isNotBlank()) " - ${p.note}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    p.isReversal -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
                        StatusChip("REVERSAL", kind = ChipKind.WARNING)
                        Text("Reason: ${p.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                    p.id in reversed -> StatusChip("Reversed", modifier = Modifier.padding(top = Spacing.xs))
                    else -> SecondaryButton("Reverse", onClick = {
                        reason = ""
                        reversing = p
                    }, modifier = Modifier.padding(top = Spacing.sm))
                }
            }
        }
    }
    reversing?.let { p ->
        ReasonDialog(
            title = "Reverse purchase",
            message = "Adds a linked entry that cancels ${p.materialName} on ${formatDate(p.date)}. The original stays in the list.",
            reason = reason,
            onReason = { reason = it },
            confirmText = "Reverse",
            onConfirm = {
                onReverse(p.id, reason)
                reversing = null
            },
            onDismiss = { reversing = null },
        )
    }
}

@Composable
private fun MaterialCard(row: StockRow) {
    val m = row.material
    val incomplete = listOf(
        row.openingQtyMb, row.usedMb, row.closingQtyMb, row.avgPriceTt, row.costConsumedCents,
    ).any { it is Figure.Incomplete }
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (row.negativeStock) StatusChip(NEGATIVE_STOCK, kind = ChipKind.WARNING)
                if (incomplete) StatusChip(INCOMPLETE, kind = ChipKind.WARNING)
            }
        }
        Column(Modifier.padding(top = Spacing.sm)) {
            LabelValueRow("Opening stock") { FigureLine(row.openingQtyMb) { formatQuantity(it, m) } }
            LabelValueRow("Bought") { Text(formatQuantity(row.boughtQtyMb, m) + " - " + formatCents(row.boughtCents)) }
            LabelValueRow("Used") { FigureLine(row.usedMb) { formatQuantity(it, m) } }
            LabelValueRow("Closing stock") { FigureLine(row.closingQtyMb) { formatQuantity(it, m) } }
            LabelValueRow("Average price") { FigureLine(row.avgPriceTt) { formatTenThousandths(it) + " / " + m.purchaseUnit } }
            LabelValueRow("Cost consumed") { FigureLine(row.costConsumedCents) { formatCents(it) } }
        }
        val missing = listOf(row.openingQtyMb, row.usedMb, row.avgPriceTt, row.costConsumedCents)
            .filterIsInstance<Figure.Incomplete>().flatMap { it.missing }.distinct()
        missing.forEach {
            Text("Missing: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/** A server figure: its text when known, an INCOMPLETE chip when an input is missing (never a zero). */
@Composable
private fun FigureLine(
    figure: Figure,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    format: (Long) -> String,
) {
    when (figure) {
        is Figure.Known -> Text(format(figure.value), style = style)
        is Figure.Incomplete -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(NOT_AVAILABLE, style = style)
            StatusChip(INCOMPLETE, kind = ChipKind.WARNING)
        }
    }
}

// --- other expenses ---

@Composable
private fun OtherExpensesTab(report: ExpensesReport, onAdd: () -> Unit, onReverse: (String, String) -> Unit) {
    var reversing by remember { mutableStateOf<Expense?>(null) }
    var reason by remember { mutableStateOf("") }
    PrimaryButton("Add expense", onClick = onAdd)
    AppCard {
        Text("Indirect expenses this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatCents(report.indirectTotalCents), style = MaterialTheme.typography.headlineSmall)
        Text(
            "Direct expense (materials consumed, calculated): " + when (val d = report.directExpense) {
                is Figure.Known -> formatCents(d.value)
                is Figure.Incomplete -> INCOMPLETE
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val reversed = report.categories.flatMap { it.entries }.mapNotNull { it.reversesId }.toSet()
    report.categories.forEach { cat: CategoryTotal ->
        AppCard {
            LabelValueRow(cat.category.name) { Text(formatCents(cat.totalCents), style = MaterialTheme.typography.titleSmall) }
            if (cat.entries.isEmpty()) {
                Text("Nothing this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cat.entries.forEach { e ->
                Column(Modifier.padding(top = Spacing.sm)) {
                    LabelValueRow("${formatDate(e.date)}${if (e.description.isNotBlank()) " - ${e.description}" else ""}") {
                        Text(formatCents(e.amountCents), style = MaterialTheme.typography.bodyLarge)
                    }
                    when {
                        e.isReversal -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            StatusChip("REVERSAL", kind = ChipKind.WARNING)
                            Text("Reason: ${e.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                        e.id in reversed -> StatusChip("Reversed")
                        else -> SecondaryButton("Reverse", onClick = {
                            reason = ""
                            reversing = e
                        })
                    }
                }
            }
        }
    }
    reversing?.let { e ->
        ReasonDialog(
            title = "Reverse expense",
            message = "Adds a linked entry that cancels ${formatCents(e.amountCents)} of ${e.categoryName} on ${formatDate(e.date)}. The original stays in the list.",
            reason = reason,
            onReason = { reason = it },
            confirmText = "Reverse",
            onConfirm = {
                onReverse(e.id, reason)
                reversing = null
            },
            onDismiss = { reversing = null },
        )
    }
}

// --- add purchase ---

data class AddPurchaseUi(
    val loading: Boolean = true,
    val materials: List<Material> = emptyList(),
    val today: LocalDate? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class AddPurchaseViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddPurchaseUi())
    val ui: StateFlow<AddPurchaseUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.update { it.copy(loading = false, materials = api.materials(), today = api.today) } }
    }

    fun save(materialId: String, date: LocalDate, qtyMb: Long, totalCents: Long, note: String) {
        viewModelScope.launch {
            try {
                api.addPurchase(materialId, date, qtyMb, totalCents, note, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null, saved = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun AddPurchaseScreen(onBack: () -> Unit, viewModel: AddPurchaseViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    if (ui.loading) return
    AddPurchaseContent(ui, onBack, viewModel::save)
}

@Composable
fun AddPurchaseContent(ui: AddPurchaseUi, onBack: () -> Unit, onSave: (String, LocalDate, Long, Long, String) -> Unit) {
    var materialId by remember { mutableStateOf<String?>(null) }
    var unit by remember { mutableStateOf<MaterialUnit?>(null) }
    var date by remember { mutableStateOf(ui.today) }
    var qty by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var bags by remember { mutableStateOf("") }
    var perBag by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val material = ui.materials.firstOrNull { it.id == materialId }
    val check = validatePurchase(materialId, date, qty, unit, total)
    val problems = (check as? PurchaseCheck.Invalid)?.problems.orEmpty()
    fun msg(vararg of: PurchaseProblem) =
        if (tried) problems.filter { it in of }.joinToString { purchaseProblemMessage(it) }.ifEmpty { null } else null

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Add purchase", onBack = onBack, actions = { DemoChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Material", style = MaterialTheme.typography.titleSmall)
            OptionChips(ui.materials, material, { it.name }, {
                materialId = it.id
                unit = it.units.first { u -> u.label == it.purchaseUnit }
            }, perRow = 2)
            msg(PurchaseProblem.MATERIAL_REQUIRED)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            DateField("Purchase date", date, { date = it }, errorText = msg(PurchaseProblem.DATE_REQUIRED))
            if (material != null) {
                Text("Unit", style = MaterialTheme.typography.titleSmall)
                OptionChips(material.units, unit, { it.label }, { unit = it })
                LabeledTextField(
                    "Quantity in ${unit?.label ?: "unit"}", qty, { qty = it },
                    errorText = msg(PurchaseProblem.QUANTITY_INVALID, PurchaseProblem.QUANTITY_NOT_POSITIVE, PurchaseProblem.UNIT_REQUIRED),
                    placeholder = "400",
                )
                AppCard {
                    Text("Helper: bags x amount per bag", style = MaterialTheme.typography.titleSmall)
                    LabeledTextField("Bags", bags, { bags = it }, placeholder = "16")
                    LabeledTextField("Amount per bag (${unit?.label ?: "unit"})", perBag, { perBag = it }, placeholder = "25")
                    SecondaryButton("Fill the quantity", onClick = { bagsTimes(bags, perBag)?.let { qty = it } }, enabled = bagsTimes(bags, perBag) != null)
                }
            }
            LabeledTextField(
                "Total paid", total, { total = it },
                errorText = msg(PurchaseProblem.TOTAL_INVALID, PurchaseProblem.TOTAL_NOT_POSITIVE),
                placeholder = "440.00",
            )
            LabeledTextField("Note (optional)", note, { note = it })
            Text(
                "A purchase is never edited or deleted. A mistake is corrected with a reversing entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save purchase", onClick = {
                tried = true
                if (check is PurchaseCheck.Ok) onSave(check.materialId, check.date, check.qtyMb, check.totalCents, note)
            })
        }
    }
}

// --- add expense ---

data class AddExpenseUi(
    val loading: Boolean = true,
    val categories: List<ExpenseCategory> = emptyList(),
    val today: LocalDate? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddExpenseUi())
    val ui: StateFlow<AddExpenseUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.update { it.copy(loading = false, categories = api.expenseCategories(), today = api.today) } }
    }

    fun save(categoryId: String, date: LocalDate, amountCents: Long, description: String) {
        viewModelScope.launch {
            try {
                api.addExpense(categoryId, date, amountCents, description, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null, saved = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun AddExpenseScreen(onBack: () -> Unit, viewModel: AddExpenseViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    if (ui.loading) return
    AddExpenseContent(ui, onBack, viewModel::save)
}

@Composable
fun AddExpenseContent(ui: AddExpenseUi, onBack: () -> Unit, onSave: (String, LocalDate, Long, String) -> Unit) {
    var categoryId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(ui.today) }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val check = validateExpense(categoryId, date, amount)
    val problems = (check as? ExpenseCheck.Invalid)?.problems.orEmpty()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Add expense", onBack = onBack, actions = { DemoChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Category", style = MaterialTheme.typography.titleSmall)
            OptionChips(ui.categories, ui.categories.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id }, perRow = 2)
            if (tried && ExpenseProblem.CATEGORY_REQUIRED in problems) {
                Text("Choose a category", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            DateField("Date", date, { date = it }, errorText = if (tried && ExpenseProblem.DATE_REQUIRED in problems) "Choose the date" else null)
            LabeledTextField(
                "Amount", amount, { amount = it },
                errorText = if (tried && (ExpenseProblem.AMOUNT_INVALID in problems || ExpenseProblem.AMOUNT_NOT_POSITIVE in problems)) "Enter an amount above $0.00 like 125.50" else null,
                placeholder = "125.50",
            )
            LabeledTextField("Description (optional)", description, { description = it })
            Text(
                "An expense is never edited or deleted. A mistake is corrected with a reversing entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save expense", onClick = {
                tried = true
                if (check is ExpenseCheck.Ok) onSave(check.categoryId, check.date, check.amountCents, description)
            })
        }
    }
}
