package com.mamre.billing.data.repository

import com.mamre.billing.data.demo.DemoStore
import javax.inject.Inject

sealed interface SyncOutcome {
    data object Done : SyncOutcome

    /** The catalog could not be pulled (offline, server error); nothing was changed. */
    data class Failed(val message: String) : SyncOutcome
}

/**
 * "Sync now" (Doc 2 s6, s10 W10): pulls the catalog from the stored cursor through [CatalogRepository], so
 * prices the Admin changed reach the device (owner costing spec 7). Records are still acknowledged by the
 * pretend server until the real outbox exists (P2); they are only acknowledged after the pull worked,
 * so an offline device keeps its pending count (Doc 2 s6.6).
 */
class WorkerSync @Inject constructor(
    private val catalog: CatalogRepository,
    private val store: DemoStore,
) {
    suspend fun syncNow(): SyncOutcome {
        try {
            catalog.refresh()
        } catch (e: java.io.IOException) {
            return SyncOutcome.Failed("Could not reach the server. Try again when you have signal.")
        } catch (e: com.mamre.billing.data.api.ApiException) {
            return SyncOutcome.Failed(e.message ?: "The server refused the sync.")
        }
        store.syncNow()
        return SyncOutcome.Done
    }
}
