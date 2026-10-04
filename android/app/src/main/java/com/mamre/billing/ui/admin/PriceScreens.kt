package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.domain.admin.PriceCheck
import com.mamre.billing.domain.admin.PriceMatrix
import com.mamre.billing.domain.admin.PriceProblem
import com.mamre.billing.domain.admin.priceProblemMessage
import com.mamre.billing.domain.admin.validateNewPrice
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B4 Prices (Doc 1 s4.3; Doc 2 s9). A price is added with a later effective-from date and never rewritten.
// The shared price table (DECISIONS 2026-10-03) carries the change to the worker's next sync.

const val SALESMEN_SYNC_NOTE = "Salesmen receive this at their next sync."

data class PricesUi(
    val loading: Boolean = true,
    val matrix: PriceMatrix? = null,
    val today: LocalDate? = null,
    val error: String? = null,
)

@HiltViewModel
class PricesViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(PricesUi())
    val ui: StateFlow<PricesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect { _ui.update { it.copy(loading = false, matrix = api.priceMatrix(), today = api.today) } }
        }
    }

    /** Switches "Salesman can edit price" for a customer type; the change is logged and synced to salesmen. */
    fun setWorkerCanEdit(typeId: String, allowed: Boolean) {
        viewModelScope.launch {
            try {
                api.setWorkerCanEditPrice(typeId, allowed, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun PricesScreen(onSetPrice: (String, String) -> Unit, viewModel: PricesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    PricesContent(ui, onSetPrice, viewModel::setWorkerCanEdit)
}

@Composable
fun PricesContent(ui: PricesUi, onSetPrice: (String, String) -> Unit, onWorkerCanEdit: (String, Boolean) -> Unit = { _, _ -> }) {
    val matrix = ui.matrix
    val today = ui.today
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Prices", actions = { DemoChip() })
        if (matrix == null || today == null) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "Price per packet by product and customer type. $SALESMEN_SYNC_NOTE",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader("Price rules")
            AppCard {
                Text(
                    "Salesman can edit price: when on, a salesman may change a line's price for customers of this type. When off the price is read-only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                matrix.types.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                            Text(type.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (type.workerCanEditPrice) "Salesman can edit price" else "Price is read-only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = type.workerCanEditPrice,
                            onCheckedChange = { onWorkerCanEdit(type.id, it) },
                            modifier = Modifier.semantics { contentDescription = "Salesman can edit price for ${type.name}" },
                        )
                    }
                }
                Text(
                    SALESMEN_SYNC_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md),
                )
                ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            SectionHeader("Default prices")
            matrix.products.forEach { product ->
                AppCard {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    matrix.types.forEach { type ->
                        val current = matrix.current(product.id, type.id, today)
                        val next = matrix.history(product.id, type.id).firstOrNull { it.effectiveFrom.isAfter(today) }
                        Column(Modifier.padding(top = Spacing.md)) {
                            LabelValueRow(type.name) {
                                Text(
                                    current?.let { formatCents(it.unitPriceCents) } ?: "No price",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            current?.let {
                                Text(
                                    "Since ${formatDate(it.effectiveFrom)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (next != null) {
                                StatusChip(
                                    "${formatCents(next.unitPriceCents)} from ${formatDate(next.effectiveFrom)}",
                                    kind = ChipKind.ACCENT,
                                    modifier = Modifier.padding(top = Spacing.xs),
                                )
                            }
                            SecondaryButton(
                                "Set new price",
                                onClick = { onSetPrice(product.id, type.id) },
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- set new price ---

data class PriceSetUi(
    val loading: Boolean = true,
    val productName: String = "",
    val typeName: String = "",
    val history: List<com.mamre.billing.domain.admin.PriceEntry> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class PriceSetViewModel @Inject constructor(
    private val api: AdminApi,
    private val session: SessionManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val productId: String = checkNotNull(savedState[AdminRoutes.ARG_PRODUCT])
    private val typeId: String = checkNotNull(savedState[AdminRoutes.ARG_TYPE])
    private val _ui = MutableStateFlow(PriceSetUi())
    val ui: StateFlow<PriceSetUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val m = api.priceMatrix()
            _ui.update {
                it.copy(
                    loading = false,
                    productName = m.products.firstOrNull { p -> p.id == productId }?.name.orEmpty(),
                    typeName = m.types.firstOrNull { t -> t.id == typeId }?.name.orEmpty(),
                    history = m.history(productId, typeId),
                )
            }
        }
    }

    fun save(priceCents: Long, from: LocalDate) {
        viewModelScope.launch {
            try {
                api.setDefaultPrice(productId, typeId, priceCents, from, session.profile?.fullName ?: DEFAULT_ADMIN_NAME)
                _ui.update { it.copy(error = null, saved = true) }
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }
}

@Composable
fun PriceSetScreen(onBack: () -> Unit, viewModel: PriceSetViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    PriceSetContent(ui, onBack, viewModel::save)
}

@Composable
fun PriceSetContent(ui: PriceSetUi, onBack: () -> Unit, onSave: (Long, LocalDate) -> Unit) {
    var price by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var tried by remember { mutableStateOf(false) }
    val latest = ui.history.firstOrNull()?.effectiveFrom
    val check = validateNewPrice(price, date, latest)
    val problems = (check as? PriceCheck.Invalid)?.problems.orEmpty()
    fun messages(vararg of: PriceProblem) =
        if (tried) problems.filter { it in of }.joinToString { priceProblemMessage(it, latest) }.ifEmpty { null } else null

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Set new price", onBack = onBack, actions = { DemoChip() })
        if (ui.loading) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("${ui.productName} - ${ui.typeName}", style = MaterialTheme.typography.titleMedium)
            LabeledTextField(
                "New price per packet", price, { price = it },
                errorText = messages(PriceProblem.PRICE_REQUIRED, PriceProblem.PRICE_NOT_POSITIVE),
                placeholder = "2.95",
            )
            DateField(
                "Effective from", date, { date = it },
                errorText = messages(PriceProblem.DATE_REQUIRED, PriceProblem.DATE_NOT_LATER),
            )
            Text(SALESMEN_SYNC_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save price", onClick = {
                tried = true
                if (check is PriceCheck.Ok) onSave(check.priceCents, check.effectiveFrom)
            })
            SectionHeader("Price history")
            AppCard {
                if (ui.history.isEmpty()) EmptyState("No price yet")
                ui.history.forEach {
                    LabelValueRow("From ${formatDate(it.effectiveFrom)}") {
                        Text(formatCents(it.unitPriceCents), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
