package com.mamre.billing.ui.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.auth.SessionExpiredException
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.data.demo.DemoStore
import com.mamre.billing.data.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val workerName: String? = null,
    val deviceCode: String? = null,
    /** A count only. The worker never sees invoice totals here (Doc 2 s10, Doc 1 s2). */
    val invoicesToday: Int = 0,
    val pendingCount: Int = 0,
)

/**
 * W1 Home. Shows the profile stored at sign-in and the counts. It does NOT refresh the catalog: customers, the
 * corporate flag, locations, prices, price-edit switches and the business settings reach the device only at
 * Sync now (owner decision). Only an EMPTY local catalog (first use after login) is loaded, once, and failures
 * then are silent: offline the salesman keeps what the device has (Doc 2 s6). A dead session is handled by
 * SessionManager.role, which returns the UI to Login.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: SessionManager,
    private val catalog: CatalogRepository,
    private val store: DemoStore,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { demo ->
                val today = store.today()
                _state.update {
                    it.copy(
                        invoicesToday = demo.invoices.count { inv -> inv.issuedAt.toLocalDate() == today },
                        pendingCount = demo.pendingCount,
                    )
                }
            }
        }
        // Name and device code were stored at sign-in, so Home works offline (Doc 2 s2, s6).
        session.profile?.let { p ->
            _state.update { it.copy(workerName = p.fullName, deviceCode = p.deviceCode) }
        }
        // Sync timing (owner decision): the catalog changes ONLY at Sync now. The one exception is first use after
        // login, when the local catalog is empty: it is loaded once so the app is usable.
        viewModelScope.launch { quietly { if (catalog.isEmpty()) catalog.refresh() } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
        } catch (_: ApiException) {
        } catch (_: SessionExpiredException) {
        }
    }
}
