package com.greeneats.seller.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLEncoder

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector? = null,
    val unselectedIcon: ImageVector? = null,
) {
    // Auth
    data object Login : Screen("login", "Login")
    data object PhoneLogin : Screen("phone-login", "Phone Login")
    data object Onboarding : Screen("onboarding", "Onboarding")

    // Bottom nav tabs
    data object Dashboard : Screen(
        "dashboard", "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
    )
    data object Orders : Screen(
        "orders", "Orders",
        selectedIcon = Icons.Filled.Receipt,
        unselectedIcon = Icons.Outlined.Receipt,
    )
    data object Menu : Screen(
        "menu", "Menu",
        selectedIcon = Icons.Filled.MenuBook,
        unselectedIcon = Icons.Outlined.MenuBook,
    )
    data object Deals : Screen(
        "deals", "Deals",
        selectedIcon = Icons.Filled.LocalOffer,
        unselectedIcon = Icons.Outlined.LocalOffer,
    )
    data object Settings : Screen(
        "settings", "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )

    // Detail screens
    data object OrderDetail : Screen("orders/{orderId}", "Order Detail") {
        fun createRoute(orderId: String): String {
            require(orderId.isNotBlank()) { "orderId must not be blank" }
            return "orders/${URLEncoder.encode(orderId, "UTF-8")}"
        }
    }
    data object MenuItemForm : Screen("menu/form?itemId={itemId}", "Menu Item") {
        fun createRoute(itemId: String? = null): String {
            if (itemId.isNullOrBlank()) return "menu/form"
            return "menu/form?itemId=${URLEncoder.encode(itemId, "UTF-8")}"
        }
    }
    data object CreateDeal : Screen("deals/create", "Create Deal")
    data object Integrations : Screen("settings/integrations", "Integrations")

    companion object {
        val bottomNavItems = listOf(Dashboard, Orders, Menu, Deals, Settings)
    }
}
