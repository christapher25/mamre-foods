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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.theme.Spacing

/**
 * Doc 2 s10 Login: username and password. Navigation happens when the session gets a role:
 * the role comes from /me, never from the username, and decides the home screen.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "Mamre Foods",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Sign in to continue",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LabeledTextField(
            label = "Username",
            value = state.username,
            onValueChange = viewModel::onUsername,
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        LabeledTextField(
            label = "Password",
            value = state.password,
            onValueChange = viewModel::onPassword,
            enabled = !state.loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        PrimaryButton(
            text = if (state.loading) "Signing in..." else "Sign in",
            onClick = viewModel::signIn,
            enabled = state.canSubmit,
        )
    }
}
