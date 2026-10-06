package com.mamre.billing.data.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an export is: nothing yet, being written, written and ready for the share sheet, or failed. */
sealed interface ExportState {
    data object Idle : ExportState

    data object Working : ExportState

    /** The files are in cacheDir/exports; the screen opens the share sheet once and then calls [ExportViewModel.shared]. */
    data class Ready(val files: List<File>) : ExportState

    data class Failed(val message: String) : ExportState
}

/** Admin > Settings > Export: reads the database (read only), writes the files to the cache folder, offers them to share. */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val builder: ExportBuilder,
    private val writer: ExportWriter,
) : ViewModel() {
    private val _ui = MutableStateFlow<ExportState>(ExportState.Idle)
    val ui: StateFlow<ExportState> = _ui.asStateFlow()

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

    /** The share sheet was opened (or dismissed): back to the start. */
    fun shared() {
        _ui.value = ExportState.Idle
    }
}
