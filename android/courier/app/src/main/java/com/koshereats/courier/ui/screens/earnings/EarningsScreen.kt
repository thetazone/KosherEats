package com.koshereats.courier.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.courier.data.models.HistoryOrder
import com.koshereats.courier.data.repository.CourierRepository
import com.koshereats.courier.ui.theme.BackgroundBlack
import com.koshereats.courier.ui.theme.Orange
import com.koshereats.courier.ui.theme.SuccessGreen
import com.koshereats.courier.ui.theme.SurfaceDark
import com.koshereats.courier.ui.theme.SurfaceDarkBorder
import com.koshereats.courier.ui.theme.TextMuted
import com.koshereats.courier.ui.theme.TextSecondary
import com.koshereats.courier.ui.theme.TextTertiary
import com.koshereats.courier.ui.theme.TextWhite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EarningsViewModel @Inject constructor(
    private val repo: CourierRepository,
) : ViewModel() {
    private val _history = MutableStateFlow<List<HistoryOrder>>(emptyList())
    val history: StateFlow<List<HistoryOrder>> = _history.asStateFlow()

    fun load() = viewModelScope.launch {
        _history.value = repo.listHistory().getOrNull() ?: emptyList()
    }
}

@Composable
fun EarningsScreen(vm: EarningsViewModel = hiltViewModel()) {
    val history by vm.history.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    val total = history.sumOf { it.deliveryFee + it.courierTip }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Total earned", color = TextTertiary, fontSize = 12.sp)
            Text("$${"%.2f".format(total / 100.0)}", color = Orange, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("${history.size} deliveries", color = TextSecondary, fontSize = 12.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Recent deliveries", color = TextWhite, fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) {
                Text("No completed deliveries yet.", color = TextMuted, fontSize = 13.sp)
            } else {
                history.forEach { h ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(h.restaurantName, color = TextWhite)
                            Text(h.deliveredAt ?: "", color = TextTertiary, fontSize = 11.sp)
                        }
                        Text(
                            "$${"%.2f".format((h.deliveryFee + h.courierTip) / 100.0)}",
                            color = SuccessGreen,
                        )
                    }
                    Divider(color = SurfaceDarkBorder)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
