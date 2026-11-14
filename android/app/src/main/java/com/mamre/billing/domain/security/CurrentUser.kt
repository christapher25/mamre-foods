package com.mamre.billing.domain.security

import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.usecase.RuleException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is logged in (B8 L9). Held in memory only: it is never stored, so a killed and restarted app always asks for the login
 * again, and it is cleared on lock and on Log out. Use cases that make a bill, a payment or a return take the account as an argument.
 */
@Singleton
class CurrentUser @Inject constructor() {
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun set(account: Account) {
        _account.value = account
    }

    fun clear() {
        _account.value = null
    }

    /** The logged-in account, or a refusal when nobody is logged in (a screen should not be reachable then). */
    fun require(): Account = _account.value ?: throw RuleException("Log in again")
}
