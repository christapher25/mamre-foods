package com.mamre.billing.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mamre.billing.BuildConfig
import com.mamre.billing.data.local.MutableClock
import com.mamre.billing.data.local.TestDatabase
import com.mamre.billing.data.local.World
import com.mamre.billing.domain.security.AppLock
import com.mamre.billing.ui.admin.SettingsViewModel
import com.mamre.billing.ui.worker.HomeViewModel
import java.io.File
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Review item 5 (owner decision): FLAG_SECURE on the window in the RELEASE build only (blank recent-apps preview, no
 * screenshots), and a "Lock now" button on the Sales Home and in Admin Settings that locks the app at once.
 *
 * HOW THE TWO BUILDS DIFFER: BuildConfig.SECURE_WINDOW, set per build type in app/build.gradle.kts (false by default and so in
 * debug, true in release); MainActivity passes it to applyWindowSecurity before the content is set. The debug unit tests see the
 * debug value; the release value is proven by a text check of build.gradle.kts here AND by verifyReleaseFlags, which reads the
 * generated release BuildConfig on every assembleRelease and fails the build if it is not true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LockNowAndSecureWindowTest {
    private lateinit var w: World
    private lateinit var lock: AppLock

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        w = runBlocking { World(TestDatabase.inMemory(), MutableClock()).also { it.seed() } }
        lock = AppLock(w.clock).also { it.unlock() }
    }

    // The view models start work when made (Settings collects the database revision): they live in a store that is CLEARED before the
    // database is closed, so no coroutine of theirs outlives the test and touches a closed database.
    private val store = ViewModelStore()

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : ViewModel> made(crossinline create: () -> T): T =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        })[T::class.java]

    private fun home() = made { HomeViewModel(w.sales, w.settings, w.clock, lock) }

    private fun settingsVm() = made { SettingsViewModel(w.admin, Optional.empty(), lock) }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        w.db.close()
    }

    private fun flagSecure(a: ComponentActivity) = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun module(path: String) = File(path).readText()

    // ---------------------------------------------------------------- FLAG_SECURE

    @Test fun theSecureFlagIsSetWhenAskedAndClearedWhenNot() {
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertFalse(flagSecure(a))
        applyWindowSecurity(a.window, secure = true)
        assertTrue(flagSecure(a))
        applyWindowSecurity(a.window, secure = false)
        assertFalse(flagSecure(a))
    }

    @Test fun theDebugBuildIsNotSecureSoItCanBeScreenshotted() {
        assertFalse("this unit test runs against the debug build", BuildConfig.SECURE_WINDOW)
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        applyWindowSecurity(a.window, BuildConfig.SECURE_WINDOW)
        assertFalse(flagSecure(a))
    }

    @Test fun theBuildFileMakesTheReleaseSecureAndEveryOtherBuildNot() {
        val gradle = module("build.gradle.kts")
        val defaults = gradle.substringBefore("buildTypes")
        val release = gradle.substringAfter("release {").substringBefore("optimization")
        assertTrue(Regex("""SECURE_WINDOW",\s*"false"""").containsMatchIn(defaults))
        assertFalse(Regex("""SECURE_WINDOW",\s*"true"""").containsMatchIn(defaults))
        assertTrue(Regex("""SECURE_WINDOW",\s*"true"""").containsMatchIn(release))
        assertTrue("the release build is checked on every assembleRelease", gradle.contains("""SECURE_WINDOW\s*=\s*true"""))
    }

    @Test fun theActivityAppliesTheFlagFromTheBuildConfigBeforeTheContentIsSet() {
        val source = module("src/main/java/com/mamre/billing/MainActivity.kt")
        val apply = source.indexOf("applyWindowSecurity(window, BuildConfig.SECURE_WINDOW)")
        assertTrue("MainActivity must pass BuildConfig.SECURE_WINDOW to applyWindowSecurity", apply > 0)
        assertTrue("before setContent", apply < source.indexOf("setContent {"))
    }

    // ---------------------------------------------------------------- Lock now

    @Test fun lockNowOnTheSalesHomeLocksTheAppAtOnce() {
        val home = home()
        assertFalse(lock.locked.value)
        home.lockNow()
        assertTrue(lock.locked.value)
    }

    @Test fun lockNowInAdminSettingsLocksTheAppAtOnce() {
        val settings = settingsVm()
        assertFalse(lock.locked.value)
        settings.lockNow()
        assertTrue(lock.locked.value)
    }

    @Test fun lockNowDoesNotChangeTheLockMinutesSetting() = runBlocking {
        val before = w.settings.lockMinutes()
        settingsVm().lockNow()
        home().lockNow()
        assertEquals(before, w.settings.lockMinutes())
    }

    @Test fun theAppStaysLockedUntilAnUnlockAndTheIdleRulesStillApply() {
        settingsVm().lockNow()
        w.clock.advanceMinutes(60)
        lock.noteActivity() // a touch on the PIN screen opens nothing
        assertTrue(lock.locked.value)
        lock.unlock()
        w.clock.advanceMinutes(5)
        assertTrue(lock.checkIdle(5)) // the lock minutes rule is unchanged
    }

    @Test fun theLockNowButtonsAreInTheScreens() {
        val home = module("src/main/java/com/mamre/billing/ui/worker/HomeScreen.kt")
        val settings = module("src/main/java/com/mamre/billing/ui/admin/SettingsScreen.kt")
        assertTrue(home.contains("\"Lock now\"") && home.contains("onLockNow"))
        assertTrue(settings.contains("\"Lock now\"") && settings.contains("actions.lockNow"))
    }
}
