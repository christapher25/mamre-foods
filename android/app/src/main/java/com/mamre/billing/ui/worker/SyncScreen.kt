package com.mamre.billing.ui.worker

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.repository.CatalogRepository
import com.mamre.billing.data.repository.SyncOutcome
import com.mamre.billing.data.repository.WorkerSync
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ConfirmDialog
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val session: SessionManager,
    private val store: DemoStore,
    val catalog: CatalogRepository,
    private val workerSync: WorkerSync,
) : ViewModel() {
    val state = store.state
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Pulls the catalog through the repository and cursor, then acknowledges records (fake until P2). */
    fun syncNow() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = when (val r = workerSync.syncNow()) {
                SyncOutcome.Done -> "Catalog is up to date."
                is SyncOutcome.Failed -> r.message
            }
            _busy.value = false
        }
    }

    fun logout() = session.logout()
}

/**
 * W10 Sync and settings (Doc 2 s10): pending count, Sync now, app version, Log out.
 * Logging out is blocked while records are unsynced (Doc 2 s6.6).
 */
@Composable
fun SyncScreen(onBack: () -> Unit, viewModel: SyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var catalogVersion by remember { mutableLongStateOf(0L) }
    var confirmLogout by remember { mutableStateOf(false) }
    val pending = state.pendingCount
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    LaunchedEffect(busy) { catalogVersion = viewModel.catalog.catalogCursor() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Sync and settings", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Text("Pending records", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pending.toString(), style = MaterialTheme.typography.headlineLarge)
                Text(
                    if (pending == 0) "Everything is synced (demo)." else "Waiting to sync (demo).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Always enabled: a sync also pulls new prices, even with nothing pending.
            PrimaryButton(if (busy) "Syncing..." else "Sync now", onClick = viewModel::syncNow, enabled = !busy)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            AppCard {
                Text("App version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("Catalog version: $catalogVersion", style = MaterialTheme.typography.bodyLarge)
            }
            if (pending > 0) {
                Text(
                    "Sync before logging out: $pending record${if (pending == 1) "" else "s"} not yet synced.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SecondaryButton("Log out", onClick = { confirmLogout = true }, enabled = pending == 0)
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            message = "You will need to sign in again.",
            confirmText = "Log out",
            onConfirm = {
                confirmLogout = false
                viewModel.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}
