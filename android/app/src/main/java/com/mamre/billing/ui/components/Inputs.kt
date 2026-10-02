package com.mamre.billing.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Sizes
import com.mamre.billing.ui.theme.Spacing

private const val MAX_DIGITS = 4
private const val MAX_PACKETS = 9999

/**
 * Whole packets (Doc 1 A-1) with minus and plus buttons of 48 dp and a field that also accepts
 * typing. An empty field means 0. Typing is limited to digits.
 */
@Composable
fun QuantityStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        StepperButton(enabled = enabled && value > 0, onClick = { onValueChange(value - 1) }) {
            Icon(Icons.Default.Remove, contentDescription = "Remove one packet")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it in '0'..'9' }.take(MAX_DIGITS)
                text = digits
                onValueChange(digits.toIntOrNull() ?: 0)
            },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("0", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp).heightIn(min = Sizes.touchTarget),
        )
        StepperButton(enabled = enabled && value < MAX_PACKETS, onClick = { onValueChange(value + 1) }) {
            Icon(Icons.Default.Add, contentDescription = "Add one packet")
        }
    }
}

@Composable
private fun StepperButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(Sizes.touchTarget),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MamreTheme.extra.disabledContainer,
            disabledContentColor = MamreTheme.extra.onDisabled,
        ),
    ) { content() }
}

/** A text field with a label above the input and error text underneath. */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.primaryButton),
    )
}
