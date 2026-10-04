package com.mamre.billing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Sizes
import com.mamre.billing.ui.theme.Spacing

/** One choice of a group: at least 48 dp tall, filled and outlined in the brand colour when picked. */
@Composable
fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !enabled -> MamreTheme.extra.disabledContainer
        selected -> scheme.primaryContainer
        else -> scheme.surface
    }
    val content = when {
        !enabled -> MamreTheme.extra.onDisabled
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Surface(
        modifier = modifier
            .heightIn(min = Sizes.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(Sizes.cornerRadius),
        color = container,
        contentColor = content,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                .fillMaxWidth(),
        )
    }
}

/**
 * A single-choice group laid out in equal-width rows of [perRow], so nothing scrolls sideways
 * at a narrow width or a large font.
 */
@Composable
fun <T> OptionChips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    perRow: Int = 3,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.chunked(perRow).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                rowOptions.forEach { option ->
                    OptionChip(
                        text = label(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A bar pinned to the bottom of a screen for the running total and the main action. */
@Composable
fun StickyBottomBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

/** A label on the left and a value on the right, for summary cards. */
@Composable
fun LabelValueRow(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = Spacing.sm),
        )
        value()
    }
}
