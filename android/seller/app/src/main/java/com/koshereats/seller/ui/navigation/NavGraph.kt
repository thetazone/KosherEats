package com.koshereats.seller.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koshereats.seller.ui.screens.auth.SellerLoginScreen
import com.koshereats.seller.ui.screens.dashboard.DashboardScreen
import com.koshereats.seller.ui.screens.deals.CreateDealScreen
import com.koshereats.seller.ui.screens.deals.DealsScreen
import com.koshereats.seller.ui.screens.menu.MenuItemFormScreen
import com.koshereats.seller.ui.screens.menu.MenuManagementScreen
import com.koshereats.seller.ui.screens.onboarding.OnboardingScreen
import com.koshereats.seller.ui.screens.orders.SellerOrderDetailScreen
import com.koshereats.seller.ui.screens.orders.SellerOrdersScreen
import com.koshereats.seller.ui.screens.settings.RestaurantSettingsScreen
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.BackgroundDark
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.AuthViewModel

@Composable
fun NavGraph() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.state.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = Screen.bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    if (authState.isLoading) {
        return
    }

    if (authState.isLoggedIn && authState.hasRestaurants == null) {
        return
    }

    val startDestination = when {
        !authState.isLoggedIn -> Screen.Login.route
        authState.hasRestaurants == false -> Screen.Onboarding.route
        else -> Screen.Dashboard.route
    }

    LaunchedEffect(authState.isLoggedIn) {
        if (!authState.isLoggedIn) {
            val current = navController.currentDestination?.route
            if (current != null && current != Screen.Login.route) {
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(authState.isLoggedIn, authState.hasRestaurants) {
        if (!authState.isLoggedIn) return@LaunchedEffect
        val hasRestaurants = authState.hasRestaurants ?: return@LaunchedEffect
        val current = navController.currentDestination?.route
        if (current == Screen.Login.route) {
            val target = if (hasRestaurants) Screen.Dashboard.route else Screen.Onboarding.route
            navController.navigate(target) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    Scaffold(
        containerColor = BackgroundBlack,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                NavigationBar(
                    containerColor = BackgroundDark,
                    contentColor = TextWhite,
                    tonalElevation = 0.dp,
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon!! else screen.unselectedIcon!!,
                                    contentDescription = screen.title,
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            // Auth
            composable(Screen.Login.route) {
                SellerLoginScreen(
                    onLoginSuccess = {
                    },
                )
            }

            // Onboarding
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onComplete = {
                        authViewModel.refreshRestaurants()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    },
                )
            }

            // Dashboard
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onOrderClick = { orderId ->
                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                    },
                )
            }

            // Orders
            composable(Screen.Orders.route) {
                SellerOrdersScreen(
                    onOrderClick = { orderId ->
                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                    },
                )
            }

            // Order Detail
            composable(
                route = Screen.OrderDetail.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: return@composable
                SellerOrderDetailScreen(
                    orderId = orderId,
                    onBack = { navController.popBackStack() },
                )
            }

            // Menu
            composable(Screen.Menu.route) {
                MenuManagementScreen(
                    onAddItem = {
                        navController.navigate(Screen.MenuItemForm.createRoute())
                    },
                    onEditItem = { itemId ->
                        navController.navigate(Screen.MenuItemForm.createRoute(itemId))
                    },
                )
            }

            // Menu Item Form
            composable(
                route = Screen.MenuItemForm.route,
                arguments = listOf(
                    navArgument("itemId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getString("itemId")
                MenuItemFormScreen(
                    itemId = itemId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            // Deals
            composable(Screen.Deals.route) {
                DealsScreen(
                    onCreateDeal = {
                        navController.navigate(Screen.CreateDeal.route)
                    },
                )
            }

            // Create Deal
            composable(Screen.CreateDeal.route) {
                CreateDealScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { navController.popBackStack() },
                )
            }

            // Settings
            composable(Screen.Settings.route) {
                RestaurantSettingsScreen(
                    onLogout = {
                        authViewModel.logout()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
