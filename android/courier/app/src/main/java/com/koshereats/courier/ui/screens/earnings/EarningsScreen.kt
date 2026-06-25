package com.koshereats.courier.ui.screens.earnings

import com.koshereats.courier.util.isoLocalDate
import com.koshereats.courier.util.shortDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.koshereats.courier.data.models.formatPrice
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

sealed class EarningsUiState {
    object Loading : EarningsUiState()
    /**
     * @param history the most-recent deliveries (backend caps this list, so it
     *   MUST NOT be used to derive lifetime/all-time aggregates).
     * @param lifetimeDeliveries the courier's true all-time delivery count,
     *   sourced from the profile (`total_deliveries`), which the backend bumps
     *   on every delivery and is therefore not capped. Null if unavailable.
     */
    data class Success(
        val history: List<HistoryOrder>,
        val lifetimeDeliveries: Int?,
    ) : EarningsUiState()
    object Error : EarningsUiState()
}

@HiltViewModel
class EarningsViewModel @Inject constructor(
    private val repo: CourierRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EarningsUiState>(EarningsUiState.Loading)
    val uiState: StateFlow<EarningsUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun load() = viewModelScope.launch {
        val wasSuccess = _uiState.value is EarningsUiState.Success
        if (wasSuccess) {
            _isRefreshing.value = true
        } else {
            _uiState.value = EarningsUiState.Loading
        }
        val result = repo.listHistory()
        // The history list is capped by the backend, so pull the uncapped
        // lifetime delivery count from the profile separately. A profile fetch
        // failure is non-fatal — we just omit the all-time count rather than
        // showing a wrong (capped) number.
        val lifetimeDeliveries = repo.profile().getOrNull()?.totalDeliveries
        _isRefreshing.value = false
        if (result.isSuccess) {
            _uiState.value = EarningsUiState.Success(
                history = result.getOrDefault(emptyList()),
                lifetimeDeliveries = lifetimeDeliveries,
            )
        } else if (!wasSuccess) {
            _uiState.value = EarningsUiState.Error
        }
        // refresh failure while data is visible: keep existing history shown
    }
}

@Composable
fun EarningsScreen(vm: EarningsViewModel = hiltViewModel()) {
    val uiState by vm.uiState.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    when (val state = uiState) {
        is EarningsUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
        }
        is EarningsUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize().background(BackgroundBlack), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Failed to load earnings.", color = TextMuted)
                    Button(onClick = { vm.load() }) { Text("Retry") }
                }
            }
        }
        is EarningsUiState.Success -> {
            val history = state.history
            val today = LocalDate.now(ZoneId.systemDefault())
            val weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1L)
            val todayItems = history.filter { isoLocalDate(it.deliveredAt ?: "")?.equals(today) == true }
            val weekItems = history.filter {
                isoLocalDate(it.deliveredAt ?: "")?.let { d -> !d.isBefore(weekStart) } == true
            }
            // Today/week are safe to derive from the (capped) recent list — a
            // courier won't exceed the backend's history cap within a single
            // day or ISO week. All-time aggregates are NOT computed here: the
            // history list is capped, so summing it would silently undercount.
            val todayTotal = todayItems.sumOf { it.courierPayout }
            val weekTotal = weekItems.sumOf { it.courierPayout }
            val lifetimeDeliveries = state.lifetimeDeliveries

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundBlack)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Today", color = TextTertiary, fontSize = 12.sp)
                                Text(todayTotal.formatPrice(), color = Orange, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text("${todayItems.size} deliveries", color = TextSecondary, fontSize = 11.sp)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("This week", color = TextTertiary, fontSize = 12.sp)
                                Text(weekTotal.formatPrice(), color = Orange, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text("${weekItems.size} deliveries", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        if (lifetimeDeliveries != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text("$lifetimeDeliveries deliveries total", color = TextMuted, fontSize = 12.sp)
                            }
                        }
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
                                        val dateLabel = h.deliveredAt?.let { shortDateTime(it) }
                                        if (dateLabel != null) {
                                            Text(dateLabel, color = TextTertiary, fontSize = 11.sp)
                                        }
                                    }
                                    Text(
                                        h.courierPayout.formatPrice(),
                                        color = SuccessGreen,
                                    )
                                }
                                Divider(color = SurfaceDarkBorder)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (isRefreshing) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
                    }
                }
            }
        }
    }
}
