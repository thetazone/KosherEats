package com.greeneats.consumer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.greeneats.consumer.push.GreenEatsMessagingService
import com.greeneats.consumer.ui.navigation.GreenEatsNavHost
import com.greeneats.consumer.ui.theme.DarkGreenEatsColors
import com.greeneats.consumer.ui.theme.GreenEatsTheme
import com.greeneats.consumer.ui.theme.LightGreenEatsColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val _deepLinkIntent = MutableStateFlow<Intent?>(null)

    /** Observable deep-link intent for the NavHost to consume. */
    val deepLinkIntent: StateFlow<Intent?> = _deepLinkIntent.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure notification channels exist early
        GreenEatsMessagingService.ensureChannel(this)

        // Configure edge-to-edge with the correct scrim colors for both
        // status bar and navigation bar, matching the app's background.
        val darkScrim = DarkGreenEatsColors.backgroundBlack.toArgb()
        val lightScrim = LightGreenEatsColors.backgroundBlack.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(lightScrim, darkScrim),
            navigationBarStyle = SystemBarStyle.auto(lightScrim, darkScrim),
        )

        // Process the launch intent for deep links (e.g. from notification taps)
        handleDeepLink(intent)

        setContent {
            val isDark = isSystemInDarkTheme()

            // Keep the edge-to-edge bar styles in sync when the theme changes
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(lightScrim, darkScrim),
                    navigationBarStyle = SystemBarStyle.auto(lightScrim, darkScrim),
                )
                onDispose {}
            }

            GreenEatsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.greeneats.consumer.ui.theme.BackgroundBlack,
                ) {
                    GreenEatsNavHost()
                }
            }
        }
    }

    /**
     * Called when the activity receives a new intent while already running
     * (e.g. notification tapped while app is in foreground). FLAG_ACTIVITY_SINGLE_TOP
     * in the notification PendingIntent routes here instead of creating a new instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Parse a deep-link URI from the incoming intent and publish it so the
     * NavHost can route to the appropriate screen. Supported schemes:
     *   - koshereats://orders/{orderId}/tracking
     *   - koshereats://orders/{orderId}/chat
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        _deepLinkIntent.value = intent
    }

    /** Called by the NavHost after it has consumed the deep link. */
    fun consumeDeepLink() {
        _deepLinkIntent.value = null
    }
}
