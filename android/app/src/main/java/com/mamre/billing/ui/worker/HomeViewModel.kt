package com.mamre.billing.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.SalesRepository
import com.mamre.billing.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val businessName: String = "",
    val ownerName: String = "",
    val deviceCode: String = "",
    /** A count only. The Sales area never shows bill totals here (Doc 2 s10, Doc 1 s2). */
    val invoicesToday: Int = 0,
)

/**
 * W1 Home. The business name, the Owner's name and the device code come from Settings; the only number is how many bills
 * were made today (Doc 2 s10: counts only, never totals).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    sales: SalesRepository,
    settings: SettingsRepository,
    clock: Clock,
) : ViewModel() {
    val state: StateFlow<HomeState> = combine(sales.observeState(), settings.observeAll()) { sold, s ->
        val today = LocalDate.now(clock)
        HomeState(
            businessName = s[SettingKeys.BUSINESS_NAME].orEmpty(),
            ownerName = s[SettingKeys.OWNER_NAME].orEmpty(),
            deviceCode = s[SettingKeys.DEVICE_CODE].orEmpty(),
            invoicesToday = sold.invoices.count { it.issuedAt.toLocalDate() == today },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
