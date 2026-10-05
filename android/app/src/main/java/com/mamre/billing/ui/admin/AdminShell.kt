package com.mamre.billing.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamre.billing.domain.auth.Features
import com.mamre.billing.ui.components.AppCard
import com.mamre.billing.ui.components.AppTopBar
import com.mamre.billing.ui.theme.Spacing

private const val BAR_MAX_FONT_SCALE = 1.1f
private const val BAR_LABEL_SP = 11

private fun AdminTab.icon(): ImageVector = when (this) {
    AdminTab.DASHBOARD -> Icons.Default.Dashboard
    AdminTab.SALES -> Icons.Default.ShoppingCart
    AdminTab.CUSTOMERS -> Icons.Default.People
    AdminTab.PRICES -> Icons.Default.Sell
    AdminTab.MORE -> Icons.Default.MoreHoriz
}

/**
 * Dashboard, Sales, Customers, Prices, More. The font scale is capped inside the bar only, so five
 * labels still fit a 360 dp screen at large system font sizes; the rest of the app scales fully.
 */
@Composable
fun AdminBottomBar(tabs: List<AdminTab>, selected: AdminTab?, onSelect: (AdminTab) -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, BAR_MAX_FONT_SCALE)),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0), // the activity already pads the system bars
            ) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        label = {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = BAR_LABEL_SP.sp),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

/** More opens the five less frequent screens (owner brief). */
@Composable
fun MoreScreen(features: Features, onOpen: (String) -> Unit, onSalesArea: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "More")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            for (route in moreRoutes(features)) {
                when (route) {
                    AdminRoutes.COSTING -> MoreItem("Costing", "Cost per packet, recipes and wastage", route, onOpen)
                    AdminRoutes.EXPENSES -> MoreItem("Expenses", "Material purchases and other expenses", route, onOpen)
                    AdminRoutes.RETURNS -> MoreItem("Returns and damage", "Customer returns and production damage", route, onOpen)
                    AdminRoutes.BALANCES -> MoreItem("Balances", "Who owes what, with ageing", route, onOpen)
                    AdminRoutes.SETTINGS -> MoreItem("Settings", "Business details and wastage", route, onOpen)
                }
            }
            MoreItem("Sales area", "Switch to bills, payments and returns", "", { onSalesArea() })
        }
    }
}

@Composable
private fun MoreItem(title: String, subtitle: String, route: String, onOpen: (String) -> Unit) {
    AppCard(onClick = { onOpen(route) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
