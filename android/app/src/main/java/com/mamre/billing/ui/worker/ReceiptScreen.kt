package com.mamre.billing.ui.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamre.billing.print.RECEIPT_WIDTH
import com.mamre.billing.print.SENT_TO_MOCK_PRINTER
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.components.StickyBottomBar
import com.mamre.billing.ui.theme.MamreTheme
import com.mamre.billing.ui.theme.Spacing

/** Where a print stands. A failure keeps the saved record and offers Retry (Doc 1 s5.4). */
sealed interface PrintStatus {
    data object Idle : PrintStatus

    data object Printing : PrintStatus

    data object Sent : PrintStatus

    data class Failed(val reason: String) : PrintStatus
}

/** W6 Bill and the payment receipt preview (W7): loads by id from the route. */
@Composable
fun ReceiptScreen(onDone: () -> Unit, onBack: () -> Unit, viewModel: ReceiptViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ReceiptContent(
        title = ui.title,
        lines = ui.lines,
        status = ui.status,
        printLabel = ui.printLabel,
        onPrint = viewModel::print,
        onDone = onDone,
        onBack = onBack,
    )
}

/** The receipt text in a monospace card, the print buttons pinned underneath. */
@Composable
fun ReceiptContent(
    title: String,
    lines: List<String>,
    status: PrintStatus,
    printLabel: String,
    onPrint: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                ReceiptText(lines)
            }
            when (status) {
                PrintStatus.Sent -> Text(
                    SENT_TO_MOCK_PRINTER,
                    style = MaterialTheme.typography.titleSmall,
                    color = MamreTheme.extra.success,
                )
                is PrintStatus.Failed -> AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text("Could not print", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "${status.reason}. Nothing is lost: the record is saved. Tap Retry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
        StickyBottomBar {
            val failed = status is PrintStatus.Failed
            PrimaryButton(
                if (failed) "Retry" else printLabel,
                onClick = onPrint,
                enabled = status != PrintStatus.Printing,
            )
            SecondaryButton("Done", onClick = onDone)
        }
    }
}

/**
 * The 32 columns drawn in a monospace font sized so all of them fit the card at any width and any
 * system font scale: the receipt never wraps and never scrolls sideways.
 */
@Composable
private fun ReceiptText(lines: List<String>) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val glyph = maxWidth / RECEIPT_WIDTH // dp per character; monospace glyphs are about 0.6 em wide
        val size = minOf(glyph / MONOSPACE_ADVANCE_EM, MAX_RECEIPT_TEXT)
        val sp = with(density) { size.toSp() } // dp to sp cancels the user's font scale
        Text(
            text = lines.joinToString("\n"),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = sp,
                lineHeight = sp * LINE_HEIGHT_RATIO,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            softWrap = false,
        )
    }
}

private const val MONOSPACE_ADVANCE_EM = 0.6f
private const val LINE_HEIGHT_RATIO = 1.3f
private val MAX_RECEIPT_TEXT = 16.dp
