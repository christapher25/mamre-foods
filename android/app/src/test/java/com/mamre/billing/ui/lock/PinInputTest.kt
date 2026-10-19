package com.mamre.billing.ui.lock

import com.mamre.billing.domain.security.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one digits filter and the one wait countdown that the PIN screen and the Change PIN screen share (review item 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class PinInputTest {
    private class Mono : MonotonicClock {
        var ms = 1_000_000L
        override fun elapsedMs() = ms
        override fun bootId() = "b"
    }

    @Test fun onlyDigitsAreKeptAndAtMostSix() {
        assertEquals("124567", pinDigits("12a4-5 678"))
        assertEquals("", pinDigits("abc"))
        assertEquals("1234", pinDigits("1234"))
        assertEquals("123456", pinDigits("1234567890"))
    }

    @Test fun theWaitSentenceRoundsUpToWholeSeconds() {
        assertEquals("Too many wrong PINs. Please wait 30 seconds.", waitMessage(30_000))
        assertEquals("Too many wrong PINs. Please wait 1 seconds.", waitMessage(1))
        assertEquals("Too many wrong PINs. Please wait 2 seconds.", waitMessage(1_001))
    }

    @Test fun theCountdownReportsTheStartCountsDownOnTheMonotonicClockAndEndsAtZero() {
        val scope = TestScope()
        val mono = Mono()
        val seen = mutableListOf<Long>()
        val countdown = WaitCountdown(scope, mono) { seen += it }
        countdown.start(3_000)
        assertEquals(listOf(3_000L), seen) // at once
        mono.ms += 1_000; scope.advanceTimeBy(1_000); scope.runCurrent()
        mono.ms += 1_000; scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), seen)
        mono.ms += 1_000; scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(0L, seen.last())
        val count = seen.size
        mono.ms += 5_000; scope.advanceTimeBy(5_000); scope.runCurrent()
        assertEquals("nothing more after the end", count, seen.size)
    }

    @Test fun theCountdownFollowsTheClockNotTheNumberOfTicks() {
        val scope = TestScope()
        val mono = Mono()
        val seen = mutableListOf<Long>()
        WaitCountdown(scope, mono) { seen += it }.start(30_000)
        mono.ms += 12_000 // the phone slept: one tick, twelve seconds
        scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(18_000L, seen.last())
    }

    @Test fun startingAgainReplacesTheRunningCountdown() {
        val scope = TestScope()
        val mono = Mono()
        val seen = mutableListOf<Long>()
        val countdown = WaitCountdown(scope, mono) { seen += it }
        countdown.start(30_000)
        countdown.start(60_000)
        mono.ms += 1_000; scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(59_000L, seen.last())
        assertEquals("one countdown, not two", listOf(30_000L, 60_000L, 59_000L), seen)
    }
}
