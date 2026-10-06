package com.mamre.billing.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.ui.StartingScreen
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.MamreLogo
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.theme.Spacing

/** The PIN screen: the single gate in front of every other screen (Doc 2 s6). */
@Composable
fun PinScreen(viewModel: PinViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    PinContent(ui, viewModel::onPin, viewModel::onConfirm, viewModel::submit)
}

@Composable
fun PinContent(ui: PinUi, onPin: (String) -> Unit, onConfirm: (String) -> Unit, onSubmit: () -> Unit) {
    if (ui.mode == PinMode.LOADING) {
        StartingScreen()
        return
    }
    val setup = ui.mode == PinMode.SETUP
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MamreLogo(size = 72.dp)
        Text(
            if (setup) "Choose a PIN" else "Enter your PIN",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (setup) {
            Text("A PIN is 4 to 6 digits. Enter it twice.", style = MaterialTheme.typography.bodyLarge)
            Text(SETUP_NO_RECOVERY_TEXT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Text(SETUP_WRITE_DOWN_TEXT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        val keys = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        LabeledTextField(
            label = if (setup) "New PIN" else "PIN",
            value = ui.pin,
            onValueChange = onPin,
            keyboardOptions = keys,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !ui.busy,
        )
        if (setup) {
            LabeledTextField(
                label = "New PIN again",
                value = ui.confirm,
                onValueChange = onConfirm,
                keyboardOptions = keys,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !ui.busy,
            )
        }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        PrimaryButton(text = if (setup) "Save PIN" else "Open", onClick = onSubmit, enabled = ui.canSubmit)
    }
}
