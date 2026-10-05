package com.mamre.billing.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing

/**
 * A label a build can ask the screens to show, for a database that holds sample data (the debug build). A release build
 * binds none, so no label is shown and no sample-data text exists in its code.
 */
interface DataLabel {
    val text: String
}

val LocalDataLabel = compositionLocalOf<DataLabel?> { null }

/** The chip in a top bar that says the data is sample data; nothing when the build has no label. */
@Composable
fun RowScope.DataLabelChip() {
    val label = LocalDataLabel.current ?: return
    StatusChip(label.text, kind = ChipKind.ACCENT, modifier = Modifier.padding(end = Spacing.sm))
}
