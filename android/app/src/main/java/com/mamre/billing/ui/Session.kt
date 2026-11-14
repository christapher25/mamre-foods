package com.mamre.billing.ui

import androidx.lifecycle.ViewModel
import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.auth.Role
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Who is logged in, for the screens that depend on it (the Home tiles, the area switch) and the Log out button (B8 e, h, L9). */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val currentUser: CurrentUser,
    private val lock: AppLock,
) : ViewModel() {
    val account: StateFlow<Account?> = currentUser.account

    /** Log out: the app locks, which clears the content and the current account and shows the login (B8 h). */
    fun logOut() {
        currentUser.clear()
        lock.lockNow()
    }
}

/** The role of a possibly missing account: nobody logged in is treated as a SALESMAN, the narrowest role (fails closed). */
val Account?.roleOrNarrowest: Role get() = this?.role ?: Role.SALESMAN
