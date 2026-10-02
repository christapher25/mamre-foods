package com.mamre.billing.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.mamre.billing.domain.admin.monthLabel
import com.mamre.billing.ui.theme.Sizes
import com.mamre.billing.ui.theme.Spacing
import java.time.YearMonth

/**
 * Pick a month with the arrows, limited to the months the data covers. The month in progress is
 * marked, because its figures are not final.
 */
@Composable
fun MonthSelector(
    month: YearMonth,
    first: YearMonth,
    last: YearMonth,
    onChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
) {
    AppCard(modifier = modifier, contentPadding = Spacing.xs) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { onChange(month.minusMonths(1)) },
                enabled = month.isAfter(first),
                modifier = Modifier.size(Sizes.touchTarget),
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(monthLabel(month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (inProgress) StatusChip("Month in progress", kind = ChipKind.ACCENT)
            }
            IconButton(
                onClick = { onChange(month.plusMonths(1)) },
                enabled = month.isBefore(last),
                modifier = Modifier.size(Sizes.touchTarget),
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
        }
    }
}
