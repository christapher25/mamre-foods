package com.mamre.billing.data.system

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.mamre.billing.domain.security.MonotonicClock

/** [SystemClock.elapsedRealtime] (counts through sleep, not changed by the clock settings) and the system's boot count. */
class AndroidMonotonicClock(private val context: Context) : MonotonicClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun bootId(): String =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()?.toString().orEmpty()
}
