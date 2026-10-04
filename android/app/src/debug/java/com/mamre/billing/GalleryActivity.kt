package com.mamre.billing

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mamre.billing.domain.worker.InvoiceLine
import com.mamre.billing.domain.worker.InvoiceRecord
import com.mamre.billing.domain.worker.InvoiceStatus
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.theme.MamreBillingTheme
import com.mamre.billing.ui.theme.Spacing
import com.mamre.billing.ui.worker.InvoiceCard
import com.mamre.billing.ui.worker.PrintStatus
import com.mamre.billing.ui.worker.ProductCard
import com.mamre.billing.ui.worker.ReceiptContent
import java.time.LocalDateTime

/** Dev-only state gallery (debug source set, never committed). Mode: cards or failed. */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        val mode = intent.getStringExtra("mode") ?: "cards"
        setContent {
            MamreBillingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding()) {
                        if (mode == "failed") {
                            ReceiptContent(
                                title = "MAM-W1-0001",
                                lines = listOf("          MAMRE FOODS", "--------------------------------", "TOTAL                      $6.40"),
                                status = PrintStatus.Failed("Printer out of paper"),
                                printLabel = "Print",
                                onPrint = {}, onDone = {}, onBack = {},
                            )
                        } else {
                            AppTopBar("States", onBack = {})
                            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                ProductCard("Mamre Chapathi", 250, 3, {})
                                ProductCard("New Product", null, 0, {})
                                val line = InvoiceLine("p", "Mamre Chapathi", 10, 250)
                                val inv = InvoiceRecord("i", "MAM-W1-0003", "c", "Test Restaurant", "Restaurant", "W1",
                                    LocalDateTime.of(2026, 10, 2, 9, 30), listOf(line), 2500, 0, null, 14500)
                                InvoiceCard(inv.copy(status = InvoiceStatus.VOID, voidReason = "Wrong customer"), {})
                                InvoiceCard(inv.copy(number = "MAM-W1-0004"), {})
                            }
                        }
                    }
                }
            }
        }
    }
}
