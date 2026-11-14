package com.mamre.billing.ui.admin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.print.AndroidPrintDriver
import com.mamre.billing.print.BluetoothEscPosDriver
import com.mamre.billing.print.BluetoothPermissionGate
import com.mamre.billing.print.PrintDocument
import com.mamre.billing.print.PrintResult
import com.mamre.billing.print.PrinterCheckInfo
import com.mamre.billing.print.ReceiptPrinter
import com.mamre.billing.print.RECEIPT_WIDTH
import com.mamre.billing.print.SettingsPrinterMethodStore
import com.mamre.billing.print.SunmiServiceDriver
import com.mamre.billing.print.isPrinterPackage
import com.mamre.billing.print.printerReportOf
import com.mamre.billing.print.wrapTo32
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.PrimaryButton
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Admin > Settings > Printer check (Doc 4 s5, A1 item 6): what this phone offers for printing, text only, no network.

data class PrinterCheckUi(val report: List<String> = emptyList(), val message: String? = null)

@HiltViewModel
class PrinterCheckViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printer: ReceiptPrinter,
    private val settings: SettingsRepository,
    private val gate: BluetoothPermissionGate,
) : ViewModel() {
    private val _ui = MutableStateFlow(PrinterCheckUi())
    val ui: StateFlow<PrinterCheckUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _ui.value = _ui.value.copy(report = printerReportOf(collect())) }
    }

    /** Runs the whole chain with the report as the bill; the 32-column ruler shows whether the paper width is right. */
    fun printTestPage() {
        viewModelScope.launch {
            val header = settings.businessHeader()
            val lines = wrapTo32(
                listOf(header.name, "") + printerReportOf(collect()) +
                    listOf("", "1234567890".repeat(4).take(RECEIPT_WIDTH), "-".repeat(RECEIPT_WIDTH), "End of test page"),
            )
            val result = printer.print(PrintDocument(lines))
            _ui.value = _ui.value.copy(
                message = when (result) {
                    PrintResult.Sent -> "Test page sent to the printer."
                    is PrintResult.Failed -> result.reason
                },
            )
            refresh()
        }
    }

    fun shareText(): String = _ui.value.report.joinToString("\n")

    @SuppressLint("MissingPermission")
    private suspend fun collect(): PrinterCheckInfo {
        val pm = context.packageManager
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val granted = gate.isGranted()
        val paired = if (granted) {
            adapter?.bondedDevices.orEmpty().map { "${it.name ?: "(no name)"} (0x${"%04X".format(it.bluetoothClass?.majorDeviceClass ?: 0)})" }
        } else {
            emptyList()
        }
        val services = pm.queryIntentServices(Intent("android.printservice.PrintService"), 0).map { it.serviceInfo.packageName }
        val packages = pm.getInstalledPackages(0).map { it.packageName }.filter(::isPrinterPackage).sorted()
        return PrinterCheckInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkLevel = Build.VERSION.SDK_INT,
            pairedDevices = paired,
            bluetoothNote = when {
                adapter == null -> "no Bluetooth on this phone"
                !granted -> "Bluetooth permission not given yet"
                else -> null
            },
            printServices = services.distinct().sorted(),
            printerPackages = packages,
            driverAvailability = listOf(SunmiServiceDriver(context), BluetoothEscPosDriver(context, gate), AndroidPrintDriver(context))
                .map { it.name to it.isAvailable() },
            savedMethod = SettingsPrinterMethodStore(settings).saved(),
        )
    }
}

@Composable
fun PrinterCheckScreen(onBack: () -> Unit, viewModel: PrinterCheckViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Printer check", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppCard {
                Text(ui.report.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            ui.message?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            PrimaryButton("Print test page", onClick = viewModel::printTestPage)
            SecondaryButton("Share report", onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.shareText())
                context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}
