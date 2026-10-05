package com.mamre.billing.data.local

import com.mamre.billing.domain.security.PinResult
import com.mamre.billing.domain.usecase.ChangePin
import com.mamre.billing.domain.usecase.PinStatus
import com.mamre.billing.domain.usecase.RuleException
import com.mamre.billing.domain.usecase.SetPin
import com.mamre.billing.domain.usecase.VerifyPin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AT-19 (Doc 2 s6): a wrong PIN is refused, only a salted hash is stored, the wrong-PIN delay is enforced and survives the
 * process being killed (the counter and the lock time are settings rows), and the PIN can be changed only with the old one.
 * There is no recovery.
 */
@RunWith(RobolectricTestRunner::class)
class PinUseCasesTest {
    private val file = "pin-test.db"

    @After fun cleanUp() = TestDatabase.delete(file)

    private class Pin(w: World) {
        val set = SetPin(w.unitOfWork, w.settings, w.changeLog, w.clock)
        val change = ChangePin(w.unitOfWork, w.settings, w.changeLog, w.clock)
        val verify = VerifyPin(w.unitOfWork, w.settings, w.clock)
        val status = PinStatus(w.settings)
    }

    private suspend fun world(db: MamreDatabase = TestDatabase.inMemory()) = World(db).also { it.seed() }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: RuleException) {
            return e.message.orEmpty()
        }
        fail("expected the rule to refuse")
        return ""
    }

    private suspend fun everythingStored(w: World): String =
        w.settings.all().entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n" +
            w.changeLog.all().joinToString("\n") { "${it.what}|${it.beforeText}|${it.afterText}" }

    // ---------------------------------------------------------------- setting the PIN

    @Test fun beforeThePinIsChosenTheStatusSaysSoAndVerifyHasNothingToCheck() = runBlocking {
        val w = world()
        val p = Pin(w)
        assertFalse(p.status.isSet())
        assertEquals(PinResult.NotSet, p.verify("1234"))
    }

    @Test fun aSaltedHashAndTheSaltAreStoredAndThePlainPinIsNotStoredAnywhere() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("482913".take(6), "482913".take(6))
        val hash = w.settings.get(SettingKeys.PIN_HASH)!!
        val salt = w.settings.get(SettingKeys.PIN_SALT)!!
        assertTrue(hash.startsWith("pbkdf2-sha256:120000:"))
        assertTrue(salt.isNotBlank())
        // The plain PIN is in no setting and in no change-log text.
        assertFalse(everythingStored(w).contains("482913"))
        assertTrue(p.status.isSet())
    }

    @Test fun aPinNeedsFourToSixDigitsAndTwoEqualEntries() = runBlocking {
        val w = world()
        val p = Pin(w)
        assertTrue(refused { p.set("123", "123") }.contains("4 to 6"))
        assertTrue(refused { p.set("1234567", "1234567") }.contains("4 to 6"))
        assertTrue(refused { p.set("12ab", "12ab") }.contains("4 to 6"))
        assertTrue(refused { p.set("1234", "1235") }.contains("same"))
        assertNull(w.settings.get(SettingKeys.PIN_HASH))
        assertFalse(p.status.isSet())
    }

    @Test fun thePinCanBeChosenOnlyOnceThereIsNoSecondSetup() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        assertTrue(refused { p.set("5678", "5678") }.contains("already"))
        assertEquals(PinResult.Ok, p.verify("1234"))
    }

    @Test fun settingThePinIsChangeLoggedWithoutAnyValue() = runBlocking {
        val w = world()
        Pin(w).set("1234", "1234")
        val entry = w.changeLog.all().single { it.what.contains("PIN") }
        assertFalse(entry.beforeText.contains("1234") || entry.afterText.contains("1234"))
    }

    // ---------------------------------------------------------------- checking the PIN

    @Test fun theRightPinOpensAndAWrongPinIsRefused() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        assertEquals(PinResult.Ok, p.verify("1234"))
        assertEquals(PinResult.Wrong(triesBeforeDelay = 4), p.verify("1235"))
        assertEquals(PinResult.Wrong(triesBeforeDelay = 3), p.verify("0000"))
        assertEquals(PinResult.Ok, p.verify("1234")) // the right one still works and resets the counter
        assertEquals(PinResult.Wrong(triesBeforeDelay = 4), p.verify("1235"))
    }

    @Test fun fiveWrongPinsStartTheDelayAndEvenTheRightPinIsRefusedUntilItEnds() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        repeat(4) { assertTrue(p.verify("9999") is PinResult.Wrong) }
        val fifth = p.verify("9999")
        assertEquals(PinResult.Locked(remainingMs = 30_000), fifth)
        // While the delay runs the RIGHT PIN is refused and the clock is not advanced.
        w.clock.advanceMinutes(0)
        assertEquals(PinResult.Locked(remainingMs = 30_000), p.verify("1234"))
        // A wrong try during the delay does not count and does not lengthen it.
        assertEquals(PinResult.Locked(remainingMs = 30_000), p.verify("9999"))
        assertEquals("5", w.settings.get(SettingKeys.PIN_FAIL_COUNT))
    }

    @Test fun afterTheDelayTheCorrectPinOpensAndTheCounterIsReset() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        repeat(5) { p.verify("9999") }
        w.clock.set("2026-10-04T10:00:31Z") // 31 seconds later than the fixed start 10:00:00
        assertEquals(PinResult.Ok, p.verify("1234"))
        assertEquals("0", w.settings.get(SettingKeys.PIN_FAIL_COUNT))
        assertEquals("0", w.settings.get(SettingKeys.PIN_LOCKED_UNTIL))
    }

    @Test fun aSixthWrongPinAfterTheDelayDoublesTheDelay() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        repeat(5) { p.verify("9999") }
        w.clock.set("2026-10-04T10:00:31Z")
        assertEquals(PinResult.Locked(remainingMs = 60_000), p.verify("9999"))
        w.clock.set("2026-10-04T10:01:32Z")
        assertEquals(PinResult.Locked(remainingMs = 120_000), p.verify("9999"))
    }

    @Test fun theDelayIsStillInForceAfterTheProcessIsKilledAndTheDatabaseReopened() = runBlocking {
        var w = world(TestDatabase.file(file))
        Pin(w).set("1234", "1234")
        repeat(5) { Pin(w).verify("9999") }
        w.db.close()
        // A new process: new objects, the same database file, the same clock.
        w = World(TestDatabase.file(file), w.clock)
        val p = Pin(w)
        assertEquals(PinResult.Locked(remainingMs = 30_000), p.verify("1234"))
        w.clock.set("2026-10-04T10:00:31Z")
        assertEquals(PinResult.Ok, p.verify("1234"))
        w.db.close()
    }

    @Test fun aTamperedFailCountSettingNeverCrashesAndCountsAsZero() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        w.settings.put(SettingKeys.PIN_FAIL_COUNT, "not a number")
        assertEquals(PinResult.Ok, p.verify("1234"))
    }

    // ---------------------------------------------------------------- changing the PIN

    @Test fun changingThePinNeedsTheOldOneAndTheNewOneTwice() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        assertTrue(refused { p.change("9999", "5678", "5678") }.contains("old PIN"))
        assertTrue(refused { p.change("1234", "5678", "5679") }.contains("same"))
        assertTrue(refused { p.change("1234", "56", "56") }.contains("4 to 6"))
        assertEquals(PinResult.Ok, p.verify("1234")) // nothing changed
        p.change("1234", "5678", "5678")
        assertTrue(p.verify("1234") is PinResult.Wrong)
        assertEquals(PinResult.Ok, p.verify("5678"))
        assertFalse(everythingStored(w).contains("5678"))
        assertTrue(w.changeLog.all().any { it.what.contains("PIN changed") })
    }

    @Test fun wrongOldPinsWhenChangingCountTowardsTheSameDelay() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        repeat(5) { refused { p.change("0000", "5678", "5678") } }
        assertTrue(refused { p.change("1234", "5678", "5678") }.contains("wait"))
        assertNotNull(w.settings.get(SettingKeys.PIN_HASH))
    }

    @Test fun theHashChangesWithANewSaltEachTimeThePinIsChanged() = runBlocking {
        val w = world()
        val p = Pin(w)
        p.set("1234", "1234")
        val first = w.settings.get(SettingKeys.PIN_SALT)
        p.change("1234", "1234", "1234")
        assertTrue(first != w.settings.get(SettingKeys.PIN_SALT))
    }
}
