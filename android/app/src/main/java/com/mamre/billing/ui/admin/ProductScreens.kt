package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.domain.admin.AdminProduct
import com.mamre.billing.domain.admin.DEFAULT_YIELD_PER_KG
import com.mamre.billing.domain.admin.ExistingProduct
import com.mamre.billing.domain.admin.ProductForm
import com.mamre.billing.domain.admin.productProblemMessage
import com.mamre.billing.domain.admin.validateProductForm
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Admin > More > Products (B5, Doc 1 s3): the list, Add product and Edit product. A new product has no price until the Admin sets
// one on the Prices screen; nothing here shows a cost.

/** The standard packet size a new product starts with. */
const val DEFAULT_PACKET_SIZE = 12

data class ProductListUi(val loading: Boolean = true, val products: List<AdminProduct> = emptyList())

@HiltViewModel
class ProductListViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(ProductListUi())
    val ui: StateFlow<ProductListUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            api.revision.collect { _ui.update { it.copy(loading = false, products = api.products().sortedBy { p -> p.name.lowercase() }) } }
        }
    }
}

@Composable
fun ProductListScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit, viewModel: ProductListViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ProductListContent(ui, onBack, onAdd, onOpen)
}

@Composable
fun ProductListContent(ui: ProductListUi, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Products", onBack = onBack, actions = { DataLabelChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PrimaryButton("Add product", onClick = onAdd, modifier = Modifier.fillMaxWidth())
            ui.products.forEach { p ->
                AppCard(onClick = { onOpen(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(if (p.isActive) "Active" else "Inactive", kind = if (p.isActive) ChipKind.SUCCESS else ChipKind.ERROR)
                    }
                    Text("Code ${p.code}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${p.unitsPerPacket} chapathis a packet, ${p.yieldPerKg} per kg of wheat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** What the Add and Edit screens share: the fields as typed, the problems to show, and the save result. */
data class ProductFormUi(
    val loading: Boolean = true,
    val name: String = "",
    val code: String = "",
    val packet: String = DEFAULT_PACKET_SIZE.toString(),
    val yieldPerKg: String = DEFAULT_YIELD_PER_KG.toString(),
    val active: Boolean = true,
    /** Set once Save was tried: the rule messages then show under the fields. */
    val tried: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val title: String = "Add product",
)

@HiltViewModel
class ProductFormViewModel @Inject constructor(
    private val api: AdminApi,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** Null on the Add screen; the product being edited otherwise. */
    private val productId: String? = savedState[AdminRoutes.ARG_ID]
    private var existing: List<AdminProduct> = emptyList()
    private var original: AdminProduct? = null
    private val _ui = MutableStateFlow(ProductFormUi(title = if (productId == null) "Add product" else "Edit product"))
    val ui: StateFlow<ProductFormUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            existing = api.products()
            val p = productId?.let { id -> existing.firstOrNull { it.id == id } }
            original = p
            _ui.update {
                if (p == null) it.copy(loading = false)
                else it.copy(
                    loading = false, name = p.name, code = p.code, packet = p.unitsPerPacket.toString(),
                    yieldPerKg = p.yieldPerKg.toString(), active = p.isActive,
                )
            }
        }
    }

    fun onName(v: String) = _ui.update { it.copy(name = v, error = null) }

    fun onCode(v: String) = _ui.update { it.copy(code = v, error = null) }

    fun onPacket(v: String) = _ui.update { it.copy(packet = v.filter(Char::isDigit).take(3), error = null) }

    fun onYield(v: String) = _ui.update { it.copy(yieldPerKg = v.filter(Char::isDigit).take(3), error = null) }

    fun onActive(v: Boolean) = _ui.update { it.copy(active = v, error = null) }

    /** The form as typed (a packet size or yield that is not a number counts as 0, which the rules refuse). */
    private fun form(u: ProductFormUi) = ProductForm(u.name, u.code, u.packet.toIntOrNull() ?: 0, u.yieldPerKg.toIntOrNull() ?: 0, u.active)

    /** The rule problems of what is typed now, as sentences, in the order of the fields. */
    fun problems(u: ProductFormUi): List<String> =
        validateProductForm(form(u), existing.map { ExistingProduct(it.id, it.name, it.code) }, productId).map(::productProblemMessage)

    fun save() {
        val u = _ui.value
        _ui.update { it.copy(tried = true, error = null) }
        if (problems(u).isNotEmpty()) return
        viewModelScope.launch {
            try {
                val f = form(u)
                if (productId == null) {
                    api.addProduct(f)
                } else {
                    val old = original
                    // The three product settings keep their own use cases; each writes its own change log entry.
                    if (old != null) {
                        if (nameCodeActiveChanged(old, u)) api.editProduct(productId, u.name, u.code, u.active)
                        if (old.unitsPerPacket != f.packetSize) api.setStandardPacketSize(productId, f.packetSize)
                        if (old.yieldPerKg != f.yieldPerKg) api.setYieldPerKg(productId, f.yieldPerKg)
                    }
                }
                _ui.update { it.copy(saved = true) }
            } catch (e: RuleException) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    private fun nameCodeActiveChanged(old: AdminProduct, u: ProductFormUi) =
        old.name != com.mamre.billing.domain.model.normalizeSpaces(u.name) ||
            old.code != com.mamre.billing.domain.admin.cleanProductCode(u.code) ||
            old.isActive != u.active
}

@Composable
fun ProductFormScreen(onBack: () -> Unit, viewModel: ProductFormViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    ProductFormContent(
        ui = ui,
        problems = if (ui.tried) viewModel.problems(ui) else emptyList(),
        onBack = onBack,
        onName = viewModel::onName,
        onCode = viewModel::onCode,
        onPacket = viewModel::onPacket,
        onYield = viewModel::onYield,
        onActive = viewModel::onActive,
        onSave = viewModel::save,
    )
}

@Composable
fun ProductFormContent(
    ui: ProductFormUi,
    problems: List<String>,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onCode: (String) -> Unit,
    onPacket: (String) -> Unit,
    onYield: (String) -> Unit,
    onActive: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = ui.title, onBack = onBack, actions = { DataLabelChip() })
        if (ui.loading) return@Column
        val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LabeledTextField("Name", ui.name, onName)
            LabeledTextField("Code (letters or digits)", ui.code, onCode)
            LabeledTextField("Chapathis in a standard packet", ui.packet, onPacket, keyboardOptions = numbers)
            LabeledTextField("Chapathis from 1 kg of wheat", ui.yieldPerKg, onYield, keyboardOptions = numbers)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Active", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = ui.active, onCheckedChange = onActive)
            }
            Text(
                "A new product has no price yet. Set it on the Prices screen; until then it cannot be sold. A product is never deleted: switch it off to stop offering it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save", onClick = onSave, modifier = Modifier.fillMaxWidth())
        }
    }
}
