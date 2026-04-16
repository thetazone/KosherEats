package com.koshereats.consumer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object NearbyMap : Screen("map")
    data object Search : Screen("search")
    data object Restaurant : Screen("restaurant/{restaurantId}") {
        fun createRoute(restaurantId: String) = "restaurant/$restaurantId"
    }
    data object Cart : Screen("cart")
    data object Checkout : Screen("checkout")
    data object OrderConfirmation : Screen("order-confirmation/{orderId}") {
        fun createRoute(orderId: String) = "order-confirmation/$orderId"
    }
    data object Orders : Screen("orders")
    data object OrderDetail : Screen("orders/{orderId}") {
        fun createRoute(orderId: String) = "orders/$orderId"
    }
    data object OrderTracking : Screen("orders/{orderId}/tracking") {
        fun createRoute(orderId: String) = "orders/$orderId/tracking"
    }
    data object Chat : Screen("orders/{orderId}/chat") {
        fun createRoute(orderId: String) = "orders/$orderId/chat"
    }
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Profile : Screen("profile")
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
    SEARCH(
        route = Screen.Search.route,
        label = "Search",
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search,
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
