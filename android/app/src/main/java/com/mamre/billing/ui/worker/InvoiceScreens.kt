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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.PayerKind
import com.mamre.billing.domain.worker.PaymentCheck
import com.mamre.billing.domain.worker.PaymentMethod
import com.mamre.billing.domain.worker.paymentMessage
import com.mamre.billing.ui.components.AmountSize
import com.mamre.billing.ui.components.AmountText
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.LabelValueRow
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.OptionChips
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StickyBottomBar
import com.mamre.billing.ui.components.QuantityStepper
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Spacing

/** The message a worker reads for a product that cannot be sold (Doc 1 s4.2). */
const val NO_PRICE_MESSAGE = "No price set - contact admin"

/** W2 Select customer (Doc 2 s10): search, customer cards, and a prominent Walk-in sale button. */
@Composable
fun CustomerScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onChosen: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Select customer", onBack = onBack)
        CustomerList(
            rows = ui.customers,
            query = ui.query,
            onQuery = vm::onQuery,
            loading = ui.loading,
            onPick = {
                vm.selectCustomer(it.customer)
                onChosen()
            },
            header = {
                PrimaryButton("Walk-in sale", onClick = {
                    vm.selectWalkIn()
                    onChosen()
                })
            },
        )
    }
}

/** W3 Invoice builder: a card per product, prices read-only, running total pinned at the bottom. */
@Composable
fun BuilderScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "New invoice", subtitle = "${ui.customerName} - ${ui.typeName}", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(ui.products, key = { it.product.id }) { row ->
                ProductCard(
                    name = row.product.name,
                    unitPriceCents = row.unitPriceCents,
                    qty = ui.quantities[row.product.id] ?: 0,
                    onQty = { vm.setQuantity(row.product.id, it) },
                )
            }
        }
        StickyBottomBar {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Total", style = MaterialTheme.typography.titleMedium)
                AmountText(ui.totalCents, size = AmountSize.LARGE)
            }
            PrimaryButton("Continue", onClick = onContinue, enabled = ui.canContinue)
        }
    }
}

/**
 * One product: its name, the price from the price list (read-only, never editable) and the packet
 * stepper. With no price the card is disabled and says so; it is never priced at zero (Doc 1 s4.2).
 */
@Composable
fun ProductCard(name: String, unitPriceCents: Long?, qty: Int, onQty: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sellable = unitPriceCents != null
    AppCard(
        modifier = modifier,
        containerColor = if (sellable) MaterialTheme.colorScheme.surface else MamreTheme.extra.disabledContainer,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        if (unitPriceCents != null) {
            Text(
                "${formatCents(unitPriceCents)} per packet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(NO_PRICE_MESSAGE, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        }
        QuantityStepper(
            value = if (sellable) qty else 0,
            onValueChange = onQty,
            enabled = sellable,
            modifier = Modifier.padding(top = Spacing.md),
        )
        if (unitPriceCents != null && qty > 0) {
            Text(
                "$qty x ${formatCents(unitPriceCents)} = ${formatCents(qty * unitPriceCents)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** W4 Payment (Doc 1 s5.1 step 3, s6.1): amount received now, method, and the balance after. */
@Composable
fun PaymentScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val credit = ui.payerKind == PayerKind.CREDIT_CUSTOMER
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Payment", subtitle = "${ui.customerName} - ${ui.typeName}", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                LabelValueRow("Invoice total") { AmountText(ui.totalCents, size = AmountSize.MEDIUM) }
                if (credit) {
                    LabelValueRow("Previous balance", Modifier.padding(top = Spacing.sm)) {
                        AmountText(ui.previousBalanceCents, size = AmountSize.SMALL)
                    }
                }
            }
            val rejected = ui.check as? PaymentCheck.Rejected
            LabeledTextField(
                label = "Amount received now",
                value = ui.amountText,
                onValueChange = vm::onAmount,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = rejected?.takeIf { ui.amountText.isNotBlank() }
                    ?.let { paymentMessage(it.problem, ui.payerKind, ui.totalCents) },
                placeholder = if (credit) "Type an amount, or tap Full or None" else null,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SecondaryButton("Full", onClick = vm::fullAmount, modifier = Modifier.weight(1f))
                if (credit) SecondaryButton("None", onClick = vm::noAmount, modifier = Modifier.weight(1f))
            }
            if (!credit) {
                Text(
                    if (ui.payerKind == PayerKind.WALK_IN) "Walk-in sales must be paid in full."
                    else "Cash customers pay in full.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader("Payment method")
            OptionChips(PaymentMethod.entries, ui.method, { it.label }, vm::onMethod)
            BalanceAfter(ui.check, ui.payerKind)
        }
        StickyBottomBar {
            PrimaryButton("Continue", onClick = onContinue, enabled = ui.check is PaymentCheck.Ok)
        }
    }
}

/** "Balance after" for a saved customer; an overpayment reads "Credit on account" (Doc 1 A-4). */
@Composable
private fun BalanceAfter(check: PaymentCheck, kind: PayerKind) {
    val ok = check as? PaymentCheck.Ok ?: return
    if (kind == PayerKind.WALK_IN) return
    AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        if (ok.creditOnAccountCents > 0) {
            LabelValueRow("Credit on account") {
                AmountText(ok.creditOnAccountCents, size = AmountSize.MEDIUM, color = MamreTheme.extra.success)
            }
        } else {
            LabelValueRow("Balance after") { AmountText(ok.balanceAfterCents, size = AmountSize.MEDIUM) }
        }
    }
}

/** W5 Confirm: the summary, then a ConfirmDialog; confirming creates the immutable invoice. */
@Composable
fun ConfirmScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onConfirmed: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    val ok = ui.check as? PaymentCheck.Ok
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Confirm invoice", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Text(ui.customerName, style = MaterialTheme.typography.titleMedium)
                Text(ui.typeName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppCard {
                ui.lines.forEachIndexed { i, line ->
                    if (i > 0) Column(Modifier.padding(vertical = Spacing.sm)) { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }
                    Text(line.productName, style = MaterialTheme.typography.titleSmall)
                    LabelValueRow("${line.qtyPackets} x ${formatCents(line.unitPriceCents)}") {
                        AmountText(line.lineTotalCents, size = AmountSize.SMALL)
                    }
                }
            }
            AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                LabelValueRow("Total") { AmountText(ui.totalCents, size = AmountSize.LARGE) }
                if (ok != null) {
                    val paid = if (ok.amountCents > 0) "Paid now (${ui.method.label})" else "Paid now"
                    LabelValueRow(paid, Modifier.padding(top = Spacing.sm)) { AmountText(ok.amountCents, size = AmountSize.SMALL) }
                    if (ui.payerKind != PayerKind.WALK_IN) {
                        if (ok.creditOnAccountCents > 0) {
                            LabelValueRow("Credit on account", Modifier.padding(top = Spacing.sm)) {
                                AmountText(ok.creditOnAccountCents, size = AmountSize.SMALL, color = MamreTheme.extra.success)
                            }
                        } else {
                            LabelValueRow("Balance after", Modifier.padding(top = Spacing.sm)) {
                                AmountText(ok.balanceAfterCents, size = AmountSize.SMALL)
                            }
                        }
                    }
                }
            }
            Text(
                "The invoice number is given when you confirm. A confirmed invoice cannot be edited.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.deviceCode == null) {
                Text(
                    "This device has no device code. Sign in again while online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        StickyBottomBar {
            PrimaryButton("Confirm invoice", onClick = { asking = true }, enabled = ui.canConfirm)
        }
    }
    if (asking) {
        ConfirmDialog(
            title = "Confirm invoice?",
            message = "Create the invoice for ${formatCents(ui.totalCents)}. It cannot be edited afterwards.",
            confirmText = "Confirm",
            onConfirm = {
                asking = false
                vm.confirm()?.let { onConfirmed(it.id) }
            },
            onDismiss = { asking = false },
        )
    }
}
