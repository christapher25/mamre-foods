package com.mamre.billing.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mamre.billing.data.demo.DEMO_DATA_LABEL
import com.mamre.billing.domain.admin.AdminCustomer
import com.mamre.billing.domain.admin.dateToPickerMillis
import com.mamre.billing.domain.admin.pickerMillisToDate
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Sizes
import com.mamre.billing.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pieces shared by the Stage B admin screens. Display only: no formula lives here (Doc 2 s1.1).

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun formatDate(date: LocalDate): String = date.format(DATE_FORMAT)

/** Who made an Admin edit, for the change log, when the session has no name. */
const val DEFAULT_ADMIN_NAME = "Admin"

/** The "Demo data" chip every admin screen carries in its top bar (owner rule). */
@Composable
fun RowScope.DemoChip() {
    StatusChip(DEMO_DATA_LABEL, kind = ChipKind.ACCENT, modifier = Modifier.padding(end = Spacing.sm))
}

/** A date input that opens the Material 3 date picker. [errorText] shows under the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.let(::formatDate).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Choose a date") },
            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Choose a date") },
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.primaryButton),
        )
        // A read-only field does not receive taps, so a transparent layer on top opens the picker.
        Box(
            Modifier.matchParentSize().clickable(onClickLabel = "Choose $label", role = Role.Button) { open = true },
        )
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.let(::dateToPickerMillis))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(pickerMillisToDate(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** Pick one customer from a list, or "All customers" (null). */
@Composable
fun CustomerPickerDialog(
    customers: List<AdminCustomer>,
    onPick: (AdminCustomer?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Customer", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn {
                item { PickerRow("All customers") { onPick(null) } }
                items(customers, key = { it.id }) { PickerRow(it.name) { onPick(it) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.titleMedium) } },
    )
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** A bold label over its value, for read-only details. */
@Composable
fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(top = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
