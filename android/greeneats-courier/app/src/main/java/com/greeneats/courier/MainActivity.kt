package com.greeneats.courier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.greeneats.courier.ui.navigation.CourierNavHost
import com.greeneats.courier.ui.theme.BackgroundBlack
import com.greeneats.courier.ui.theme.GreenEatsCourierTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GreenEatsCourierTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BackgroundBlack) {
                    CourierNavHost()
                }
            }
        }
    }
}
