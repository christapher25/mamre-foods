package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mamre.billing.data.auth.SessionManager
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.components.EmptyState
import com.mamre.billing.ui.components.SecondaryButton
import com.mamre.billing.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AdminHomeViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {
    /** Stored at sign-in (Doc 2 s2). */
    val name: String? = session.profile?.fullName

    fun logout() = session.logout()
}

/**
 * The Admin experience starts here. Stage A has only this placeholder and Log out; the
 * dashboard and the other admin screens are Stage B.
 */
@Composable
fun AdminHomeScreen(viewModel: AdminHomeViewModel = hiltViewModel()) {
    val name = viewModel.name
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
