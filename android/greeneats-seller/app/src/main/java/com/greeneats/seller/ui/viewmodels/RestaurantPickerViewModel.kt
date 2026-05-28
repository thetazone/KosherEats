package com.greeneats.seller.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.seller.data.SelectedRestaurant
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.Restaurant
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RestaurantPickerState(
    val restaurants: List<Restaurant> = emptyList(),
    val selectedId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads the seller's owned restaurants for the picker sheet and persists
 * the user's selection back to DataStore via `SelectedRestaurant`. A separate
 * VM from DashboardViewModel because the sheet lives above the main nav
 * graph and shouldn't share state with the dashboard.
 */
@HiltViewModel
class RestaurantPickerViewModel @Inject constructor(
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(RestaurantPickerState())
    val state: StateFlow<RestaurantPickerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.listRestaurants()
                if (!response.isSuccessful) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load restaurants (HTTP ${response.code()})",
                    )
                    return@launch
                }
                val list = response.body().orEmpty()
                val current = SelectedRestaurant.flow(context).first()
                // First-launch default: if nothing is set and the seller owns
                // at least one restaurant, pin it. Matches iOS's load() path.
                val resolved = current ?: list.firstOrNull()?.id
                if (current == null && resolved != null) {
                    SelectedRestaurant.set(context, resolved)
                }
                _state.value = RestaurantPickerState(
                    restaurants = list,
                    selectedId = resolved,
                    isLoading = false,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Couldn't load restaurants",
                )
            }
        }
    }

    fun select(restaurantId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            SelectedRestaurant.set(context, restaurantId)
            // Wait until the DataStore flow reflects the new ID so that any
            // runBlocking { flow.first() } on the OkHttp interceptor thread
            // sees the updated value before the dashboard reload fires.
            SelectedRestaurant.flow(context).first { it == restaurantId }
            _state.value = _state.value.copy(selectedId = restaurantId)
            onDone()
        }
    }
}
