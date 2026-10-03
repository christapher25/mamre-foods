package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.domain.model.label
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Spacing

/**
 * Search field, an optional [header] (such as the Walk-in button) and the customer cards.
 * Used by Select customer, Record payment and Return (Doc 2 s10).
 */
@Composable
fun CustomerList(
    rows: List<CustomerRow>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (CustomerRow) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            header?.invoke()
            LabeledTextField(
                label = "Search customers",
                value = query,
                onValueChange = onQuery,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
        }
        when {
            loading -> Unit
            rows.isEmpty() -> EmptyState(
                title = "No customers found",
                message = if (query.isBlank()) "Customers are added by the admin." else "Try another name.",
                modifier = Modifier.padding(top = Spacing.xl),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(rows, key = { it.customer.id }) { row -> CustomerCard(row, onClick = { onPick(row) }) }
            }
        }
    }
}

/** Name and location, type chip, Cash or Credit chip, and Balance due for a credit customer, never a corporate one (Doc 2 s10, D4). */
@Composable
fun CustomerCard(row: CustomerRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val customer = row.customer
    val credit = customer.paymentMode == PaymentMode.CREDIT
    AppCard(onClick = onClick, modifier = modifier, contentPadding = Spacing.lg) {
        Text(customer.label, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatusChip(row.typeName)
            StatusChip(if (credit) "Credit" else "Cash", kind = if (credit) ChipKind.ACCENT else ChipKind.NEUTRAL)
        }
        if (row.showsBalance) {
            val cents = row.balanceCents
            Text(
                text = if (cents < 0) "Credit on account ${formatCents(-cents)}" else "Balance due ${formatCents(cents)}",
                style = MaterialTheme.typography.titleSmall,
                color = if (cents < 0) MamreTheme.extra.success else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}
