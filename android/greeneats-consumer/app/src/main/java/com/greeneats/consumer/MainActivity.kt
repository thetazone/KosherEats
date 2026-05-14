package com.greeneats.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.greeneats.consumer.ui.navigation.GreenEatsNavHost
import com.greeneats.consumer.ui.theme.GreenEatsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GreenEatsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.greeneats.consumer.ui.theme.BackgroundBlack
                ) {
                    GreenEatsNavHost()
                }
            }
        }
    }
}
