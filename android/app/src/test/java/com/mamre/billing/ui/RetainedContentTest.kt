package com.mamre.billing.ui

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Review finding: a bill, payment or return in progress must survive the activity being recreated (rotation, font scale, dark
 * mode, language) and be dropped ONLY when the app locks (Doc 2 s6). The content's view models live in the activity's
 * [ContentStore]. There is no Hilt here, so a probe view model with a draft stands in for the invoice view model: it is made by
 * the same store the real screens use.
 */
@RunWith(RobolectricTestRunner::class)
class RetainedContentTest {
    class Draft : ViewModel() {
        val lines = mutableListOf<String>()
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    /** What the next activity instance draws; set by each test, read by [Host.onCreate] (so a recreation draws the same). */
    companion object {
        /** What a new activity instance starts with (a recreation draws the same as before). */
        var current = AppScreen.CONTENT
        val drafts = mutableListOf<Draft>()
        var contentEntered = 0
        var pinDrawn = 0

        /** The draft the content composed last: what a Sales screen would be holding. */
        var held: Draft? = null
    }

    class Host : ComponentActivity() {
        var screen by mutableStateOf(current)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                AppFrame(
                    screen = screen,
                    gate = {},
                    pin = { SideEffect { pinDrawn++ }; Text("PIN") },
                    content = {
                        SideEffect { contentEntered++ }
                        RetainedContent {
                            val store = LocalViewModelStoreOwner.current!!.viewModelStore
                            held = ViewModelProvider(store, DraftFactory)[Draft::class.java]
                        }
                    },
                )
            }
        }
    }

    @Before fun reset() {
        current = AppScreen.CONTENT
        drafts.clear()
        contentEntered = 0
        pinDrawn = 0
        held = null
    }

    /** What the app does when it locks or unlocks: the screen of the running activity changes, and so does what a recreation draws. */
    private fun show(activity: Host, next: AppScreen) {
        current = next
        activity.screen = next
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun draftOf(@Suppress("UNUSED_PARAMETER") activity: ComponentActivity): Draft = held!!

    object DraftFactory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Draft().also { drafts += it } as T
    }

    @Test fun aDraftInTheContentSurvivesTheActivityBeingRecreated() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        settle()
        val before = draftOf(controller.get())
        before.lines += "2 x Paneer 250 g"
        controller.recreate()
        settle()
        val after = draftOf(controller.get())
        assertSame("the same view model, not a new one", before, after)
        assertEquals(listOf("2 x Paneer 250 g"), after.lines)
        assertTrue("it was not cleared by the recreation", !before.cleared)
        controller.get().finish()
    }

    @Test fun afterTheAppLocksTheStoreIsClearedAndTheDraftIsGone() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        settle()
        val before = draftOf(controller.get())
        before.lines += "2 x Paneer 250 g"
        show(controller.get(), AppScreen.PIN) // the idle lock fires
        settle()
        assertTrue("the lock cleared the view model", before.cleared)
        show(controller.get(), AppScreen.CONTENT) // the right PIN
        settle()
        val after = draftOf(controller.get())
        assertNotSame(before, after)
        assertTrue("an empty draft after the unlock", after.lines.isEmpty())
        controller.get().finish()
    }

    @Test fun lockingWhileTheActivityIsRecreatedStillDropsTheDraft() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        settle()
        val before = draftOf(controller.get())
        show(controller.get(), AppScreen.PIN)
        controller.recreate()
        settle()
        assertTrue("the draft did not outlive the lock", before.cleared)
        controller.get().finish()
    }

    @Test fun whileLockedRotatingStillDrawsThePinScreenAndComposesNoContent() {
        current = AppScreen.PIN
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        settle()
        controller.recreate()
        settle()
        controller.recreate()
        settle()
        assertEquals("no content was composed", 0, contentEntered)
        assertTrue("the PIN screen is drawn after each recreation", pinDrawn >= 3)
        assertTrue("no view model was started", drafts.isEmpty())
        controller.get().finish()
    }
}
