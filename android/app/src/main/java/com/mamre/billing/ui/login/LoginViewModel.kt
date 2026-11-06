package com.mamre.billing.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.accounts.Account
import com.mamre.billing.domain.accounts.MIN_PASSWORD
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.CurrentUser
import com.mamre.billing.domain.security.MonotonicClock
import com.mamre.billing.domain.usecase.AccountStatus
import com.mamre.billing.domain.usecase.CreateAdmin
import com.mamre.billing.domain.usecase.Login
import com.mamre.billing.domain.usecase.LoginResult
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.ui.lock.WaitCountdown
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** BUSINESS: the admin account was just created on a first run; the Set up your business screen comes before the app opens (Doc 1 s2.1 FR-2). */
enum class LoginMode { LOADING, CREATE_ADMIN, BUSINESS, LOGIN }

data class LoginUi(
    val mode: LoginMode = LoginMode.LOADING,
    val displayName: String = "",
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    /** Milliseconds still to wait for the account named in [waitFor] after too many wrong passwords; 0 when none. */
    val waitMs: Long = 0,
    val waitFor: String = "",
) {
    /** The wait belongs to one account (L6): another username can still be tried, so it does not block the button. */
    val canSubmit: Boolean
        get() = !busy && username.isNotBlank() && password.length >= 1 &&
            (mode != LoginMode.CREATE_ADMIN || (displayName.isNotBlank() && password.length >= MIN_PASSWORD && confirm.isNotEmpty()))
}

/** Said on the create-the-admin screen in plain words (B8 c; no recovery in version 1). */
const val CREATE_ADMIN_NO_RECOVERY_TEXT =
    "If you forget the admin password, the only way back in is to clear the app's data, which deletes everything."

/** Said when an account's stored data is unusable: that account cannot log in, and nothing offers a way round it. */
const val ACCOUNT_DAMAGED_TEXT =
    "This account's data on this phone is damaged, so it cannot log in. The only way back in is to clear the app's data, which deletes everything."

const val WRONG_LOGIN_TEXT = "Wrong username or password."
const val ACCOUNT_OFF_TEXT = "This account is switched off. Ask the admin."

private fun waitText(ms: Long) = "Too many wrong passwords. Please wait ${(ms + 999) / 1000} seconds."

/** The login screen (B8 c, d): create the admin on a first run, log in on every start, after the lock time and after Log out. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val status: AccountStatus,
    private val createAdmin: CreateAdmin,
    private val login: Login,
    private val settings: SettingsRepository,
    private val lock: AppLock,
    private val currentUser: CurrentUser,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui.asStateFlow()
    private var created: Account? = null
    private val countdown = WaitCountdown(viewModelScope, clock) { left ->
        _ui.update {
            // Only the account that was locked shows the wait; typing another username hides it (L6).
            if (it.waitFor == key(it.username)) it.copy(waitMs = left, message = if (left > 0) waitText(left) else null) else it
        }
    }

    init {
        currentUser.clear() // the login screen is shown whenever the app is locked: nobody is logged in here (L9)
        viewModelScope.launch {
            if (status.anyExists()) {
                _ui.update { it.copy(mode = LoginMode.LOGIN, username = settings.get(SettingKeys.LAST_USERNAME).orEmpty()) }
            } else {
                _ui.update { it.copy(mode = LoginMode.CREATE_ADMIN) }
            }
        }
    }

    private fun key(username: String) = username.trim().lowercase()

    fun onDisplayName(value: String) = _ui.update { it.copy(displayName = value, message = null) }

    fun onUsername(value: String) = _ui.update {
        val other = key(value) != it.waitFor
        it.copy(username = value, message = if (other) null else it.message, waitMs = if (other) 0 else it.waitMs)
    }

    fun onPassword(value: String) = _ui.update { it.copy(password = value, message = if (it.waitMs > 0) it.message else null) }

    fun onConfirm(value: String) = _ui.update { it.copy(confirm = value, message = null) }

    fun submit() {
        val now = _ui.value
        if (now.busy || now.mode == LoginMode.LOADING || now.mode == LoginMode.BUSINESS) return
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (now.mode == LoginMode.CREATE_ADMIN) {
                    createAdmin(now.displayName, now.username, now.password, now.confirm)
                    val r = login(now.username, now.password)
                    if (r is LoginResult.Ok) created = r.user
                    // First run: the business screen comes before the app opens; the app stays locked until it is done (FR-2).
                    _ui.update { LoginUi(mode = LoginMode.BUSINESS) }
                } else {
                    answer(login(now.username, now.password), now)
                }
            } catch (e: RuleException) {
                _ui.update { it.copy(busy = false, message = e.message) }
            }
        }
    }

    private fun answer(r: LoginResult, asked: LoginUi) {
        when (r) {
            is LoginResult.Ok -> finish(r.user)
            LoginResult.UnknownUser -> fail(WRONG_LOGIN_TEXT)
            LoginResult.Damaged -> fail(ACCOUNT_DAMAGED_TEXT)
            LoginResult.Inactive -> fail(ACCOUNT_OFF_TEXT)
            is LoginResult.Wrong -> fail("$WRONG_LOGIN_TEXT ${r.triesBeforeDelay} tries left before a wait.")
            is LoginResult.Locked -> {
                _ui.update { it.copy(busy = false, password = "", waitFor = key(asked.username)) }
                countdown.start(r.remainingMs)
            }
        }
    }

    private fun fail(message: String) = _ui.update { it.copy(busy = false, password = "", message = message) }

    /** The Set up your business screen was saved or skipped: now the app opens, for the admin who was just created. */
    fun businessDone() {
        if (_ui.value.mode != LoginMode.BUSINESS) return
        val admin = created
        if (admin == null) { // cannot happen (the account was just made); fall back to the login rather than stay stuck
            _ui.update { it.copy(mode = LoginMode.LOGIN) }
            return
        }
        created = null
        finish(admin)
    }

    private fun finish(account: Account) {
        currentUser.set(account)
        _ui.update { LoginUi(mode = LoginMode.LOGIN, username = account.username) } // the password never stays in memory longer than needed
        lock.unlock()
    }
}
