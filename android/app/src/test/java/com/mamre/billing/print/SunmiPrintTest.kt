package com.mamre.billing.print

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A1 round 2, fix 1 (AT-11): the answer to printerInit never decides the print; only the answer to sendRAWData does. */
@RunWith(RobolectricTestRunner::class)
class SunmiPrintTest {
    private val bytes = byteArrayOf(1, 2, 3)

    @Test fun initSuccessAloneDoesNotCompleteThePrint() = runTest {
        val result = runSunmiPrint(bytes, init = { it.onRunResult(true) }, send = { _, _ -> }, timeoutMs = 50)
        assertEquals(PrintResult.Failed("Printer did not answer"), result)
    }

    @Test fun aFailureAfterInitGivesFailed() = runTest {
        val result = runSunmiPrint(
            bytes,
            init = { it.onRunResult(true) },
            send = { _, cb -> cb.onRaiseException(7, "Out of paper") },
        )
        assertEquals(PrintResult.Failed("Out of paper"), result)
    }

    @Test fun aRefusedRawPrintAfterInitGivesFailed() = runTest {
        val result = runSunmiPrint(bytes, init = { it.onRunResult(true) }, send = { _, cb -> cb.onRunResult(false) })
        assertTrue(result is PrintResult.Failed)
    }

    @Test fun initSuccessThenRawSuccessGivesSent() = runTest {
        val result = runSunmiPrint(bytes, init = { it.onRunResult(true) }, send = { _, cb -> cb.onRunResult(true) })
        assertEquals(PrintResult.Sent, result)
    }
}
