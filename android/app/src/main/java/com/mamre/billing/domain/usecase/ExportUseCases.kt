package com.mamre.billing.domain.usecase

import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.UnitOfWork
import com.mamre.billing.data.repo.SettingsRepository
import java.time.Clock

/**
 * Records that an export was prepared (Doc 1 s11.1, A-26): the settings row last_export_at, epoch milliseconds UTC, written in one
 * transaction. Call it when the files and the manifest are written and the share sheet is launched; the app cannot know whether the
 * Owner finished sharing. It is a system record, not a business change, so it makes no change log entry.
 */
class RecordExport(
    private val unitOfWork: UnitOfWork,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke() {
        unitOfWork.run { settings.put(SettingKeys.LAST_EXPORT_AT, clock.millis().toString()) }
    }
}
