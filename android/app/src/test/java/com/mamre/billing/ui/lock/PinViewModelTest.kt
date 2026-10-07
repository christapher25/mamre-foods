package com.mamre.billing.ui.lock

import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.usecase.PinStatus
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The PIN screen's logic (Doc 2 s6, s10): first-run setup, entry, a wrong PIN, the delay, and unlocking the app. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PinViewModelTest {
    private lateinit var w: World
    private lateinit var lock: AppLock

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory(), MutableClock()).also { it.seed() } }
        lock = AppLock(w.clock)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun vm() = PinViewModel(
        PinStatus(w.settings), SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock), VerifyPin(w.unitOfWork, w.settings, w.clock), lock, w.clock,
    )

    private suspend fun PinViewModel.ready(mode: PinMode): PinUi = withTimeout(10_000) { ui.first { it.mode == mode } }

    private suspend fun PinViewModel.until(p: (PinUi) -> Boolean): PinUi = withTimeout(10_000) { ui.first(p) }

    @Test fun onAFirstRunTheScreenAsksForANewPinWithTheNoRecoveryWarnings() = runBlocking {
        val v = vm()
        val ui = v.ready(PinMode.SETUP)
        assertTrue(lock.locked.value)
        assertTrue(SETUP_NO_RECOVERY_TEXT.contains("If you forget this PIN, the only way back in is to clear the app's data, which deletes everything"))
        assertTrue(SETUP_WRITE_DOWN_TEXT.contains("Write this PIN down and keep it somewhere safe at home. It cannot be recovered."))
        assertNull(ui.message)
    }

    @Test fun thePinFieldsTakeDigitsOnlyAndAtMostSix() = runBlocking {
        val v = vm()
        v.ready(PinMode.SETUP)
        v.onPin("12a4-5 678")
        assertEquals("124567", v.ui.value.pin)
        v.onConfirm("9x9")
        assertEquals("99", v.ui.value.confirm)
    }

    @Test fun twoDifferentEntriesAreRefusedAndNothingIsSaved() = runBlocking {
        val v = vm()
        v.ready(PinMode.SETUP)
        v.onPin("1234"); v.onConfirm("1235")
        v.submit()
        val ui = v.until { it.message != null }
        assertTrue(ui.message!!.contains("same"))
        assertTrue(lock.locked.value)
        assertFalse(PinStatus(w.settings).isSet())
    }

    @Test fun chosenTwiceTheSamePinIsSavedAndTheAppIsUnlocked() = runBlocking {
        val v = vm()
        v.ready(PinMode.SETUP)
        v.onPin("482913"); v.onConfirm("482913")
        v.submit()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertTrue(PinStatus(w.settings).isSet())
        assertFalse(lock.locked.value)
    }

    @Test fun onALaterStartTheScreenAsksForThePinAndTheRightOneUnlocks() = runBlocking {
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        val v = vm()
        v.ready(PinMode.ENTRY)
        v.onPin("1234")
        v.submit()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertEquals("", v.ui.value.pin) // the field is cleared
    }

    @Test fun aWrongPinIsRefusedTheFieldIsClearedAndTheAppStaysLocked() = runBlocking {
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        val v = vm()
        v.ready(PinMode.ENTRY)
        v.onPin("9999")
        v.submit()
        val ui = v.until { it.message != null }
        assertTrue(ui.message!!, ui.message!!.contains("Wrong PIN"))
        assertTrue(ui.message!!.contains("4"))
        assertEquals("", ui.pin)
        assertTrue(lock.locked.value)
    }

    @Test fun fiveWrongPinsShowTheWaitAndEvenTheRightPinDoesNotOpen() = runBlocking {
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        val v = vm()
        v.ready(PinMode.ENTRY)
        repeat(5) { v.onPin("9999"); v.submit(); v.until { it.pin.isEmpty() && !it.busy } }
        val ui = v.until { it.waitMs > 0 }
        assertEquals(30_000L, ui.waitMs)
        assertTrue(ui.message!!.contains("wait"))
        v.onPin("1234"); v.submit()
        v.until { !it.busy }
        assertTrue("the right PIN is refused during the delay", lock.locked.value)
        w.clock.set("2026-10-04T10:00:31Z")
        v.onPin("1234"); v.submit()
        withTimeout(10_000) { lock.locked.first { !it } }
        assertNotNull(v.ui.value)
    }

    @Test fun damagedPinDataShowsTheEntryScreenNeverTheSetupAndNothingOpens() = runBlocking {
        SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234")
        for (damage in listOf<suspend () -> Unit>(
            { w.db.openHelper.writableDatabase.execSQL("DELETE FROM settings WHERE key = 'pin_salt'") },
            { w.settings.put("pin_salt", "") },
            { w.settings.put("pin_salt", "AAAAAAAAAAAAAAAAAAAAAA=="); w.settings.put("pin_hash", "garbage") },
        )) {
            damage()
            val v = vm()
            val ui = v.ready(PinMode.ENTRY) // never SETUP
            assertFalse(ui.mode == PinMode.SETUP)
            v.onPin("1234")
            v.submit()
            val after = v.until { it.message != null }
            assertEquals(PIN_DAMAGED_TEXT, after.message)
            assertEquals(PinMode.ENTRY, after.mode)
            assertTrue(lock.locked.value)
            assertTrue(PinStatus(w.settings).isSet())
        }
    }

    @Test fun theSubmitButtonNeedsAtLeastFourDigits() = runBlocking {
        val v = vm()
        v.ready(PinMode.SETUP)
        v.onPin("12"); v.onConfirm("12")
        assertFalse(v.ui.value.canSubmit)
        v.onPin("1234"); v.onConfirm("1234")
        assertTrue(v.ui.value.canSubmit)
    }
}
