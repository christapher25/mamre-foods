package com.mamre.billing.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.mamre.billing.domain.auth.Area
import com.mamre.billing.domain.auth.Features
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Review item 4: while the app is locked the PIN screen is drawn INSTEAD of the content, never on top of it. The REAL Sales and
 * Admin graphs are put in the content slot: composing any screen of them here would call hiltViewModel() with no Hilt and throw,
 * and the counters show the content slot was not entered at all. A process start with a restored back stack is the same case as
 * a locked frame: the PIN screen is the first and only thing composed.
 */
@RunWith(RobolectricTestRunner::class)
class LockedFrameTest {
    private val release = Features(showAnalytics = false)
    private val analytics = Features(showAnalytics = true)

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    @Test fun whileLockedTheRealSalesAndAdminGraphsAreNotComposedAndNothingOfThemStarts() {
        for (features in listOf(release, analytics)) for (area in Area.entries) {
            var contentSlotEntered = 0
            var pin = 0
            val a = activity()
            a.setContent {
                AppFrame(
                    screen = screenFor(hasGate = false, gateOpen = true, locked = true),
                    gate = {},
                    pin = { SideEffect { pin++ }; Text("PIN") },
                    content = {
                        SideEffect { contentSlotEntered++ }
                        AppContent(area, features, onOpenAdmin = {}, onOpenSales = {})
                    },
                )
            }
            settle() // would throw here if a Sales or Admin screen were composed: there is no Hilt in this test
            assertEquals("$area $features", 0, contentSlotEntered)
            assertTrue("$area $features: the PIN screen is drawn", pin >= 1)
            a.finish()
        }
    }

    @Test fun lockingRemovesTheContentFromTheCompositionInTheSameStepThePinScreenAppears() {
        var screen by mutableStateOf(AppScreen.CONTENT)
        var contentIn = 0
        var contentOut = 0
        var pinIn = 0
        val a = activity()
        a.setContent {
            AppFrame(
                screen = screen,
                gate = {},
                pin = { DisposableEffect(Unit) { pinIn++; onDispose { } }; Text("PIN") },
                content = { DisposableEffect(Unit) { contentIn++; onDispose { contentOut++ } }; Text("Sales figures") },
            )
        }
        settle()
        assertEquals(1, contentIn)
        assertEquals(0, pinIn)
        screen = screenFor(hasGate = false, gateOpen = true, locked = true) // the idle lock fires
        settle()
        assertEquals("the content left the composition", 1, contentOut)
        assertEquals("the PIN screen took its place", 1, pinIn)
        screen = screenFor(hasGate = false, gateOpen = true, locked = false) // the right PIN
        settle()
        assertEquals("a fresh content, not the old one", 2, contentIn)
        a.finish()
    }

    @Test fun theViewModelsOfTheContentAreClearedWhenTheAppLocksAndStartAfreshOnUnlock() {
        var screen by mutableStateOf(AppScreen.CONTENT)
        val made = mutableListOf<Probe>()
        val a = activity()
        a.setContent {
            AppFrame(
                screen = screen,
                gate = {},
                pin = { Text("PIN") },
                content = {
                    RetainedContent {
                        val store = LocalViewModelStoreOwner.current!!.viewModelStore
                        val factory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe().also { made += it } as T
                        }
                        ViewModelProvider(store, factory)[Probe::class.java]
                    }
                },
            )
        }
        settle()
        assertEquals(1, made.size)
        assertTrue(!made[0].cleared)
        screen = AppScreen.PIN
        settle()
        assertTrue("the screen's view model was cleared by the lock", made[0].cleared)
        screen = AppScreen.CONTENT
        settle()
        assertEquals("a new view model after the unlock", 2, made.size)
        assertTrue(!made[1].cleared)
        a.finish()
    }

    @Test fun aClosedDebugGateDrawsOnlyTheGateNotThePinAndNotTheContent() {
        var gate = 0
        var pin = 0
        var content = 0
        val a = activity()
        a.setContent {
            AppFrame(
                screen = screenFor(hasGate = true, gateOpen = false, locked = true),
                gate = { SideEffect { gate++ } },
                pin = { SideEffect { pin++ } },
                content = { SideEffect { content++ } },
            )
        }
        settle()
        assertEquals(listOf(1, 0, 0), listOf(gate, pin, content))
        a.finish()
    }
}
