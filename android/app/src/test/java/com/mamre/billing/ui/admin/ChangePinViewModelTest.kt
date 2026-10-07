package com.mamre.billing.ui.admin

import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.domain.security.PinResult
import com.mamre.billing.domain.usecase.ChangePin
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
import com.mamre.billing.ui.lock.SETUP_WRITE_DOWN_TEXT
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Change PIN screen's logic (Doc 1 A-25, A-26; Doc 2 s6): current PIN once, new PIN twice, same delay as the PIN screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChangePinViewModelTest {
    private lateinit var w: World
    private lateinit var lock: AppLock

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory(), MutableClock()).also { it.seed() } }
        lock = AppLock(w.clock)
        runBlocking { SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)("1234", "1234") }
        lock.unlock()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun vm() = ChangePinViewModel(ChangePin(w.unitOfWork, w.settings, w.changeLog, w.clock), lock, w.clock)

    private fun verify(pin: String) = runBlocking { VerifyPin(w.unitOfWork, w.settings, w.clock)(pin) }

    private suspend fun ChangePinViewModel.until(p: (ChangePinUi) -> Boolean): ChangePinUi = withTimeout(10_000) { ui.first(p) }

    private fun ChangePinViewModel.fill(current: String, new: String, again: String) {
        onCurrent(current); onNew(new); onConfirm(again)
    }

    @Test fun theScreenShowsTheSameWriteItDownReminderAsTheSetup() {
        assertEquals(SETUP_WRITE_DOWN_TEXT, CHANGE_PIN_WRITE_DOWN_TEXT)
        assertTrue(CHANGE_PIN_WRITE_DOWN_TEXT.contains("Write this PIN down and keep it somewhere safe at home. It cannot be recovered."))
    }

    @Test fun theFieldsTakeDigitsOnlyAndAtMostSixAndTheButtonNeedsAllThree() = runBlocking {
        val v = vm()
        v.onCurrent("12a4-5 678")
        assertEquals("124567", v.ui.value.current)
        v.fill("1234", "56", "56")
        assertFalse(v.ui.value.canSubmit)
        v.fill("1234", "5678", "5678")
        assertTrue(v.ui.value.canSubmit)
    }

    @Test fun aWrongCurrentPinIsRefusedCountedAndTheFieldsAreCleared() = runBlocking {
        val v = vm()
        v.fill("0000", "5678", "5678")
        v.submit()
        val ui = v.until { it.message != null }
        assertEquals("Wrong current PIN. 4 tries left before a wait.", ui.message)
        assertEquals("", ui.current)
        assertEquals("", ui.newPin)
        assertEquals("", ui.confirm)
        assertFalse(ui.done)
        assertEquals("1", w.settings.get(SettingKeys.PIN_FAIL_COUNT))
        assertEquals(PinResult.Ok, verify("1234")) // nothing changed
    }

    @Test fun fiveWrongCurrentPinsShowTheWaitAndEvenTheRightOneIsRefusedUntilItEnds() = runBlocking {
        val v = vm()
        repeat(5) { v.fill("0000", "5678", "5678"); v.submit(); v.until { !it.busy && it.current.isEmpty() } }
        val ui = v.until { it.waitMs > 0 }
        assertEquals(30_000L, ui.waitMs)
        assertTrue(ui.message!!.contains("wait"))
        assertFalse(ui.canSubmit)
        v.fill("1234", "5678", "5678")
        assertFalse("no submit during the delay", v.ui.value.canSubmit)
        v.submit()
        assertFalse(v.ui.value.done)
        assertEquals(PinResult.Locked(30_000), verify("1234"))
        // The PIN screen counts the same delay: it is one counter.
        w.clock.set("2026-10-04T10:00:31Z")
        assertEquals(PinResult.Ok, verify("1234"))
    }

    @Test fun aCorrectCurrentPinChangesThePinReturnsAndRestartsTheLockTimer() = runBlocking {
        val v = vm()
        w.clock.advanceMinutes(4) // 4 of the 5 idle minutes are gone
        v.fill("1234", "5678", "5678")
        v.submit()
        val ui = v.until { it.done }
        assertEquals("", ui.current + ui.newPin + ui.confirm)
        assertTrue(verify("1234") is PinResult.Wrong)
        assertEquals(PinResult.Ok, verify("5678"))
        w.clock.advanceMinutes(2) // 6 minutes since the unlock, 2 since the change
        assertFalse("the lock timer was restarted by the change", lock.checkIdle(5))
        w.clock.advanceMinutes(4)
        assertTrue(lock.checkIdle(5))
    }

    @Test fun aNewPinEqualToTheCurrentOneIsRefusedWithAPlainSentence() = runBlocking {
        val v = vm()
        v.fill("1234", "1234", "1234")
        v.submit()
        val ui = v.until { it.message != null }
        assertEquals("The new PIN must be different from the current PIN.", ui.message)
        assertFalse(ui.done)
        assertEquals(PinResult.Ok, verify("1234"))
    }

    @Test fun twoDifferentNewEntriesAreRefusedAndNothingChanges() = runBlocking {
        val v = vm()
        v.fill("1234", "5678", "5679")
        v.submit()
        val ui = v.until { it.message != null }
        assertTrue(ui.message!!.contains("same"))
        assertFalse(ui.done)
        assertEquals(PinResult.Ok, verify("1234"))
    }

    @Test fun nothingTheScreenSaysEverContainsAPin() = runBlocking {
        val v = vm()
        v.fill("1234", "5678", "5679")
        v.submit()
        val refusal = v.until { it.message != null }.message!!
        assertFalse(listOf("1234", "5678", "5679").any { refusal.contains(it) })
        v.fill("4321", "5678", "5678")
        v.submit()
        val wrong = v.until { it.message != null && it.message != refusal }.message!!
        assertFalse(listOf("1234", "4321", "5678").any { wrong.contains(it) })
    }
}
