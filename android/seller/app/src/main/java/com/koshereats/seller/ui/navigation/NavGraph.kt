package com.koshereats.seller.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koshereats.seller.R
import com.koshereats.seller.ui.screens.auth.PhoneLoginScreen
import com.koshereats.seller.ui.screens.auth.SellerLoginScreen
import com.koshereats.seller.ui.screens.dashboard.DashboardScreen
import com.koshereats.seller.ui.screens.deals.CreateDealScreen
import com.koshereats.seller.ui.screens.deals.DealsScreen
import com.koshereats.seller.ui.screens.menu.MenuItemFormScreen
import com.koshereats.seller.ui.screens.menu.MenuManagementScreen
import com.koshereats.seller.ui.screens.onboarding.OnboardingScreen
import com.koshereats.seller.ui.screens.orders.SellerOrderDetailScreen
import com.koshereats.seller.ui.screens.orders.SellerOrdersScreen
import com.koshereats.seller.ui.screens.settings.IntegrationsScreen
import com.koshereats.seller.ui.screens.settings.RestaurantSettingsScreen
import com.koshereats.seller.ui.theme.BackgroundBlack
import com.koshereats.seller.ui.theme.BackgroundDark
import com.koshereats.seller.ui.theme.Orange
import com.koshereats.seller.ui.theme.TextMuted
import com.koshereats.seller.ui.theme.TextWhite
import com.koshereats.seller.ui.viewmodels.AuthViewModel

@Composable
fun NavGraph(
    initialOrderId: String? = null,
    onOrderDeepLinkConsumed: () -> Unit = {},
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = Screen.bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    // While the auth check runs, or while a logged-in seller's restaurants are
    // still loading (a network call with a 30s read timeout), show a branded
    // splash instead of an empty Surface — otherwise cold start is a black screen.
    if (authState.isLoading || (authState.isLoggedIn && authState.hasRestaurants == null)) {
        SplashLoading()
        return
    }

    val startDestination = when {
        !authState.isLoggedIn -> Screen.Login.route
        authState.hasRestaurants == false -> Screen.Onboarding.route
        else -> Screen.Dashboard.route
    }

    LaunchedEffect(initialOrderId, authState.isLoggedIn, authState.hasRestaurants) {
        val orderId = initialOrderId ?: return@LaunchedEffect
        if (!authState.isLoggedIn || authState.hasRestaurants != true) return@LaunchedEffect
        if (orderId.isBlank() || !orderId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            android.util.Log.w("NavGraph", "Dropping deep-link with invalid order_id='$orderId'")
            onOrderDeepLinkConsumed()
            return@LaunchedEffect
        }
        navController.navigate(Screen.OrderDetail.createRoute(orderId)) {
            launchSingleTop = true
        }
        onOrderDeepLinkConsumed()
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
        if (current == Screen.Login.route || current == Screen.PhoneLogin.route) {
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
                                    // Don't restore orders_graph state: it may contain an
                                    // OrderDetail entry, which would skip the list on re-tap.
                                    restoreState = screen.route != Screen.Orders.route
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
                    onLoginSuccess = {},
                    onPhoneLoginClick = {
                        navController.navigate(Screen.PhoneLogin.route)
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.PhoneLogin.route) {
                PhoneLoginScreen(
                    onLoginSuccess = {},
                    onBack = { navController.popBackStack() },
                    viewModel = authViewModel,
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
                    onSignOut = {
                        // LaunchedEffect at top of NavGraph handles the redirect to Login
                        // once authState.isLoggedIn flips false.
                        authViewModel.logout()
                    },
                )
            }

            // Dashboard
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onOrderClick = { orderId ->
                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                    },
                    onViewAllOrders = {
                        navController.navigate(Screen.Orders.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    authViewModel = authViewModel,
                )
            }

            // Orders + Order Detail share one OrdersViewModel via nested-graph scope.
            navigation(startDestination = Screen.Orders.route, route = "orders_graph") {
                composable(Screen.Orders.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry("orders_graph")
                    }
                    SellerOrdersScreen(
                        viewModel = hiltViewModel(parentEntry),
                        onOrderClick = { orderId ->
                            navController.navigate(Screen.OrderDetail.createRoute(orderId))
                        },
                    )
                }

                composable(
                    route = Screen.OrderDetail.route,
                    arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val orderId = backStackEntry.arguments?.getString("orderId") ?: return@composable
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry("orders_graph")
                    }
                    SellerOrderDetailScreen(
                        orderId = orderId,
                        viewModel = hiltViewModel(parentEntry),
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Menu + Menu Item Form share one MenuViewModel via nested-graph scope, so
            // the form's post-save loadMenuItems() refreshes the list the seller returns to.
            navigation(startDestination = Screen.Menu.route, route = "menu_graph") {
                composable(Screen.Menu.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry("menu_graph")
                    }
                    MenuManagementScreen(
                        viewModel = hiltViewModel(parentEntry),
                        onAddItem = {
                            navController.navigate(Screen.MenuItemForm.createRoute())
                        },
                        onEditItem = { itemId ->
                            navController.navigate(Screen.MenuItemForm.createRoute(itemId))
                        },
                    )
                }

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
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry("menu_graph")
                    }
                    MenuItemFormScreen(
                        itemId = itemId,
                        viewModel = hiltViewModel(parentEntry),
                        onBack = { navController.popBackStack() },
                        onSaved = { navController.popBackStack() },
                    )
                }
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
                    onIntegrations = {
                        navController.navigate(Screen.Integrations.route)
                    },
                    authViewModel = authViewModel,
                )
            }

            composable(Screen.Integrations.route) {
                IntegrationsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun SplashLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.auth_brand_name),
            style = MaterialTheme.typography.headlineLarge,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator(color = Orange)
    }
}
