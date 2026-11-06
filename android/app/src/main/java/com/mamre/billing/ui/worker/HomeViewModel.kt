package com.mamre.billing.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.CustomerRepository
import com.mamre.billing.data.repo.PriceRepository
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.export.ExportDue
import com.mamre.billing.domain.export.exportDue
import com.mamre.billing.domain.export.parseLastExport
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.setup.PriceKey
import com.mamre.billing.domain.setup.SetupGaps
import com.mamre.billing.domain.setup.setupGaps
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val businessName: String = "",
    val deviceCode: String = "",
    /** A count only. The Sales area never shows bill totals here (Doc 2 s10, Doc 1 s2). */
    val invoicesToday: Int = 0,
    /** Why an export is due now (Doc 1 s11.1), or null: then the Home shows no reminder card. Not money. */
    val exportDue: ExportDue? = null,
    /** What the Finish setup card lists (Doc 1 s2.1 FR-3); not money. */
    val setup: SetupGaps = SetupGaps(businessDetails = false, missingPrices = 0),
)

/**
 * W1 Home. The business name, the Owner's name and the device code come from Settings; the only number is how many bills
 * were made today (Doc 2 s10: counts only, never totals). [HomeState.exportDue] says whether to show the quiet export reminder.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    sales: SalesRepository,
    settings: SettingsRepository,
    clock: Clock,
    prices: PriceRepository,
    customers: CustomerRepository,
    private val lock: AppLock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    /** Products and customer types have no flow, so the Home asks again when it comes back to the front (a product added in Admin). */
    fun refreshSetup() { refresh.value += 1 }

    val state: StateFlow<HomeState> = combine(sales.observeState(), settings.observeAll(), prices.observeDefaults(), refresh) { sold, s, defaults, _ ->
        val today = LocalDate.now(clock)
        HomeState(
            businessName = s[SettingKeys.BUSINESS_NAME].orEmpty(),
            deviceCode = s[SettingKeys.DEVICE_CODE].orEmpty(),
            invoicesToday = sold.invoices.count { it.issuedAt.toLocalDate() == today },
            setup = setupGaps(s, prices.products().filter { it.isActive }.map { it.id }, customers.types().map { it.id }, defaults.filter { !it.effectiveFrom.isAfter(today) }.map { PriceKey(it.productId, it.customerTypeId) }),
            exportDue = exportDue(parseLastExport(s[SettingKeys.LAST_EXPORT_AT]), hasBills = sold.invoices.isNotEmpty(), clock = clock),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** The "Lock now" button: the app locks at once and asks for the PIN (Doc 2 s6). The lock minutes setting is not touched. */
    fun lockNow() = lock.lockNow()
}
