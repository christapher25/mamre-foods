package com.mamre.billing.ui.worker

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
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.ui.components.AmountSize
import com.mamre.billing.ui.components.AmountText
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TodayViewModel @Inject constructor(store: DemoStore) : ViewModel() {
    /** Invoices made today, newest first. Reads only; nothing here can edit or delete one. */
    val invoices: StateFlow<List<InvoiceRecord>> = store.state
        .map { demo ->
            val today = store.today()
            demo.invoices.filter { it.issuedAt.toLocalDate() == today }.sortedByDescending { it.issuedAt }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** W9 Today's invoices (Doc 2 s10): number, customer, total and time; a VOID badge; tap to reprint. */
@Composable
fun TodayScreen(onBack: () -> Unit, onOpen: (InvoiceRecord) -> Unit, viewModel: TodayViewModel = hiltViewModel()) {
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Today's invoices", subtitle = "${invoices.size} today", onBack = onBack)
        if (invoices.isEmpty()) {
            EmptyState(
                title = "No invoices today yet",
                message = "Invoices you confirm today appear here.",
                modifier = Modifier.padding(top = Spacing.xl),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(invoices, key = { it.id }) { InvoiceCard(it, onClick = { onOpen(it) }) }
            }
        }
    }
}

/** One row of Today's invoices. */
@Composable
fun InvoiceCard(invoice: InvoiceRecord, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(invoice.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (invoice.isVoid) StatusChip("VOID", kind = ChipKind.ERROR)
        }
        Text(invoice.customerName, style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                invoice.issuedAt.format(TIME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AmountText(invoice.totalCents, size = AmountSize.SMALL)
        }
    }
}
