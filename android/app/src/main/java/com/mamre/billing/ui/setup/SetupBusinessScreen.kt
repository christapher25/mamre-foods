package com.mamre.billing.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.ui.StartingScreen
import com.mamre.billing.ui.components.LabeledTextField
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing

/** Doc 1 s2.1 FR-2. Everything here can be changed later in Admin > Settings. */
@Composable
fun SetupBusinessScreen(onDone: () -> Unit, viewModel: SetupBusinessViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (ui.loading) {
        StartingScreen()
        return
    }
    val f = ui.form
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set up your business", style = MaterialTheme.typography.headlineSmall)
        Text("This prints on your bills. You can change it later in Admin, Settings.", style = MaterialTheme.typography.bodyLarge)
        LabeledTextField("Business name", f.businessName, { viewModel.edit(f.copy(businessName = it)) }, enabled = !ui.busy)
        LabeledTextField("Your name (printed as Salesman)", f.ownerName, { viewModel.edit(f.copy(ownerName = it)) }, enabled = !ui.busy)
        LabeledTextField("Address (up to 4 lines)", f.address, { viewModel.edit(f.copy(address = it)) }, singleLine = false, enabled = !ui.busy)
        LabeledTextField("Phone", f.phone, { viewModel.edit(f.copy(phone = it)) }, enabled = !ui.busy)
        LabeledTextField("Bill footer", f.footer, { viewModel.edit(f.copy(footer = it)) }, enabled = !ui.busy)
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        PrimaryButton("Save and continue", onClick = { viewModel.saveAndContinue(onDone) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
        SecondaryButton("Skip for now", onClick = onDone, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
    }
}
