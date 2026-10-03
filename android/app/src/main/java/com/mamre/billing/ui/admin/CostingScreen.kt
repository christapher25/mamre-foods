package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.data.admin.DataSpan
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.CostingReport
import com.mamre.billing.domain.admin.ProductCost
import com.mamre.billing.domain.admin.ProductRecipe
import com.mamre.billing.domain.admin.RecipeCheck
import com.mamre.billing.domain.admin.WastageCheck
import com.mamre.billing.domain.admin.formatRecipeQuantity
import com.mamre.billing.domain.admin.formatWastage
import com.mamre.billing.domain.admin.recipeProblemMessage
import com.mamre.billing.domain.admin.validateRecipeQuantity
import com.mamre.billing.domain.admin.validateWastage
import com.mamre.billing.domain.admin.wastageProblemMessage
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.money.formatMilli
import com.mamre.billing.domain.money.formatTenThousandths
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B5 Costing (owner costing spec). Every number is calculated by the server; the screen shows it. The recipe
// and the wastage % are editable and every edit is written to the change log.

private const val INCOMPLETE = "INCOMPLETE"

data class CostingUi(
    val loading: Boolean = true,
    val span: DataSpan? = null,
    val month: YearMonth? = null,
    val report: CostingReport? = null,
    val recipes: List<ProductRecipe> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class CostingViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(CostingUi())
    val ui: StateFlow<CostingUi> = _ui.asStateFlow()
    private val by get() = session.profile?.fullName ?: DEFAULT_ADMIN_NAME

    init {
        viewModelScope.launch {
            val span = api.span()
            _ui.update { it.copy(span = span, month = span.last.minusMonths(if (span.last.isAfter(span.first)) 1 else 0)) }
            api.revision.collect { load() }
        }
    }

    private suspend fun load() {
        val month = _ui.value.month ?: return
        _ui.update { it.copy(loading = false, report = api.costing(month), recipes = api.recipes()) }
    }

    fun selectMonth(month: YearMonth) {
        _ui.update { it.copy(month = month) }
        viewModelScope.launch { load() }
    }

    fun setRecipe(productId: String, materialId: String, qtyMb: Long) = attempt { api.setRecipeQuantity(productId, materialId, qtyMb, by) }

    fun setWastage(basisPoints: Int) = attempt { api.setWastageBp(basisPoints, by) }

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
fun CostingScreen(onBack: () -> Unit, viewModel: CostingViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    CostingContent(ui, onBack, viewModel::selectMonth, viewModel::setRecipe, viewModel::setWastage)
}

private data class RecipeEdit(val productId: String, val productName: String, val materialId: String, val materialName: String, val baseUnit: String, val current: Long?)

@Composable
fun CostingContent(
    ui: CostingUi,
    onBack: () -> Unit,
    onMonth: (YearMonth) -> Unit,
    onRecipe: (String, String, Long) -> Unit,
    onWastage: (Int) -> Unit,
) {
    var editRecipe by remember { mutableStateOf<RecipeEdit?>(null) }
    var editWastage by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Costing", onBack = onBack, actions = { DemoChip() })
        val report = ui.report
        val span = ui.span
        val month = ui.month
        if (report == null || span == null || month == null) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MonthSelector(month, span.first, span.last, onMonth, inProgress = month == span.last)
            AppCard {
                LabelValueRow("Wastage on ingredients") {
                    Text("${formatWastage(report.wastageBp)}%", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Packing has no wastage. Indirect expenses ${formatCents(report.indirectTotalCents)} shared over ${report.netPackets} net packets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton("Edit wastage", onClick = { editWastage = true }, modifier = Modifier.padding(top = Spacing.sm))
            }
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            SectionHeader("Cost per packet")
            report.products.forEach { ProductCostCard(it) }

            SectionHeader("Recipe per packet")
            Text(
                "Quantities per packet apply to every month. Each edit is written to the change log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.recipes.forEach { recipe ->
                AppCard {
                    Text(recipe.productName, style = MaterialTheme.typography.titleSmall)
                    recipe.lines.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.materialName, style = MaterialTheme.typography.bodyLarge)
                                if (line.qtyMb == null) {
                                    StatusChip("$INCOMPLETE: quantity not set", kind = ChipKind.WARNING)
                                } else {
                                    Text(
                                        formatRecipeQuantity(line.qtyMb, line.baseUnit),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                editRecipe = RecipeEdit(recipe.productId, recipe.productName, line.materialId, line.materialName, line.baseUnit, line.qtyMb)
                            }) { Text(if (line.qtyMb == null) "Set" else "Edit", style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
            }
        }
    }
    editRecipe?.let { e ->
        var text by remember(e) { mutableStateOf(e.current?.let(::formatMilli).orEmpty()) }
        var problem by remember(e) { mutableStateOf<String?>(null) }
        EditNumberDialog(
            title = "${e.materialName} per packet",
            subtitle = e.productName,
            label = "Quantity (${e.baseUnit})",
            value = text,
            error = problem,
            onValue = {
                text = it
                problem = null
            },
            onSave = {
                when (val c = validateRecipeQuantity(text)) {
                    is RecipeCheck.Ok -> {
                        onRecipe(e.productId, e.materialId, c.qtyMb)
                        editRecipe = null
                    }
                    is RecipeCheck.Invalid -> problem = recipeProblemMessage(c.problem)
                }
            },
            onDismiss = { editRecipe = null },
        )
    }
    if (editWastage) {
        WastageDialog(current = ui.report?.wastageBp ?: 0, onSave = onWastage, onDismiss = { editWastage = false })
    }
}

@Composable
private fun ProductCostCard(cost: ProductCost) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(cost.productName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (cost is ProductCost.Incomplete) StatusChip(INCOMPLETE, kind = ChipKind.WARNING)
        }
        when (cost) {
            is ProductCost.Complete -> Column(Modifier.padding(top = Spacing.sm)) {
                cost.lines.forEach { LabelValueRow(it.materialName) { Text(formatTenThousandths(it.tt)) } }
                LabelValueRow("Wastage") { Text(formatTenThousandths(cost.wastageTt)) }
                LabelValueRow("Packing") { Text(formatTenThousandths(cost.packingTt)) }
                LabelValueRow("Direct cost", Modifier.padding(top = Spacing.xs)) {
                    Text(formatTenThousandths(cost.directTt), style = MaterialTheme.typography.titleSmall)
                }
                LabelValueRow("Indirect share") { Text(formatTenThousandths(cost.indirectTt)) }
                LabelValueRow("Full cost", Modifier.padding(top = Spacing.xs)) {
                    Text(formatTenThousandths(cost.fullTt), style = MaterialTheme.typography.titleMedium)
                }
            }
            is ProductCost.Incomplete -> Column(Modifier.padding(top = Spacing.sm)) {
                Text("Missing inputs:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cost.missing.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs)) }
            }
        }
    }
}

/** A dialog with one number field and an error line. */
@Composable
fun EditNumberDialog(
    title: String,
    label: String,
    value: String,
    error: String?,
    onValue: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                LabeledTextField(label = label, value = value, onValueChange = onValue, errorText = error)
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save", style = MaterialTheme.typography.titleMedium) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.titleMedium) } },
    )
}

/** Edit the wastage percent, 0 to 5 (owner spec 2). Used by Costing and Settings. */
@Composable
fun WastageDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(formatWastage(current)) }
    var problem by remember { mutableStateOf<String?>(null) }
    EditNumberDialog(
        title = "Wastage",
        label = "Percent (0 to 5)",
        value = text,
        error = problem,
        onValue = {
            text = it
            problem = null
        },
        onSave = {
            when (val c = validateWastage(text)) {
                is WastageCheck.Ok -> {
                    onSave(c.basisPoints)
                    onDismiss()
                }
                is WastageCheck.Invalid -> problem = wastageProblemMessage(c.problem)
            }
        },
        onDismiss = onDismiss,
    )
}
