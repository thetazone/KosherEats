package com.koshereats.consumer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koshereats.consumer.ui.screens.auth.LoginScreen
import com.koshereats.consumer.ui.screens.auth.RegisterScreen
import com.koshereats.consumer.ui.screens.cart.CartScreen
import com.koshereats.consumer.ui.screens.home.HomeScreen
import com.koshereats.consumer.ui.screens.orders.OrdersScreen
import com.koshereats.consumer.ui.screens.profile.ProfileScreen
import com.koshereats.consumer.ui.screens.restaurant.RestaurantDetailScreen
import com.koshereats.consumer.ui.theme.BackgroundBlack
import com.koshereats.consumer.ui.theme.BackgroundDark
import com.koshereats.consumer.ui.theme.Orange
import com.koshereats.consumer.ui.theme.TextMuted
import com.koshereats.consumer.ui.theme.TextWhite
import com.koshereats.consumer.ui.viewmodels.CartViewModel

@Composable
fun KosherEatsNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val cartViewModel: CartViewModel = hiltViewModel()

    val bottomNavRoutes = BottomNavItem.entries.map { it.route }
    val showBottomBar = currentDestination?.route in bottomNavRoutes

    Scaffold(
        containerColor = BackgroundBlack,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = BackgroundDark,
                    tonalElevation = 0.dp,
                ) {
                    BottomNavItem.entries.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
            },
            exitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
            },
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onRestaurantClick = { restaurantId ->
                        navController.navigate(Screen.Restaurant.createRoute(restaurantId))
                    },
                    onCartClick = {
                        navController.navigate(Screen.Cart.route)
                    },
                    cartViewModel = cartViewModel,
                )
            }

            composable(Screen.Search.route) {
                HomeScreen(
                    onRestaurantClick = { restaurantId ->
                        navController.navigate(Screen.Restaurant.createRoute(restaurantId))
                    },
                    onCartClick = {
                        navController.navigate(Screen.Cart.route)
                    },
                    cartViewModel = cartViewModel,
                    startWithSearch = true,
                )
            }

            composable(
                route = Screen.Restaurant.route,
                arguments = listOf(navArgument("restaurantId") { type = NavType.StringType }),
            ) {
                RestaurantDetailScreen(
                    onBackClick = { navController.popBackStack() },
                    onCartClick = { navController.navigate(Screen.Cart.route) },
                    cartViewModel = cartViewModel,
                )
            }

            composable(Screen.Cart.route) {
                CartScreen(
                    onBackClick = { navController.popBackStack() },
                    onOrderPlaced = {
                        navController.navigate(Screen.Orders.route) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                    cartViewModel = cartViewModel,
                )
            }

            composable(Screen.Orders.route) {
                OrdersScreen(
                    onOrderClick = { orderId ->
                        navController.navigate(Screen.Chat.createRoute(orderId))
                    },
                    onReorderClick = { restaurantId ->
                        navController.navigate(Screen.Restaurant.createRoute(restaurantId))
                    },
                )
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    androidx.navigation.navArgument("orderId") { type = androidx.navigation.NavType.StringType },
                ),
            ) {
                com.koshereats.consumer.ui.screens.chat.ChatScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onRegisterClick = {
                        navController.navigate(Screen.Register.route)
                    },
                )
            }

            composable(Screen.Register.route) {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onLoginClick = { navController.popBackStack() },
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onLoginClick = { navController.navigate(Screen.Login.route) },
                )
            }
        }
    }
}
