package com.mamre.billing.ui.login

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
import com.mamre.billing.ui.setup.SetupBusinessScreen
import com.mamre.billing.ui.theme.Spacing

/** The login screen: the single gate in front of every other screen (B8 c, d; Doc 2 s6). */
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (ui.mode == LoginMode.BUSINESS) {
        SetupBusinessScreen(onDone = viewModel::businessDone)
        return
    }
    LoginContent(ui, viewModel::onDisplayName, viewModel::onUsername, viewModel::onPassword, viewModel::onConfirm, viewModel::submit)
}

@Composable
fun LoginContent(
    ui: LoginUi,
    onDisplayName: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    if (ui.mode == LoginMode.LOADING) {
        StartingScreen()
        return
    }
    val create = ui.mode == LoginMode.CREATE_ADMIN
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MamreLogo(size = 72.dp)
        Text(if (create) "Create the admin account" else "Log in", style = MaterialTheme.typography.headlineSmall)
        if (create) {
            Text("The admin sees everything. Choose a password of at least 6 characters and enter it twice.", style = MaterialTheme.typography.bodyLarge)
            Text(CREATE_ADMIN_NO_RECOVERY_TEXT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            LabeledTextField(label = "Your name (printed on bills)", value = ui.displayName, onValueChange = onDisplayName, enabled = !ui.busy)
        }
        LabeledTextField(label = "Username", value = ui.username, onValueChange = onUsername, enabled = !ui.busy)
        LabeledTextField(
            label = "Password",
            value = ui.password,
            onValueChange = onPassword,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            enabled = !ui.busy,
        )
        if (create) {
            LabeledTextField(
                label = "Password again",
                value = ui.confirm,
                onValueChange = onConfirm,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                enabled = !ui.busy,
            )
        }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        PrimaryButton(text = if (create) "Create account" else "Log in", onClick = onSubmit, enabled = ui.canSubmit)
    }
}
