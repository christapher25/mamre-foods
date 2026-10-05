package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.theme.Spacing

/** One Home tile (Doc 2 s10, W1). */
enum class HomeTile(val title: String) {
    NEW_INVOICE("New invoice"),
    RECORD_PAYMENT("Record payment"),
    RETURN("Return"),
    TODAYS_INVOICES("Today's invoices"),
}

/** W1 Home: the Owner's name and device code on top, a large New invoice tile, a 2x2 grid with the switch to the Admin area. */
@Composable
fun HomeScreen(
    onTile: (HomeTile) -> Unit,
    onAdminArea: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            // The business name is a Settings value, never a string in the code (Doc 1 A-31).
            title = state.businessName.ifBlank { "Billing" },
            showLogo = true,
            subtitle = "${state.ownerName.ifBlank { "Owner" }} - device ${state.deviceCode}",
            actions = { DataLabelChip() },
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
            // Each row is as tall as its taller tile, so all four tiles are the same size.
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                GridTile("Record payment", Icons.Default.Payments, Modifier.weight(1f).fillMaxHeight()) {
                    onTile(HomeTile.RECORD_PAYMENT)
                }
                GridTile("Return", Icons.Default.Replay, Modifier.weight(1f).fillMaxHeight()) {
                    onTile(HomeTile.RETURN)
                }
            }
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                GridTile("Today's invoices", Icons.Default.Today, Modifier.weight(1f).fillMaxHeight()) {
                    onTile(HomeTile.TODAYS_INVOICES)
                }
                GridTile("Admin area", Icons.Default.AdminPanelSettings, Modifier.weight(1f).fillMaxHeight()) { onAdminArea() }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                Icons.Default.Receipt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
            Column(Modifier.weight(1f)) {
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
    }
}

@Composable
private fun GridTile(
    title: String,
    icon: ImageVector,
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.xs))
        // Every tile reserves the detail line so tiles with and without one match.
        Text(
            detail ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
