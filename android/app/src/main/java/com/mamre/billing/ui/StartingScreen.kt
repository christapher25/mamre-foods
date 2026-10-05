package com.mamre.billing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mamre.billing.ui.components.MamreLogo
import com.mamre.billing.ui.theme.Spacing

/** Shown for a moment while the database opens (the first time ever it is created with its reference data). */
@Composable
fun StartingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MamreLogo(size = 96.dp)
        Text("Starting...", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
