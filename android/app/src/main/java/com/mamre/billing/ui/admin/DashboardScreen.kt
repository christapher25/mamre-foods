package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.DataSpan
import com.mamre.billing.ui.DataLabelChip
import com.mamre.billing.domain.admin.DashboardReport
import com.mamre.billing.domain.admin.Figure
import com.mamre.billing.domain.admin.monthShort
import com.mamre.billing.domain.money.formatCents
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.ChipKind
import com.mamre.billing.ui.components.KpiCard
import com.mamre.billing.ui.components.MonthSelector
import com.mamre.billing.ui.components.SectionHeader
import com.mamre.billing.ui.components.StatusChip
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUi(
    val loading: Boolean = true,
    val span: DataSpan? = null,
    val month: YearMonth? = null,
    val report: DashboardReport? = null,
)

/**
 * B1 Dashboard (Doc 2 s9). Asks the server for one month; the screen computes nothing. It opens on
 * the last full month, because the month in progress has only a few days of data.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val api: AdminApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUi())
    val ui: StateFlow<DashboardUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val span = api.span()
            val start = if (span.last.isAfter(span.first)) span.last.minusMonths(1) else span.last
            _ui.update { it.copy(span = span, month = start) }
            load()
            api.revision.collect { load() }
        }
    }

    fun selectMonth(month: YearMonth) {
        _ui.update { it.copy(month = month) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val month = _ui.value.month ?: return
        val report = api.dashboard(month)
        _ui.update { it.copy(loading = false, report = report) }
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    DashboardContent(ui, viewModel::selectMonth)
}

@Composable
fun DashboardContent(ui: DashboardUi, onMonth: (YearMonth) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "Dashboard",
            showLogo = true,
            subtitle = "Admin",
            actions = { DataLabelChip() },
        )
        val report = ui.report
        val span = ui.span
        val month = ui.month
        if (report == null || span == null || month == null) return
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MonthSelector(month, span.first, span.last, onMonth, inProgress = report.inProgress)
            KpiGrid(report)
            if (report.missingInputs.isNotEmpty()) MissingInputsCard(report.missingInputs)
            AppCard {
                Text("Net sales, last 6 months", style = MaterialTheme.typography.titleMedium)
                // Always six slots ending at the chosen month; months before the data begins stay empty.
                val slots = (5 downTo 0).map { month.minusMonths(it.toLong()) }
                MonthlyBarChart(
                    labels = slots.map { monthShort(it) },
                    valuesCents = slots.map { m -> report.sixMonthSales.firstOrNull { it.month == m }?.netSalesCents },
                    highlight = slots.lastIndex,
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
            AppCard {
                Text("Sales by product", style = MaterialTheme.typography.titleMedium)
                HorizontalBarChart(report.salesByProduct, Modifier.padding(top = Spacing.md))
            }
            AppCard {
                Text("Sales by customer type", style = MaterialTheme.typography.titleMedium)
                HorizontalBarChart(report.salesByCustomerType, Modifier.padding(top = Spacing.md))
            }
        }
    }
}

/** The seven cards of B1. A cost based card shows INCOMPLETE instead of a number it cannot know. */
@Composable
private fun KpiGrid(r: DashboardReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        KpiRow(
            { m -> KpiCard("Net sales", formatCents(r.netSalesCents), m, supporting = "Invoiced less return credits") },
            { m -> KpiCard("Cash collected", formatCents(r.cashCollectedCents), m, supporting = "Payments this month") },
        )
        KpiRow(
            { m -> KpiCard("Outstanding", formatCents(r.outstandingCents), m, supporting = "Owed at month end") },
            { m -> FigureCard("Direct cost", r.directCost, m) },
        )
        KpiRow(
            { m -> FigureCard("Gross profit", r.grossProfit, m) },
            { m -> KpiCard("Indirect expenses", formatCents(r.indirectExpensesCents), m, supporting = "Shared per chapathi") },
        )
        KpiRow(
            { m -> KpiCard("Packets sold", "${r.packetsSold}", m, supporting = "${r.chapathisSold} chapathis") },
            { m -> KpiCard("Chapathis sold", "${r.chapathisSold}", m, supporting = "Invoiced, not net of credits") },
        )
        NetProfitCard(r)
    }
}

@Composable
private fun KpiRow(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    // Both cards of a row are as tall as the taller one.
    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        left(Modifier.weight(1f).fillMaxHeight())
        right(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun FigureCard(label: String, figure: Figure, modifier: Modifier) = when (figure) {
    is Figure.Known -> KpiCard(label, formatCents(figure.value), modifier)
    is Figure.Incomplete -> KpiCard(label, NOT_AVAILABLE, modifier, chip = { StatusChip(INCOMPLETE, kind = ChipKind.WARNING) })
}

@Composable
private fun NetProfitCard(r: DashboardReport) {
    // The headline number: navy with white text, so the amber INCOMPLETE chip shows on it.
    val ink = MaterialTheme.colorScheme.onSecondary
    AppCard(containerColor = MaterialTheme.colorScheme.secondary) {
        Text("Net profit", style = MaterialTheme.typography.bodySmall, color = ink)
        when (val net = r.netProfit) {
            is Figure.Known -> Text(formatCents(net.value), style = MaterialTheme.typography.headlineLarge, color = ink)
            is Figure.Incomplete -> {
                Text(NOT_AVAILABLE, style = MaterialTheme.typography.headlineLarge, color = ink)
                StatusChip(INCOMPLETE, kind = ChipKind.WARNING, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
        Text(
            "Gross profit less indirect expenses. Wastage, damage and replacements are already in direct expense.",
            style = MaterialTheme.typography.bodySmall,
            color = ink,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** What the cost figures are still waiting for, in plain words (Doc 1 s9.1: never silently zero). */
@Composable
private fun MissingInputsCard(missing: List<String>) {
    AppCard {
        SectionHeader("Cost figures are incomplete")
        Text(
            "These inputs are missing, so the cost based figures are not shown:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        missing.forEach {
            Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

private const val INCOMPLETE = "INCOMPLETE"
private const val NOT_AVAILABLE = "—"
