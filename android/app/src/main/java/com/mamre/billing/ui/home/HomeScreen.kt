package com.mamre.billing.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One Home tile (Doc 2 s10). */
enum class HomeTile(val title: String) {
    NEW_INVOICE("New invoice"),
    RECORD_PAYMENT("Record payment"),
    RETURN_OR_DAMAGE("Return or damage"),
    TODAYS_INVOICES("Today's invoices"),
    SYNC_STATUS("Sync status"),
}

/** Shown on the "coming soon" tiles; tiles are placeholders in P1. */
const val COMING_SOON = "Coming soon"

/** Pending records on the device. Hard-coded for P1: there is no outbox yet (owner decision). */
const val PENDING_RECORDS_P1 = 0

@Composable
fun HomeScreen(
    onTile: (HomeTile) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mamre Foods", style = MaterialTheme.typography.headlineMedium)
        val who = listOfNotNull(state.workerName, state.deviceCode).joinToString(" - ")
        if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodyLarge)
        HomeTile.entries.forEach { tile ->
            val subtitle = if (tile == HomeTile.SYNC_STATUS) "Pending: $PENDING_RECORDS_P1" else COMING_SOON
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clickable { onTile(tile) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tile.title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
