package com.mamre.billing.ui.worker

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.export.ExportDue
import com.mamre.billing.domain.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The quiet export card on the Sales Home (Doc 1 s11.1, A-26): shown when the rule says an export is due, gone as soon as
 * RecordExport runs. The view model reads the real database and the injected clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeExportReminderTest {
    private lateinit var w: World

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
        w.clock.set("2026-10-19T12:00:00Z")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun home() = HomeViewModel(w.sales, w.settings, w.clock, AppLock(w.clock))

    private suspend fun makeOneBill() {
        w.price(ReferenceIds.TYPE_RESTAURANT, 250, 280)
        val customer = w.customer("Spice Garden")
        w.makeBill(w.draft(customer.id, listOf(w.line(unit = 250))))
    }

    private suspend fun HomeViewModel.stateWhere(predicate: (HomeState) -> Boolean) = withTimeout(10_000) { state.first(predicate) }

    @Test fun aBrandNewAppWithNoBillsNeverShowsTheCard() = runBlocking {
        val vm = home()
        assertNull(vm.stateWhere { it.deviceCode.isNotEmpty() }.exportDue)
    }

    @Test fun theCardShowsOnceABillExistsAndNothingWasExported() = runBlocking {
        val vm = home()
        vm.stateWhere { it.deviceCode.isNotEmpty() }
        makeOneBill()
        assertEquals(ExportDue.Never, vm.stateWhere { it.exportDue != null }.exportDue)
    }

    @Test fun theCardDisappearsAsSoonAsRecordExportRuns() = runBlocking {
        makeOneBill()
        val vm = home()
        vm.stateWhere { it.exportDue == ExportDue.Never }
        w.recordExport()
        assertNull(vm.stateWhere { it.exportDue == null && it.invoicesToday == 1 }.exportDue)
    }

    @Test fun anExportSixDaysAgoShowsNoCardAndEightDaysAgoShowsIt() = runBlocking {
        makeOneBill()
        w.recordExport()
        w.clock.advanceMs(6 * 24 * 3_600_000L)
        assertNull(home().stateWhere { it.invoicesToday >= 0 && it.deviceCode.isNotEmpty() }.exportDue)
        w.clock.advanceMs(2 * 24 * 3_600_000L)
        assertEquals(ExportDue.Days(8), home().stateWhere { it.deviceCode.isNotEmpty() }.exportDue)
    }

    @Test fun aPhoneClockMovedBackShowsTheCard() = runBlocking {
        makeOneBill()
        w.recordExport()
        w.clock.jumpWallMs(-3 * 24 * 3_600_000L)
        assertEquals(ExportDue.ClockBehind, home().stateWhere { it.deviceCode.isNotEmpty() }.exportDue)
    }

    @Test fun aDamagedStoredValueCountsAsNeverExported() = runBlocking {
        makeOneBill()
        w.settings.put(com.mamre.billing.data.local.SettingKeys.LAST_EXPORT_AT, "not a number")
        assertEquals(ExportDue.Never, home().stateWhere { it.deviceCode.isNotEmpty() }.exportDue)
    }

    @Test fun theHomeStateStillHoldsNoMoney() {
        val names = HomeState::class.java.declaredFields.map { it.name.lowercase() }
        assert(names.none { it.contains("cents") || it.contains("total") || it.contains("balance") }) { names.toString() }
    }
}
