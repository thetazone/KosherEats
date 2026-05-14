package com.greeneats.courier.ui.navigation

/**
 * Sealed class of all nav destinations in the courier app. Using a sealed
 * class (vs. raw string constants) makes `when` exhaustiveness checks work
 * and prevents typos from silently breaking navigation.
 */
sealed class Screen(val route: String) {
    data object AuthLanding : Screen("auth_landing")
    data object Login : Screen("login")
    data object EmailLogin : Screen("email_login")
    data object PhoneAuth : Screen("phone_auth")
    data object Signup : Screen("signup")
    data object Onboarding : Screen("onboarding")
    data object Dashboard : Screen("dashboard")
    data object Earnings : Screen("earnings")
    data object Profile : Screen("profile")
    data object Payouts : Screen("payouts")
    data object Chat : Screen("orders/{orderId}/chat") {
        fun createRoute(orderId: String) = "orders/$orderId/chat"
    }
}
