package com.koshereats.seller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.koshereats.seller.ui.navigation.NavGraph
import com.koshereats.seller.ui.theme.KosherEatsSellerTheme
import com.koshereats.seller.ui.theme.BackgroundBlack
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KosherEatsSellerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundBlack
                ) {
                    NavGraph()
                }
            }
        }
    }
}
