package com.mamre.billing.ui.setup

import com.mamre.billing.data.local.SettingKeys
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.setup.SetupForm
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Doc 1 s2.1 FR-2: Save and continue writes through the Settings use cases (with the change log); a refused form saves nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetupBusinessViewModelTest {
    private lateinit var w: World

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory()).also { it.seed() } }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        w.db.close()
    }

    private suspend fun ready() = SetupBusinessViewModel(w.admin).also { vm -> withTimeout(10_000) { vm.ui.first { !it.loading } } }

    @Test fun theBusinessNameIsPrefilledFromTheSeed() = runBlocking {
        val vm = ready()
        assertTrue(vm.ui.value.form.businessName.isNotBlank())
    }

    @Test fun saveAndContinueWritesTheSettingsAndTheChangeLogThenCallsDone() = runBlocking {
        val vm = ready()
        val logBefore = w.changeLog.all().size
        vm.edit(vm.ui.value.form.copy(ownerName = "Rajesh", address = "1 Main Road", phone = "9876543210", footer = "Thank you"))
        val done = CompletableDeferred<Unit>()
        vm.saveAndContinue { done.complete(Unit) }
        withTimeout(10_000) { done.await() }
        val s = w.settings.all()
        assertEquals("Rajesh", s[SettingKeys.OWNER_NAME])
        assertEquals("1 Main Road", s[SettingKeys.ADDRESS])
        assertEquals("9876543210", s[SettingKeys.PHONE])
        assertEquals("Thank you", s[SettingKeys.FOOTER_TEXT])
        assertTrue(w.changeLog.all().size > logBefore)
    }

    @Test fun aRefusedFormSavesNothingAndStaysOnTheScreen() = runBlocking {
        val vm = ready()
        val before = w.settings.all()
        vm.edit(vm.ui.value.form.copy(ownerName = "R", address = "1 Main Road"))
        var called = false
        vm.saveAndContinue { called = true }
        assertFalse(called)
        assertNotNull(vm.ui.value.error)
        assertEquals(before, w.settings.all())
    }

    @Test fun skippingNeverTouchesTheViewModelSoNothingIsSaved() = runBlocking {
        val before = w.settings.all()
        ready() // the screen's Skip button only calls onDone; the view model has no save to run
        assertEquals(before, w.settings.all())
    }
}
