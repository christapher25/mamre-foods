package com.mamre.billing.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamre.billing.domain.admin.NamedAmount
import com.mamre.billing.domain.admin.sharePercent
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.domain.money.formatCompactCents
import com.mamre.billing.ui.theme.Spacing

// Charts are drawn with Compose Canvas: no chart library is used (owner rule). All figures arrive
// as Long cents from the server; the only arithmetic here is turning them into bar lengths.

private val BAR_AREA_HEIGHT = 150.dp
private const val BAR_SLOT_FRACTION = 0.56f
private const val MIN_BAR_PX = 3f
private val TRACK_HEIGHT = 12.dp
private const val LABEL_TEXT_DP = 11f

/**
 * Vertical bars, one per month, with the compact amount above each bar and the month name below.
 * The bar of [highlight] is drawn in the brand colour; the others are lighter.
 */
@Composable
fun MonthlyBarChart(
    labels: List<String>,
    /** Null for a month before the data begins: its slot stays empty so the bars keep their width. */
    valuesCents: List<Long?>,
    highlight: Int,
    modifier: Modifier = Modifier,
) {
    val max = (valuesCents.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val light = primary.copy(alpha = 0.35f)
    val axis = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Chart annotations keep a fixed size so six labels always fit their slot, whatever the font scale.
    val labelStyle = TextStyle(fontSize = with(density) { LABEL_TEXT_DP.dp.toSp() }, color = ink)
    val summary = labels.zip(valuesCents).filter { it.second != null }.joinToString { (l, v) -> "$l ${formatCents(v!!)}" }

    Column(modifier.fillMaxWidth().semantics { contentDescription = "Net sales by month: $summary" }) {
        Canvas(Modifier.fillMaxWidth().height(BAR_AREA_HEIGHT)) {
            val n = valuesCents.size.coerceAtLeast(1)
            val slot = size.width / n
            val barWidth = slot * BAR_SLOT_FRACTION
            val labelRoom = with(density) { 20.dp.toPx() }
            val usable = size.height - labelRoom
            valuesCents.forEachIndexed { i, value ->
                val cents = value ?: return@forEachIndexed
                val h = (usable * cents.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceAtLeast(MIN_BAR_PX)
                val left = slot * i + (slot - barWidth) / 2f
                val top = size.height - h
                drawRoundRect(
                    color = if (i == highlight) primary else light,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                val text = measurer.measure(formatCompactCents(cents), labelStyle)
                val x = (slot * i + (slot - text.size.width) / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, (top - text.size.height - 2f).coerceAtLeast(0f)))
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (i == highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Horizontal bars: a label, its amount and share, and a bar on a light track. Bars are scaled to
 * the biggest row so differences stay visible.
 */
@Composable
fun HorizontalBarChart(rows: List<NamedAmount>, modifier: Modifier = Modifier) {
    val total = rows.sumOf { it.cents.coerceAtLeast(0L) }
    val max = (rows.maxOfOrNull { it.cents } ?: 0L).coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val summary = rows.joinToString { "${it.label} ${formatCents(it.cents)}" }
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = "Sales: $summary" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        rows.forEach { row ->
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${formatCents(row.cents)}  ${sharePercent(row.cents, total)}%",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Canvas(Modifier.fillMaxWidth().padding(top = Spacing.xs).height(TRACK_HEIGHT)) {
                    val radius = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(color = track, size = size, cornerRadius = radius)
                    val fraction = (row.cents.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = primary,
                            size = Size((size.width * fraction).coerceAtLeast(size.height), size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
    }
}
