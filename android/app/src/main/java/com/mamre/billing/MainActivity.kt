package com.mamre.billing

import android.graphics.Color
import android.os.Bundle
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
import com.mamre.billing.ui.theme.MamreBillingTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var dataLabel: Optional<DataLabel>
    @Inject lateinit var features: Features

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light theme only: dark icons on the light bars, even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
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
