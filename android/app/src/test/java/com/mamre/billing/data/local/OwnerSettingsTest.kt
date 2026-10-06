package com.mamre.billing.data.local

import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetLockMinutes
import com.mamre.billing.domain.usecase.SetOwnerName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Doc 3 L2 Settings: the owner name (printed as Salesman, Doc 1 A-29) and the lock time (Doc 2 s6, 1 to 30 minutes). Each save is in the change log. */
@RunWith(RobolectricTestRunner::class)
class OwnerSettingsTest {
    private suspend fun world() = World(TestDatabase.inMemory()).also { it.seed() }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    @Test fun theOwnerNameIsSavedTrimmedAndLoggedAndTheFirstRunHasNone() = runBlocking {
        val w = world()
        val set = SetOwnerName(w.unitOfWork, w.settings, w.changeLog, w.clock)
        assertEquals("", w.settings.ownerName())
        set("  Rajesh   Thomas ")
        assertEquals("Rajesh Thomas", w.settings.ownerName())
        val log = w.newLog().first()
        assertEquals("Owner name", log.what)
        assertEquals("", log.before)
        assertEquals("Rajesh Thomas", log.after)
    }

    @Test fun anEmptyOwnerNameIsRefusedAndNothingChanges() = runBlocking {
        val w = world()
        val set = SetOwnerName(w.unitOfWork, w.settings, w.changeLog, w.clock)
        set("Rajesh")
        assertTrue(refused { set("   ") }.contains("name"))
        assertTrue(refused { set("x".repeat(41)) }.contains("40"))
        assertEquals("Rajesh", w.settings.ownerName())
    }

    @Test fun theLockTimeIsOneToThirtyMinutesDefaultFive() = runBlocking {
        val w = world()
        val set = SetLockMinutes(w.unitOfWork, w.settings, w.changeLog, w.clock)
        assertEquals(5, w.settings.lockMinutes())
        assertTrue(refused { set(0) }.contains("1 to 30"))
        assertTrue(refused { set(31) }.contains("1 to 30"))
        assertEquals(5, w.settings.lockMinutes())
        set(10)
        assertEquals(10, w.settings.lockMinutes())
        val log = w.newLog().first()
        assertEquals("Lock time", log.what)
        assertEquals("5 minutes", log.before)
        assertEquals("10 minutes", log.after)
        set(1)
        assertEquals(1, w.settings.lockMinutes())
        set(30)
        assertEquals(30, w.settings.lockMinutes())
    }
}
