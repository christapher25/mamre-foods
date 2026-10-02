package com.mamre.billing.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.auth.SessionExpiredException
import com.mamre.billing.data.auth.SessionManager
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
)

/**
 * Loads the worker profile (GET /me) and refreshes the catalog (GET /sync/catalog) when the
 * app has signal. Failures are silent: offline the worker keeps the last catalog (Doc 2 s6).
 * A dead session is handled by SessionManager.signedIn, which returns the UI to Login.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: BackendApi,
    private val session: SessionManager,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            quietly {
                val me = session.authorized { api.me(it) }
                _state.update { it.copy(workerName = me.fullName, deviceCode = me.deviceCode) }
            }
            quietly { catalog.refresh() }
        }
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
