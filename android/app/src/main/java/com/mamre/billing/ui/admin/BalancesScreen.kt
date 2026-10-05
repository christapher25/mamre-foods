package com.mamre.billing.ui.admin

import com.mamre.billing.ui.DataLabelChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.domain.admin.BalanceRow
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// B8 Balances: sorted by balance, with ageing. The server sorts and ages the money (Doc 1 s11); this only shows it.

data class BalancesUi(val loading: Boolean = true, val rows: List<BalanceRow> = emptyList())

@HiltViewModel
class BalancesViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(BalancesUi())
    val ui: StateFlow<BalancesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { api.revision.collect { _ui.update { BalancesUi(false, api.balances()) } } }
    }
}

@Composable
fun BalancesScreen(onBack: () -> Unit, onOpenCustomer: (String) -> Unit, viewModel: BalancesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BalancesContent(ui, onBack, onOpenCustomer)
}

@Composable
fun BalancesContent(ui: BalancesUi, onBack: () -> Unit, onOpenCustomer: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Balances", onBack = onBack, actions = { DataLabelChip() })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Text(
                    "Highest balance first. Ageing is by invoice age after payments are applied oldest first; an opening balance counts as 60+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.rows.isEmpty() && !ui.loading) item { EmptyState("No customers yet") }
            items(ui.rows, key = { it.customerId }) { r ->
                AppCard(onClick = { onOpenCustomer(r.customerId) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(end = Spacing.sm)) {
                            Text(r.customerName, style = MaterialTheme.typography.titleSmall)
                            Text(r.typeName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatCents(r.balanceCents), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.padding(top = Spacing.sm)) {
                        LabelValueRow("Current (under 30 days)") { Text(formatCents(r.currentCents)) }
                        LabelValueRow("30+ days") { Text(formatCents(r.over30Cents)) }
                        LabelValueRow("60+ days") { Text(formatCents(r.over60Cents)) }
                    }
                }
            }
        }
    }
}
