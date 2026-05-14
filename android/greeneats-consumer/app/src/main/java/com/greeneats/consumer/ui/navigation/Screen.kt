package com.greeneats.consumer.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object NearbyMap : Screen("map")
    data object Deals : Screen("deals")
    data object Restaurant : Screen("restaurant/{restaurantId}") {
        fun createRoute(restaurantId: String) = "restaurant/${Uri.encode(restaurantId)}"
    }
    data object Cart : Screen("cart")
    data object Checkout : Screen("checkout")
    data object OrderConfirmation : Screen("order-confirmation/{orderId}") {
        fun createRoute(orderId: String) = "order-confirmation/${Uri.encode(orderId)}"
    }
    data object Orders : Screen("orders")
    data object OrderDetail : Screen("orders/{orderId}") {
        fun createRoute(orderId: String) = "orders/${Uri.encode(orderId)}"
    }
    data object OrderTracking : Screen("orders/{orderId}/tracking") {
        fun createRoute(orderId: String) = "orders/${Uri.encode(orderId)}/tracking"
    }
    data object Chat : Screen("orders/{orderId}/chat") {
        fun createRoute(orderId: String) = "orders/${Uri.encode(orderId)}/chat"
    }
    data object Login : Screen("login")
    data object EmailLogin : Screen("email-login")
    data object Register : Screen("register")
    data object PhoneAuth : Screen("phone-auth")
    data object PhonePrompt : Screen("phone-prompt")
    data object Profile : Screen("profile")
    data object EditProfile : Screen("profile/edit")
    data object SavedAddresses : Screen("profile/addresses")
    data object PaymentMethods : Screen("profile/payment-methods")
    data object Favorites : Screen("profile/favorites")
    data object NotificationPreferences : Screen("profile/notification-preferences")
    data object ConnectedAccounts : Screen("profile/connected-accounts")
    data object Rating : Screen("orders/{orderId}/rating") {
        fun createRoute(orderId: String) = "orders/${Uri.encode(orderId)}/rating"
    }
}

enum class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        route = Screen.Home.route,
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    MAP(
        route = Screen.NearbyMap.route,
        label = "Map",
        selectedIcon = Icons.Filled.Map,
        unselectedIcon = Icons.Outlined.Map,
    ),
    DEALS(
        route = Screen.Deals.route,
        label = "Deals",
        selectedIcon = Icons.Filled.LocalOffer,
        unselectedIcon = Icons.Outlined.LocalOffer,
    ),
    ORDERS(
        route = Screen.Orders.route,
        label = "Orders",
        selectedIcon = Icons.Filled.Receipt,
        unselectedIcon = Icons.Outlined.Receipt,
    ),
    PROFILE(
        route = Screen.Profile.route,
        label = "Profile",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
}
