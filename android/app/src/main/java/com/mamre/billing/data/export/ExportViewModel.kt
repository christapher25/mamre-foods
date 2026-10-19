package com.mamre.billing.data.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.usecase.RecordExport
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an export is: nothing yet, being written, written and ready for the share sheet, or failed. */
sealed interface ExportState {
    data object Idle : ExportState

    data object Working : ExportState

    /** The files are in cacheDir/exports; the screen opens the share sheet once and then calls [ExportViewModel.shareLaunched] or [ExportViewModel.shareNotLaunched]. */
    data class Ready(val files: List<File>) : ExportState

    data class Failed(val message: String) : ExportState
}

/**
 * Admin > Settings > Export: reads the database (read only), writes the files to the cache folder, offers them to share. The export
 * is recorded (RecordExport, Doc 1 s11.1) only after the 18 files and the manifest are written and the share sheet is launched.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val builder: ExportBuilder,
    private val writer: ExportWriter,
    private val recordExport: RecordExport,
    settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<ExportState>(ExportState.Idle)
    val ui: StateFlow<ExportState> = _ui.asStateFlow()

    /** When the last export was prepared (epoch ms), null for never. */
    val lastExportAt: StateFlow<Long?> =
        settings.observeLastExportAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun export(): Job {
        if (_ui.value is ExportState.Working) return Job().also { it.complete() }
        _ui.value = ExportState.Working
        return viewModelScope.launch {
            _ui.value = try {
                val bundle = builder.build()
                ExportState.Ready(withContext(Dispatchers.IO) { writer.write(bundle) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed("The export could not be made. Nothing was shared. ${e.message.orEmpty()}".trim())
            }
        }
    }

    /**
     * The share sheet was launched: the files are ready and out, so the export is recorded, then back to the start. Only files that
     * are written (Ready) are ever recorded. A failure to record is swallowed: the sharing already happened, and the label then
     * stays on the older time, which only makes the reminder come sooner.
     */
    fun shareLaunched(): Job {
        val ready = _ui.value is ExportState.Ready
        _ui.value = ExportState.Idle
        if (!ready) return Job().also { it.complete() }
        return viewModelScope.launch {
            try {
                recordExport()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // see above
            }
        }
    }

    /** No app could take the share sheet: nothing was shared, so nothing is recorded; back to the start. */
    fun shareNotLaunched() {
        _ui.value = ExportState.Idle
    }
}
