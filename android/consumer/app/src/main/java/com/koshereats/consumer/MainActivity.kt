package com.koshereats.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.koshereats.consumer.ui.navigation.KosherEatsNavHost
import com.koshereats.consumer.ui.theme.KosherEatsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KosherEatsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.koshereats.consumer.ui.theme.BackgroundBlack
                ) {
                    KosherEatsNavHost()
                }
            }
        }
    }
}
