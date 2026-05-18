package com.koshereats.consumer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.koshereats.consumer.ui.screens.auth.EmailLoginScreen
import com.koshereats.consumer.ui.screens.auth.LoginScreen
import com.koshereats.consumer.ui.screens.auth.PhoneAuthScreen
import com.koshereats.consumer.ui.screens.auth.PhonePromptScreen
import com.koshereats.consumer.ui.screens.auth.RegisterScreen
import com.koshereats.consumer.ui.screens.cart.CartScreen
import com.koshereats.consumer.ui.screens.checkout.CheckoutScreen
import com.koshereats.consumer.ui.screens.checkout.OrderConfirmationScreen
import com.koshereats.consumer.ui.screens.deals.DealsScreen
import com.koshereats.consumer.ui.screens.home.HomeScreen
import com.koshereats.consumer.ui.screens.map.NearbyMapScreen
import com.koshereats.consumer.ui.screens.orders.OrdersScreen
import com.koshereats.consumer.ui.screens.profile.EditProfileScreen
import com.koshereats.consumer.ui.screens.profile.PaymentMethodsScreen
import com.koshereats.consumer.ui.screens.profile.ProfileScreen
import com.koshereats.consumer.ui.screens.profile.SavedAddressesScreen
import com.koshereats.consumer.ui.screens.restaurant.RestaurantDetailScreen
import com.koshereats.consumer.ui.screens.tracking.OrderTrackingScreen
import com.koshereats.consumer.ui.theme.BackgroundBlack
import com.koshereats.consumer.ui.theme.BackgroundDark
import com.koshereats.consumer.ui.theme.Orange
import com.koshereats.consumer.ui.theme.TextMuted
import com.koshereats.consumer.ui.theme.TextTertiary
import com.koshereats.consumer.ui.theme.TextWhite
import com.koshereats.consumer.ui.viewmodels.AddressViewModel
import com.koshereats.consumer.ui.viewmodels.AuthViewModel
import com.koshereats.consumer.ui.viewmodels.CartViewModel
import com.koshereats.consumer.ui.viewmodels.HomeViewModel
import com.koshereats.consumer.ui.viewmodels.SessionState

internal object DeepLinkState {
    val pendingOrderId = MutableStateFlow<String?>(null)
}

@Composable
fun KosherEatsNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val cartViewModel: CartViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val addressViewModel: AddressViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    // Tracks which route the guest should return to after logging in.
    // When a guest tries a restricted action we stash the target here
    // so the login-success handler can send them back.
    // rememberSaveable survives process death (e.g. Stripe 3DS activity recreation).
    val pendingGuestReturn = rememberSaveable { mutableStateOf<String?>(null) }

    val pendingOrderId by DeepLinkState.pendingOrderId.collectAsStateWithLifecycle()
    // Re-fires when auth state changes so the ID is held until the user is authenticated.
    // Auth gate prevents a guest/unauthenticated deep-link from reaching OrderTracking and
    // triggering a 401 → forced-logout cycle.
    LaunchedEffect(pendingOrderId, authState.sessionState) {
        val id = pendingOrderId ?: return@LaunchedEffect
        if (authState.sessionState != SessionState.Authenticated) return@LaunchedEffect
        DeepLinkState.pendingOrderId.value = null
        navController.navigate(Screen.OrderTracking.createRoute(id)) {
            launchSingleTop = true
            popUpTo(Screen.Home.route)
        }
    }

    LaunchedEffect(Unit) {
        authViewModel.logoutEvent.collect {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Reload addresses whenever the user becomes authenticated so a guest→sign-in
    // transition surfaces their saved addresses without requiring an app restart.
    LaunchedEffect(authState.sessionState) {
        if (authState.sessionState == SessionState.Authenticated) {
            addressViewModel.loadAddresses()
        }
    }

    /** Navigate to login when a guest hits a restricted feature. */
    fun requireAuth(returnRoute: String) {
        pendingGuestReturn.value = returnRoute
        navController.navigate(Screen.Login.route)
    }

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
                val fromIdx = BottomNavItem.entries.indexOfFirst { it.route == initialState.destination.route }
                val toIdx = BottomNavItem.entries.indexOfFirst { it.route == targetState.destination.route }
                if (fromIdx >= 0 && toIdx >= 0) {
                    val dir = if (toIdx > fromIdx) AnimatedContentTransitionScope.SlideDirection.Start
                              else AnimatedContentTransitionScope.SlideDirection.End
                    slideIntoContainer(dir, tween(300))
                } else {
                    fadeIn(tween(300)) + scaleIn(initialScale = 0.96f, animationSpec = tween(300))
                }
            },
            exitTransition = {
                val fromIdx = BottomNavItem.entries.indexOfFirst { it.route == initialState.destination.route }
                val toIdx = BottomNavItem.entries.indexOfFirst { it.route == targetState.destination.route }
                if (fromIdx >= 0 && toIdx >= 0) {
                    val dir = if (toIdx > fromIdx) AnimatedContentTransitionScope.SlideDirection.Start
                              else AnimatedContentTransitionScope.SlideDirection.End
                    slideOutOfContainer(dir, tween(300))
                } else {
                    fadeOut(tween(150)) + scaleOut(targetScale = 0.96f, animationSpec = tween(150))
                }
            },
            popEnterTransition = {
                fadeIn(tween(300)) + scaleIn(initialScale = 0.96f, animationSpec = tween(300))
            },
            popExitTransition = {
                fadeOut(tween(150)) + scaleOut(targetScale = 0.96f, animationSpec = tween(150))
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
                    onRequireAuth = { requireAuth(Screen.Home.route) },
                    isLoggedIn = authState.sessionState == SessionState.Authenticated,
                    cartViewModel = cartViewModel,
                    addressViewModel = addressViewModel,
                    viewModel = homeViewModel,
                )
            }

            composable(Screen.Deals.route) {
                DealsScreen(
                    onDealClick = { deal ->
                        if (deal.hasLinkedItem) {
                            cartViewModel.applyDeal(deal)
                            cartViewModel.setPendingDealItem(deal)
                        } else {
                            cartViewModel.applyDeal(deal)
                        }
                        navController.navigate(Screen.Restaurant.createRoute(deal.restaurantId))
                    },
                )
            }

            composable(Screen.NearbyMap.route) {
                NearbyMapScreen(
                    onRestaurantClick = { restaurantId ->
                        navController.navigate(Screen.Restaurant.createRoute(restaurantId))
                    },
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
                    onCheckoutClick = {
                        if (authState.sessionState != SessionState.Authenticated) {
                            // Unauthenticated and guest users must sign in before checkout.
                            requireAuth(Screen.Cart.route)
                        } else {
                            navController.navigate(Screen.Checkout.route)
                        }
                    },
                    onViewStore = { restaurantId ->
                        navController.navigate(Screen.Restaurant.createRoute(restaurantId)) {
                            popUpTo(Screen.Cart.route) { inclusive = true }
                        }
                    },
                    cartViewModel = cartViewModel,
                )
            }

            composable(Screen.Checkout.route) {
                val cartState by cartViewModel.uiState.collectAsStateWithLifecycle()
                // Pass live values — CheckoutViewModel's _bootstrapped guard prevents
                // re-triggering mid-payment, and its SavedStateHandle recovers the cart
                // across process death (e.g. Stripe 3DS activity recreation).
                val cartItems = cartState.cart.items
                val restaurantId = cartState.cart.restaurantId
                val dealId = cartState.cart.appliedDeal?.id
                CheckoutScreen(
                    localCart = cartItems,
                    restaurantId = restaurantId,
                    appliedDealId = dealId,
                    onBack = { navController.popBackStack() },
                    onOrderPlaced = { order ->
                        cartViewModel.clearCartForRestaurant(restaurantId)
                        navController.navigate(Screen.OrderConfirmation.createRoute(order.id)) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                )
            }

            composable(
                route = Screen.OrderConfirmation.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
            ) { backStackEntry ->
                if (authState.sessionState != SessionState.Authenticated) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val orderId = backStackEntry.arguments?.getString("orderId")
                if (orderId.isNullOrEmpty()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                OrderConfirmationScreen(
                    orderId = orderId,
                    onDone = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onTrack = { id ->
                        navController.navigate(Screen.OrderTracking.createRoute(id)) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                )
            }

            composable(
                route = Screen.OrderTracking.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
            ) { backStackEntry ->
                if (authState.sessionState != SessionState.Authenticated) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val orderId = backStackEntry.arguments?.getString("orderId")
                if (orderId.isNullOrEmpty()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                OrderTrackingScreen(
                    orderId = orderId,
                    onBack = { navController.popBackStack() },
                    onChat = { id -> navController.navigate(Screen.Chat.createRoute(id)) },
                )
            }

            composable(Screen.Orders.route) {
                if (authState.sessionState != SessionState.Authenticated) {
                    GuestBlockedScreen(
                        title = "Sign in to view orders",
                        subtitle = "Your order history will be available after you sign in.",
                        onSignInClick = { requireAuth(Screen.Orders.route) },
                    )
                } else {
                    OrdersScreen(
                        onOrderClick = { orderId ->
                            navController.navigate(Screen.OrderTracking.createRoute(orderId))
                        },
                        onReorderClick = { restaurantId ->
                            navController.navigate(Screen.Restaurant.createRoute(restaurantId))
                        },
                    )
                }
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("orderId") { type = NavType.StringType },
                ),
            ) {
                if (authState.sessionState != SessionState.Authenticated) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                com.koshereats.consumer.ui.screens.chat.ChatScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        val returnRoute = pendingGuestReturn.value
                        pendingGuestReturn.value = null
                        val target = returnRoute ?: Screen.Home.route
                        navController.navigate(target) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onPhoneCodeSent = {
                        navController.navigate(Screen.PhoneAuth.route)
                    },
                    onEmailLoginClick = {
                        navController.navigate(Screen.EmailLogin.route)
                    },
                    onPhoneNeeded = {
                        navController.navigate(Screen.PhonePrompt.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onGuestContinue = {
                        pendingGuestReturn.value = null
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.EmailLogin.route) {
                EmailLoginScreen(
                    onLoginSuccess = {
                        val returnRoute = pendingGuestReturn.value
                        pendingGuestReturn.value = null
                        val target = returnRoute ?: Screen.Home.route
                        navController.navigate(target) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onRegisterClick = { navController.navigate(Screen.Register.route) },
                    onBack = { navController.popBackStack() },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.PhoneAuth.route) {
                PhoneAuthScreen(
                    onAuthSuccess = {
                        val returnRoute = pendingGuestReturn.value
                        pendingGuestReturn.value = null
                        val target = returnRoute ?: Screen.Home.route
                        navController.navigate(target) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onBack = {
                        // Going back to Welcome — keep otpSent state cleared so
                        // the Welcome page doesn't immediately re-navigate forward.
                        authViewModel.resetPhoneFlow()
                        navController.popBackStack()
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.Register.route) {
                RegisterScreen(
                    onRegisterSuccess = {
                        val returnRoute = pendingGuestReturn.value
                        pendingGuestReturn.value = null
                        if (returnRoute != null) {
                            navController.navigate(returnRoute) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    },
                    onLoginClick = { navController.popBackStack() },
                    onPhoneNeeded = {
                        navController.navigate(Screen.PhonePrompt.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onGuestContinue = {
                        pendingGuestReturn.value = null
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.PhonePrompt.route) {
                val returnRoute = pendingGuestReturn.value
                PhonePromptScreen(
                    onComplete = {
                        pendingGuestReturn.value = null
                        val target = returnRoute ?: Screen.Home.route
                        navController.navigate(target) {
                            popUpTo(Screen.PhonePrompt.route) { inclusive = true }
                        }
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onLoginClick = {
                        // Guest tapping sign-in on the profile tab
                        requireAuth(Screen.Profile.route)
                    },
                    onSignOutClick = {
                        authViewModel.logout()
                    },
                    onDeleteAccountClick = {
                        authViewModel.deleteAccount { _ -> }
                    },
                    onEditProfileClick = {
                        navController.navigate(Screen.EditProfile.route)
                    },
                    onSavedAddressesClick = {
                        navController.navigate(Screen.SavedAddresses.route)
                    },
                    onPaymentMethodsClick = {
                        navController.navigate(Screen.PaymentMethods.route)
                    },
                    onFavoritesClick = {
                        navController.navigate(Screen.Favorites.route)
                    },
                    onNotificationPreferencesClick = {
                        navController.navigate(Screen.NotificationPreferences.route)
                    },
                    onConnectedAccountsClick = {
                        navController.navigate(Screen.ConnectedAccounts.route)
                    },
                    viewModel = authViewModel,
                )
            }

            composable(Screen.EditProfile.route) {
                EditProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { firstName, lastName, phone ->
                        authViewModel.patchUser(firstName, lastName, phone)
                    },
                )
            }

            composable(Screen.SavedAddresses.route) {
                SavedAddressesScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = addressViewModel,
                )
            }

            composable(Screen.PaymentMethods.route) {
                PaymentMethodsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Favorites.route) {
                com.koshereats.consumer.ui.screens.profile.FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onRestaurantClick = { id ->
                        navController.navigate(Screen.Restaurant.createRoute(id))
                    },
                )
            }

            composable(Screen.NotificationPreferences.route) {
                com.koshereats.consumer.ui.screens.profile.NotificationPreferencesScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.ConnectedAccounts.route) {
                com.koshereats.consumer.ui.screens.profile.ConnectedAccountsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.Rating.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId").orEmpty()
                com.koshereats.consumer.ui.screens.orders.RatingScreen(
                    orderId = orderId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Placeholder screen shown to guest users when they navigate to a
 * restricted tab (Orders, Chat, etc.). Displays a centered message
 * and a sign-in button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestBlockedScreen(
    title: String,
    subtitle: String,
    onSignInClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack),
    ) {
        TopAppBar(
            title = {},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = TextTertiary,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onSignInClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(horizontal = 48.dp)
                        .fillMaxWidth(),
                ) {
                    Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
