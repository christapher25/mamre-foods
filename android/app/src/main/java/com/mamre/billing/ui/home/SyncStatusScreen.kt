package com.mamre.billing.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val session: SessionManager,
    val catalog: CatalogRepository,
) : ViewModel() {
    fun logout() = session.logout()
}

/** Doc 2 s10 "Sync and settings": pending count, app version, logout. Pending is 0 in P1. */
@Composable
fun SyncStatusScreen(onBack: () -> Unit, viewModel: SyncStatusViewModel = hiltViewModel()) {
    var catalogVersion by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { catalogVersion = viewModel.catalog.catalogCursor() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sync status", style = MaterialTheme.typography.headlineMedium)
        Text("Pending: $PENDING_RECORDS_P1", style = MaterialTheme.typography.displaySmall)
        Text("Catalog version: $catalogVersion", style = MaterialTheme.typography.bodyLarge)
        Text("App version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        // Logout is blocked while records are unsynced (Doc 2 s6.6); none exist in P1.
        Button(
            onClick = viewModel::logout,
            enabled = PENDING_RECORDS_P1 == 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Log out") }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Back") }
    }
}
