package com.greeneats.courier.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.greeneats.courier.data.models.OnboardingStatus
import com.greeneats.courier.ui.screens.auth.AuthLandingScreen
import com.greeneats.courier.ui.screens.auth.EmailLoginScreen
import com.greeneats.courier.ui.screens.auth.LoginScreen
import com.greeneats.courier.ui.screens.auth.PhoneOTPScreen
import com.greeneats.courier.ui.screens.auth.SignupScreen
import com.greeneats.courier.ui.screens.chat.ChatScreen
import com.greeneats.courier.ui.screens.dashboard.DashboardScreen
import com.greeneats.courier.ui.screens.earnings.EarningsScreen
import com.greeneats.courier.ui.screens.onboarding.OnboardingFlowScreen
import com.greeneats.courier.ui.screens.payouts.PayoutsSetupScreen
import com.greeneats.courier.ui.screens.profile.CourierProfileScreen
import com.greeneats.courier.ui.theme.BackgroundBlack
import com.greeneats.courier.ui.theme.BackgroundDark
import com.greeneats.courier.ui.theme.Orange
import com.greeneats.courier.ui.theme.TextMuted
import com.greeneats.courier.ui.viewmodels.AuthViewModel

/**
 * Root nav host. Decides between the auth stack, the onboarding funnel, and
 * the main tabbed app based on the current AuthViewModel state.
 *
 * The bottom bar only appears once the courier is approved and inside the
 * main experience — during signup/onboarding it stays hidden like UberEats Driver.
 */
@Composable
fun CourierNavHost(authViewModel: AuthViewModel = hiltViewModel()) {
    val state by authViewModel.state.collectAsState()
    val profile = state.profile

    when {
        !state.isAuthenticated -> AuthFlow(authViewModel)
        profile == null -> LoadingShim()
        profile.onboardingStatus != OnboardingStatus.APPROVED -> {
            OnboardingFlowScreen(
                profile = profile,
                onRefresh = { authViewModel.loadProfile() },
                onLogout = { authViewModel.logout() },
            )
        }
        else -> MainTabs(authViewModel)
    }
}

@Composable
private fun AuthFlow(authViewModel: AuthViewModel) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screen.AuthLanding.route,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Screen.AuthLanding.route) {
            AuthLandingScreen(
                onSignup = { navController.navigate(Screen.Login.route) },
                onLogin = { navController.navigate(Screen.Login.route) },
            )
        }
        composable(Screen.Signup.route) {
            SignupScreen(authViewModel = authViewModel)
        }
        composable(Screen.Login.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onPhoneCodeSent = { navController.navigate(Screen.PhoneAuth.route) },
                onEmailLoginClick = { navController.navigate(Screen.EmailLogin.route) },
            )
        }
        composable(Screen.EmailLogin.route) {
            EmailLoginScreen(
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.PhoneAuth.route) {
            PhoneOTPScreen(
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun MainTabs(authViewModel: AuthViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val tabs = listOf(
        BottomTab(Screen.Dashboard.route, "Deliveries", Icons.Filled.LocalShipping),
        BottomTab(Screen.Earnings.route, "Earnings", Icons.Filled.AttachMoney),
        BottomTab(Screen.Profile.route, "Profile", Icons.Filled.AccountCircle),
    )

    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = BackgroundBlack,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = BackgroundDark) {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Orange,
                                selectedTextColor = Orange,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Orange.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(inner),
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onOpenChat = { orderId ->
                        navController.navigate(Screen.Chat.createRoute(orderId))
                    },
                )
            }
            composable(Screen.Earnings.route) { EarningsScreen() }
            composable(Screen.Profile.route) {
                CourierProfileScreen(
                    onLogout = { authViewModel.logout() },
                    onPayoutsClick = { navController.navigate(Screen.Payouts.route) },
                )
            }
            composable(Screen.Payouts.route) {
                PayoutsSetupScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    androidx.navigation.navArgument("orderId") { type = androidx.navigation.NavType.StringType },
                ),
            ) {
                ChatScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Navigator helper: exposed as a CompositionLocal-ish singleton so screens
 * deep in the graph (like the dashboard's active order card) can open chat
 * without plumbing the nav controller through every call site. Android
 * nav-compose doesn't give you a direct nav handle from a nested Composable,
 * so screens that need to open chat accept a lambda from their parent.
 */
object CourierNav {
    fun chatRoute(orderId: String) = Screen.Chat.createRoute(orderId)
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun LoadingShim() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = Orange)
    }
}

