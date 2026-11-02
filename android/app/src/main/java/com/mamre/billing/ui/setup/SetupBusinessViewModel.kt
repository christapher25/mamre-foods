package com.mamre.billing.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.admin.AdminApi
import com.mamre.billing.data.admin.AdminRuleException
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.setup.SetupForm
import com.mamre.billing.domain.setup.setupFormProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUi(
    val loading: Boolean = true,
    val form: SetupForm = SetupForm("", "", "", "", ""),
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Set up your business (Doc 1 s2.1 FR-2), shown once, straight after the first PIN. Saving goes through the existing Settings use
 * cases (so the change log gets its lines as for any Settings edit). Skip saves nothing.
 */
@HiltViewModel
class SetupBusinessViewModel @Inject constructor(private val api: AdminApi) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val s = api.settings()
            _ui.update { it.copy(loading = false, form = SetupForm(s.businessName, api.ownerName(), s.address, s.phone, s.footerText)) }
        }
    }

    fun edit(form: SetupForm) = _ui.update { it.copy(form = form, error = null) }

    /** Saves, then calls [onDone]. A refused form stays on the screen with the reason. */
    fun saveAndContinue(onDone: () -> Unit) {
        val form = _ui.value.form
        setupFormProblem(form)?.let { problem -> _ui.update { it.copy(error = problem) }; return }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                api.saveSettings(BusinessSettings(form.businessName, form.address, form.phone, form.footer))
                if (form.ownerName.isNotBlank()) api.setOwnerName(form.ownerName)
                onDone()
            } catch (e: AdminRuleException) {
                _ui.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}
