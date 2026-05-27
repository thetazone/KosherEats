package com.koshereats.seller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.koshereats.seller.ui.navigation.NavGraph
import com.koshereats.seller.ui.theme.KosherEatsSellerTheme
import com.koshereats.seller.ui.theme.BackgroundBlack
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var pendingOrderId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only read on a fresh start; config-change recreations restore nav state themselves.
        if (savedInstanceState == null) {
            pendingOrderId = intent.getStringExtra("order_id")
            intent.removeExtra("order_id")
        }

        setContent {
            KosherEatsSellerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundBlack
                ) {
                    NavGraph(
                        initialOrderId = pendingOrderId,
                        onOrderDeepLinkConsumed = { pendingOrderId = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newOrderId = intent.getStringExtra("order_id")
        if (newOrderId != null) {
            pendingOrderId = newOrderId
            // Clear so a config change after consumption can't re-fire navigation.
            intent.removeExtra("order_id")
        }
    }
}
