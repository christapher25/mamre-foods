package com.mamre.billing.data.local

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A Room executor that makes every query and transaction late by [delayMs] once [delayMs] is above zero. It makes a slow machine
 * deterministic: a test that reads a derived `ui.value` right after an action sees the old value here, as it does on a slow CI runner.
 * Set the delay after the test data is in place so the set-up stays fast.
 */
class SlowQueries {
    @Volatile var delayMs: Long = 0
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "slow-queries").apply { isDaemon = true } }
    val executor = Executor { task ->
        pool.execute {
            val d = delayMs
            if (d > 0) Thread.sleep(d)
            task.run()
        }
    }
}
