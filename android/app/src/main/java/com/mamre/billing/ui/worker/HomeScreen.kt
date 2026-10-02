package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.data.demo.DEMO_DATA_LABEL
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing

/** One Home tile (Doc 2 s10, W1). */
enum class HomeTile(val title: String) {
    NEW_INVOICE("New invoice"),
    RECORD_PAYMENT("Record payment"),
    RETURN("Return"),
    TODAYS_INVOICES("Today's invoices"),
    SYNC_STATUS("Sync status"),
}

/** W1 Home: the worker's name and device code on top, a large New invoice tile, a 2x2 grid. */
@Composable
fun HomeScreen(
    onTile: (HomeTile) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = state.workerName ?: "Mamre Foods",
            subtitle = state.deviceCode?.let { "Device $it" },
            actions = {
                StatusChip(DEMO_DATA_LABEL, kind = ChipKind.ACCENT, modifier = Modifier.padding(end = Spacing.sm))
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            NewInvoiceTile(onClick = { onTile(HomeTile.NEW_INVOICE) })
            Text(
                "Invoices today: ${state.invoicesToday}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = Spacing.xs),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                GridTile("Record payment", Modifier.weight(1f)) { onTile(HomeTile.RECORD_PAYMENT) }
                GridTile("Return", Modifier.weight(1f)) { onTile(HomeTile.RETURN) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                GridTile("Today's invoices", Modifier.weight(1f)) { onTile(HomeTile.TODAYS_INVOICES) }
                GridTile(
                    "Sync status",
                    Modifier.weight(1f),
                    detail = "Pending: ${state.pendingCount}",
                ) { onTile(HomeTile.SYNC_STATUS) }
            }
        }
    }
}

@Composable
private fun NewInvoiceTile(onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        minContentHeight = 120.dp,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "New invoice",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            "Choose a customer and add packets",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun GridTile(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: () -> Unit,
) {
    AppCard(
        onClick = onClick,
        modifier = modifier,
        minContentHeight = 96.dp,
        contentPadding = Spacing.md,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}
