package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.api.BackendApi
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AdminHomeViewModel @Inject constructor(
    private val api: BackendApi,
    private val session: SessionManager,
) : ViewModel() {
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _name.value = session.authorized { api.me(it) }.fullName
            } catch (_: Exception) {
                // Offline or expired: the name is cosmetic; a dead session returns to Login by itself.
            }
        }
    }

    fun logout() = session.logout()
}

/**
 * The Admin experience starts here. Stage A has only this placeholder and Log out; the
 * dashboard and the other admin screens are Stage B.
 */
@Composable
fun AdminHomeScreen(viewModel: AdminHomeViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = name ?: "Admin", subtitle = "Admin")
        EmptyState(
            title = "Admin screens are coming",
            message = "Analytics, sales, customers, prices and costing arrive in the next stage.",
            modifier = Modifier.padding(top = Spacing.xl),
        )
        Column(Modifier.padding(Spacing.lg)) { SecondaryButton("Log out", onClick = viewModel::logout) }
    }
}
