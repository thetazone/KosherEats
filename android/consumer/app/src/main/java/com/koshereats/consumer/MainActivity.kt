package com.koshereats.consumer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.koshereats.consumer.ui.navigation.KosherEatsNavHost
import com.koshereats.consumer.ui.theme.KosherEatsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Instance-scoped Compose state so onNewIntent can reactively push a new order ID
    // into the composition without a process-wide singleton.
    private val pendingOrderId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Only read on a fresh start; after process-death restore the system re-delivers
        // the original Intent extras (removeExtra only mutated the in-process Intent), which
        // would re-fire stale notification navigation. onNewIntent handles live deep links.
        if (savedInstanceState == null) {
            intent?.getStringExtra("order_id")?.let {
                pendingOrderId.value = it
                intent.removeExtra("order_id")
            }
        }
        setContent {
            KosherEatsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.koshereats.consumer.ui.theme.BackgroundBlack
                ) {
                    KosherEatsNavHost(
                        externalPendingOrderId = pendingOrderId.value,
                        onPendingOrderIdConsumed = { pendingOrderId.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("order_id")?.let {
            pendingOrderId.value = it
            intent.removeExtra("order_id")
        }
    }
}
