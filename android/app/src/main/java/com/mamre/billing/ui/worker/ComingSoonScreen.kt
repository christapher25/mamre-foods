package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing

/** Placeholder for a worker screen that is built in a later step. */
@Composable
fun ComingSoonScreen(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = title, onBack = onBack)
        EmptyState(
            title = "Coming soon",
            message = "This screen is not built yet.",
            modifier = Modifier.padding(top = Spacing.xl),
        )
        Column(Modifier.padding(Spacing.lg)) { SecondaryButton("Back", onClick = onBack) }
    }
}
