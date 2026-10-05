package com.mamre.billing.ui.worker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.model.PaymentMode
import com.mamre.billing.print.PrintDocument
import com.mamre.billing.print.PrintResult
import com.mamre.billing.print.ReceiptPrinter
import com.mamre.billing.print.invoiceReceiptOf
import com.mamre.billing.print.layoutInvoiceReceipt
import com.mamre.billing.print.layoutPaymentReceipt
import com.mamre.billing.print.paymentReceiptOf
import com.mamre.billing.print.printSafely
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReceiptUi(
    val title: String = "",
    val lines: List<String> = emptyList(),
    val printLabel: String = "Print",
    val status: PrintStatus = PrintStatus.Idle,
)

/**
 * Shows one saved record as a receipt and prints it through the ReceiptPrinter (a mock until P3).
 * The record was saved before this screen opened, so a failed print loses nothing and Retry
 * prints the same lines again. A reprint from Today's invoices adds DUPLICATE COPY (Doc 1 s5.4).
 */
@HiltViewModel
class ReceiptViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val sales: SalesRepository,
    private val settings: SettingsRepository,
    private val printer: ReceiptPrinter,
) : ViewModel() {
    private val _ui = MutableStateFlow(ReceiptUi())
    val ui: StateFlow<ReceiptUi> = _ui.asStateFlow()

    init {
        val kind: String = savedState[WorkerRoutes.ARG_KIND] ?: WorkerRoutes.KIND_INVOICE
        val id: String = savedState[WorkerRoutes.ARG_ID] ?: ""
        val duplicate: Boolean = savedState[WorkerRoutes.ARG_DUPLICATE] ?: false
        viewModelScope.launch {
            _ui.update { if (kind == WorkerRoutes.KIND_PAYMENT) paymentUi(id) else invoiceUi(id, duplicate) }
        }
    }

    private suspend fun invoiceUi(id: String, duplicate: Boolean): ReceiptUi {
        val state = sales.state()
        val invoice = state.invoices.firstOrNull { it.id == id } ?: return ReceiptUi(title = "Invoice not found")
        val mode = invoice.customerId?.let { sales.customerPaymentMode(it) }
        val receipt = invoiceReceiptOf(
            invoice = invoice,
            customerLedger = invoice.customerId?.let(state::ledgerOf).orEmpty(),
            showMonthSummary = mode == PaymentMode.CREDIT,
            header = settings.businessHeader(),
            duplicate = duplicate,
            openingBalanceCents = invoice.customerId?.let(state::openingOf) ?: 0L,
        )
        return ReceiptUi(
            title = invoice.number,
            lines = layoutInvoiceReceipt(receipt),
            printLabel = if (duplicate) "Reprint" else "Print",
        )
    }

    private suspend fun paymentUi(id: String): ReceiptUi {
        val state = sales.state()
        val payment = state.payments.firstOrNull { it.id == id } ?: return ReceiptUi(title = "Receipt not found")
        val receipt = paymentReceiptOf(payment, state.balanceOf(payment.customerId), settings.businessHeader())
        return ReceiptUi(title = payment.receiptNumber, lines = layoutPaymentReceipt(receipt))
    }

    fun print() {
        val lines = _ui.value.lines
        if (lines.isEmpty() || _ui.value.status == PrintStatus.Printing) return
        _ui.update { it.copy(status = PrintStatus.Printing) }
        viewModelScope.launch {
            val status = when (val result = printer.printSafely(PrintDocument(lines))) {
                PrintResult.Sent -> PrintStatus.Sent
                is PrintResult.Failed -> PrintStatus.Failed(result.reason)
            }
            _ui.update { it.copy(status = status) }
        }
    }
}
