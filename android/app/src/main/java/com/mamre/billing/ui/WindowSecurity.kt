package com.mamre.billing.ui

import android.view.Window
import android.view.WindowManager

/**
 * FLAG_SECURE (owner decision, review item 5): with it set, the recent-apps preview of the app is blank and the system refuses
 * screenshots and screen recording of it. [secure] is BuildConfig.SECURE_WINDOW: true in the release build, false in the debug
 * build, so a debug build can still be screenshotted. Calling it with false also clears the flag.
 */
fun applyWindowSecurity(window: Window, secure: Boolean) {
    if (secure) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
