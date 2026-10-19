package com.mamre.billing.ui.admin

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.data.export.EXPORT_WARNING
import com.mamre.billing.data.export.ExportShare
import com.mamre.billing.data.export.ExportState
import com.mamre.billing.data.export.ExportViewModel
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.theme.Spacing

// Admin > Settings > Export (Doc 2 s5.3, s6; Doc 1 A-27): writes the files to the cache folder and opens the Android share sheet.
// The Owner is told first that the files are plain text with customer data.

@Composable
fun ExportScreen(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shareProblem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        val ready = state as? ExportState.Ready ?: return@LaunchedEffect
        try {
            context.startActivity(Intent.createChooser(ExportShare.intent(context, ready.files), "Share the export"))
            shareProblem = null
        } catch (e: ActivityNotFoundException) {
            shareProblem = "No app on this phone can receive the files."
        }
        viewModel.shared()
    }
    ExportContent(state = state, shareProblem = shareProblem, onBack = onBack, onExport = { viewModel.export() })
}

@Composable
fun ExportContent(state: ExportState, shareProblem: String?, onBack: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Export", onBack = onBack, actions = { DataLabelChip() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Before you share", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(EXPORT_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(
                "One file for each list (customers, bills, payments, returns, purchases, expenses and the rest) and a manifest with the row counts. " +
                    "Money is in cents. The files stay on this phone until you choose where to send them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (state as? ExportState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            shareProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            PrimaryButton(
                if (state is ExportState.Working) "Making the files..." else "Export and share",
                onClick = onExport,
                enabled = state !is ExportState.Working,
            )
        }
    }
}
