package com.mamre.billing

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mamre.billing.data.repo.SettingsRepository
import com.mamre.billing.domain.security.AppLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import com.mamre.billing.ui.DataLabel
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.ui.LocalDataLabel
import com.mamre.billing.ui.LocalFeatures
import com.mamre.billing.ui.MamreNavHost
import com.mamre.billing.ui.applyWindowSecurity
import com.mamre.billing.ui.theme.MamreBillingTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var dataLabel: Optional<DataLabel>
    @Inject lateinit var features: Features
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var settings: SettingsRepository

    /** A touch counts as activity for the idle lock (Doc 2 s6); the time comes from the injected clock. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        appLock.noteActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        // Back from the background: lock if the app was away for the set minutes or more.
        lifecycleScope.launch { appLock.onForegrounded(settings.lockMinutes()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Release only: FLAG_SECURE blanks the recent-apps preview and refuses screenshots (BuildConfig.SECURE_WINDOW).
        applyWindowSecurity(window, BuildConfig.SECURE_WINDOW)
        // Light theme only: dark icons on the light bars, even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // While the app is in front, look for the idle time every few seconds. It stops with the screen and with the process.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(IDLE_CHECK_MS)
                    appLock.checkIdle(settings.lockMinutes())
                }
            }
        }
        setContent {
            MamreBillingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CompositionLocalProvider(LocalDataLabel provides dataLabel.orElse(null), LocalFeatures provides features) {
                        Box(Modifier.safeDrawingPadding()) { MamreNavHost() }
                    }
                }
            }
        }
    }
}

private const val IDLE_CHECK_MS = 5_000L
