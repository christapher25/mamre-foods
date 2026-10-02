package com.mamre.billing.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.api.ApiException
import com.mamre.billing.data.auth.RoleRejectedException
import com.mamre.billing.data.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = !loading && username.isNotBlank() && password.isNotEmpty()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                session.login(current.username.trim(), current.password)
                _state.update { it.copy(loading = false, password = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoleRejectedException) {
                _state.update { it.copy(loading = false, password = "", error = e.message) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: IOException) {
                _state.update {
                    it.copy(loading = false, error = "No connection. Sign in needs internet the first time.")
                }
            }
        }
    }
}
