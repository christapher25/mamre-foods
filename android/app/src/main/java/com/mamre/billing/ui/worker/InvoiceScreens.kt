package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.mamre.billing.domain.model.CustomerType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.domain.money.centsToPlain
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.worker.PriceEditProblem
import com.mamre.billing.domain.worker.PriceEditResult
import com.mamre.billing.domain.worker.priceEditMessage
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.domain.worker.PacketKey
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

/**
 * W2 Select customer (Doc 2 s10, change set C1): one tile per customer type with a count; a tile opens that
 * type's customers with search. A Walk-in sale button stays under Retail (walk-in follows the Retail rules).
 */
@Composable
fun CustomerScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onChosen: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var typeId by rememberSaveable { mutableStateOf<String?>(null) }
    val type = ui.types.firstOrNull { it.id == typeId }
    val goBack = {
        if (type != null) {
            typeId = null
            vm.onQuery("")
        } else {
            onBack()
        }
    }
    BackHandler(enabled = type != null) { goBack() }
    val walkIn: @Composable () -> Unit = {
        PrimaryButton("Walk-in sale", onClick = {
            vm.selectWalkIn()
            onChosen()
        })
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = type?.name ?: "Select customer", subtitle = if (type != null) "Select customer" else null, onBack = goBack)
        if (type == null) {
            TypeTiles(ui, onType = { typeId = it.id }, walkIn = walkIn)
            return@Column
        }
        CustomerList(
            rows = ui.customers.filter { it.customer.typeId == type.id },
            query = ui.query,
            onQuery = vm::onQuery,
            loading = ui.loading,
            onPick = {
                vm.selectCustomer(it.customer)
                onChosen()
            },
            header = if (type.name == RETAIL_TYPE_NAME) walkIn else null,
        )
    }
}

private const val RETAIL_TYPE_NAME = "Retail"

/** Four type tiles in two columns with the number of customers, then the Walk-in sale button. */
@Composable
private fun TypeTiles(ui: InvoiceUi, onType: (CustomerType) -> Unit, walkIn: @Composable () -> Unit) {
    val counts = ui.types.associate { t -> t.id to ui.customers.count { it.customer.typeId == t.id } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (!ui.loading && ui.types.isEmpty()) {
            Text("No customer types yet. Tap Sync now on the Sync screen.", style = MaterialTheme.typography.bodyLarge)
        }
        ui.types.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                pair.forEach { t ->
                    AppCard(onClick = { onType(t) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(t.name, style = MaterialTheme.typography.titleLarge)
                        val n = counts[t.id] ?: 0
                        Text(
                            "$n customer${if (n == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("Retail rules also apply to a walk-in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        walkIn()
    }
}

/** W3 Invoice builder: a card per product, prices read-only, running total pinned at the bottom. */
@Composable
fun BuilderScreen(vm: InvoiceFlowViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "New invoice", subtitle = "${ui.customerLabel} (${ui.typeName})", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(ui.products, key = { it.product.id }) { row ->
                PacketProductCard(
                    row = row,
                    onQty = vm::setQuantity,
                    onAddCustom = { size -> vm.addCustomPacket(row.product.id, size) },
                    onRemoveCustom = vm::removeCustomPacket,
                    priceEditAllowed = ui.priceEditAllowed,
                    onEditPrice = vm::editPrice,
                    onResetPrice = vm::resetPrice,
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
 * One product on the builder (change set C2): the Standard packet row, any Custom packet rows (1 to 200
 * chapathis each, priced from the standard price) and an "Add custom packet" button. A product with no price
 * is disabled and says so; it is never priced at zero (Doc 1 s4.2).
 */
@Composable
fun PacketProductCard(
    row: ProductRow,
    onQty: (PacketKey, Int) -> Unit,
    onAddCustom: (Int) -> CustomPacketResult,
    onRemoveCustom: (PacketKey) -> Unit,
    modifier: Modifier = Modifier,
    priceEditAllowed: Boolean = false,
    onEditPrice: (PacketKey, String) -> PriceEditResult = { _, _ -> PriceEditResult.Rejected(PriceEditProblem.NOT_ALLOWED) },
    onResetPrice: (PacketKey) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PacketRow?>(null) }
    val unit = row.unitPriceCents
    AppCard(
        modifier = modifier,
        containerColor = if (unit != null) MaterialTheme.colorScheme.surface else MamreTheme.extra.disabledContainer,
    ) {
        Text(row.product.name, style = MaterialTheme.typography.titleMedium)
        if (unit == null) {
            Text(NO_PRICE_MESSAGE, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            return@AppCard
        }
        row.packets.forEachIndexed { i, p ->
            if (i > 0) Column(Modifier.padding(vertical = Spacing.sm)) { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }
            Text(
                if (p.isStandard) "Standard (${p.key.chapathisPerPacket})" else "Custom: ${p.key.chapathisPerPacket} pcs",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Text(
                "${formatCents(p.chargedCents)} per packet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (p.priceChanged) {
                Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip("Price changed", kind = ChipKind.ACCENT)
                    Text("List ${formatCents(p.listPriceCents)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            QuantityStepper(value = p.qty, onValueChange = { onQty(p.key, it) }, modifier = Modifier.padding(top = Spacing.sm))
            if (p.qty > 0) {
                Text(
                    "${p.qty} x ${formatCents(p.chargedCents)} = ${formatCents(p.qty * p.chargedCents)} (${p.qty * p.key.chapathisPerPacket} pcs)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            if (priceEditAllowed) {
                SecondaryButton("Edit price", onClick = { editing = p }, modifier = Modifier.padding(top = Spacing.sm))
            }
            if (!p.isStandard) {
                SecondaryButton("Remove this size", onClick = { onRemoveCustom(p.key) }, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
        SecondaryButton("Add custom packet", onClick = { adding = true }, modifier = Modifier.padding(top = Spacing.md))
    }
    editing?.let { p ->
        PriceEditSheet(
            productName = row.product.name,
            packet = p,
            onSave = { text -> onEditPrice(p.key, text) },
            onReset = { onResetPrice(p.key) },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        CustomPacketDialog(
            productName = row.product.name,
            onAdd = onAddCustom,
            onDismiss = { adding = false },
        )
    }
}

/**
 * The small sheet for changing a price (change set C3): the list price, a field for the price per packet, a
 * Reset button and the reason when the price is refused. Only reachable for customer types whose flag allows it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PriceEditSheet(
    productName: String,
    packet: PacketRow,
    onSave: (String) -> PriceEditResult,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(centsToPlain(packet.chargedCents)) }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Edit price", style = MaterialTheme.typography.titleLarge)
            Text(
                if (packet.isStandard) productName else "$productName (${packet.key.chapathisPerPacket} pcs)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("List price: ${formatCents(packet.listPriceCents)} per packet", style = MaterialTheme.typography.bodyMedium)
            LabeledTextField(
                label = "Price per packet",
                value = text,
                onValueChange = {
                    text = it
                    problem = null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = problem,
            )
            PrimaryButton("Save price", onClick = {
                when (val r = onSave(text)) {
                    is PriceEditResult.Ok -> onDismiss()
                    is PriceEditResult.Rejected -> problem = priceEditMessage(r.problem, packet.listPriceCents)
                }
            })
            SecondaryButton("Reset to list price", onClick = {
                onReset()
                onDismiss()
            })
        }
    }
}

/** Asks for the chapathis in a custom packet (1 to 200) and says why a size is refused. */
@Composable
private fun CustomPacketDialog(productName: String, onAdd: (Int) -> CustomPacketResult, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Custom packet", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(productName, style = MaterialTheme.typography.bodyMedium)
                LabeledTextField(
                    label = "Chapathis (1 to 200)",
                    value = text,
                    onValueChange = {
                        text = it.filter(Char::isDigit).take(3)
                        problem = null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    errorText = problem,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                when (onAdd(text.toIntOrNull() ?: 0)) {
                    CustomPacketResult.ADDED -> onDismiss()
                    CustomPacketResult.INVALID_SIZE -> problem = "Enter a number from 1 to 200"
                    CustomPacketResult.ALREADY_THERE -> problem = "That packet size is already on this card"
                }
            }) { Text("Add", style = MaterialTheme.typography.titleMedium) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
        },
    )
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
        AppTopBar(title = "Payment", subtitle = "${ui.customerLabel} (${ui.typeName})", onBack = onBack)
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
                Text(ui.customerLabel, style = MaterialTheme.typography.titleMedium)
                Text(ui.typeName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppCard {
                ui.lines.forEachIndexed { i, line ->
                    if (i > 0) Column(Modifier.padding(vertical = Spacing.sm)) { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }
                    Text(
                        if (line.isCustomPacket) "${line.productName} (${line.chapathisPerPacket} pcs)" else line.productName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LabelValueRow("${line.qtyPackets} x ${formatCents(line.unitPriceCents)}") {
                        AmountText(line.lineTotalCents, size = AmountSize.SMALL)
                    }
                    if (line.priceOverridden) {
                        Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip("Price changed", kind = ChipKind.ACCENT)
                            Text("List ${formatCents(line.listPriceCents)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
