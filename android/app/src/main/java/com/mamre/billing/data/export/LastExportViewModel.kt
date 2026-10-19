package com.mamre.billing.data.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamre.billing.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** The "Last export prepared" time for the Settings screen (Doc 1 s11.1): epoch ms, null for never. */
@HiltViewModel
class LastExportViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val lastExportAt: StateFlow<Long?> =
        settings.observeLastExportAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
