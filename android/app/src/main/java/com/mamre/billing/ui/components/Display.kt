package com.mamre.billing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Spacing

enum class AmountSize { SMALL, MEDIUM, LARGE }

/** Money from Long cents, formatted with integer arithmetic (20, 24 or 28 sp bold). */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    size: AmountSize = AmountSize.MEDIUM,
    color: Color = Color.Unspecified,
) {
    val style: TextStyle = when (size) {
        AmountSize.SMALL -> MaterialTheme.typography.headlineSmall
        AmountSize.MEDIUM -> MaterialTheme.typography.headlineMedium
        AmountSize.LARGE -> MaterialTheme.typography.headlineLarge
    }
    Text(formatCents(cents), modifier = modifier, style = style, color = color)
}

enum class ChipKind { NEUTRAL, ACCENT, SUCCESS, WARNING, ERROR }

/** A small read-only label such as Credit, VOID or INCOMPLETE. Not clickable. */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, kind: ChipKind = ChipKind.NEUTRAL) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        ChipKind.NEUTRAL -> scheme.background to scheme.onSurfaceVariant
        ChipKind.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipKind.SUCCESS -> MamreTheme.extra.successContainer to MamreTheme.extra.success
        ChipKind.WARNING -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipKind.ERROR -> scheme.errorContainer to scheme.error
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        border = if (kind == ChipKind.NEUTRAL) BorderStroke(1.dp, scheme.outline) else null,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

/** The last question before something that cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
