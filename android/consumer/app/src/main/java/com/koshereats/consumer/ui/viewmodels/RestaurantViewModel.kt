package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.models.Deal
import com.koshereats.consumer.data.models.MenuCategory
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.data.repository.Resource
import com.koshereats.consumer.data.repository.RestaurantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loading status for the menu, tracked independently of the restaurant fetch so a
 * menu failure (or a menu still in flight) is never misrendered as a successful but
 * empty menu. Driven by [RestaurantViewModel.loadMenu].
 */
enum class MenuLoadState { Loading, Loaded, Error }

data class RestaurantUiState(
    val restaurant: Restaurant? = null,
    val menuCategories: List<MenuCategory> = emptyList(),
    val menuState: MenuLoadState = MenuLoadState.Loading,
    val menuError: String? = null,
    val restaurantDeals: List<Deal> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RestaurantViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val restaurantId: String? = savedStateHandle["restaurantId"]

    private val _uiState = MutableStateFlow(RestaurantUiState())
    val uiState: StateFlow<RestaurantUiState> = _uiState.asStateFlow()

    init {
        if (restaurantId.isNullOrEmpty()) {
            _uiState.update { it.copy(error = "Invalid restaurant.", isLoading = false) }
        } else {
            loadRestaurant(restaurantId)
            loadMenu(restaurantId)
            loadDeals(restaurantId)
        }
    }

    private fun loadRestaurant(restaurantId: String) {
        viewModelScope.launch {
            repository.getRestaurant(restaurantId).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                    is Resource.Success -> {
                        _uiState.update { it.copy(restaurant = result.data, isLoading = false) }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(error = result.message, isLoading = false) }
                    }
                }
            }
        }
    }

    private fun loadMenu(restaurantId: String) {
        viewModelScope.launch {
            repository.getRestaurantMenu(restaurantId).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update {
                            it.copy(menuState = MenuLoadState.Loading, menuError = null)
                        }
                    }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                menuCategories = result.data,
                                menuState = MenuLoadState.Loaded,
                                menuError = null,
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(menuState = MenuLoadState.Error, menuError = result.message)
                        }
                    }
                }
            }
        }
    }

    /** Re-attempt the menu fetch after a failure; wired to the inline Retry action. */
    fun retryMenu() {
        val id = restaurantId
        if (id.isNullOrEmpty()) return
        loadMenu(id)
    }

    private fun loadDeals(restaurantId: String) {
        viewModelScope.launch {
            repository.getRestaurantDeals(restaurantId).collect { result ->
                when (result) {
                    is Resource.Loading -> {}
                    is Resource.Success -> {
                        _uiState.update { it.copy(restaurantDeals = result.data) }
                    }
                    is Resource.Error -> {}
                }
            }
        }
    }

    fun selectCategory(index: Int) {
        _uiState.update { it.copy(selectedCategoryIndex = index) }
    }

    /**
     * Toggle the signed-in user's "Request restaurant" state on a preview
     * listing (tap on = request, tap again = retract). Optimistic flip,
     * reconciled with the server's authoritative {requested, request_count}
     * response and reverted on failure. No-op for orderable restaurants —
     * the endpoint 400s on live listings. Callers must auth-gate: guests are
     * routed to sign-in instead (the endpoint requires a token).
     */
    fun toggleRequest() {
        val current = _uiState.value.restaurant ?: return
        if (current.orderable) return
        val optimisticRequested = !current.requestedByMe
        val optimisticCount =
            (current.requestCount + if (optimisticRequested) 1 else -1).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                restaurant = it.restaurant?.copy(
                    requestedByMe = optimisticRequested,
                    requestCount = optimisticCount,
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repository.toggleRestaurantRequest(current.id)) {
                is Resource.Success -> _uiState.update {
                    it.copy(
                        restaurant = it.restaurant?.copy(
                            requestedByMe = result.data.requested,
                            requestCount = result.data.requestCount,
                        ),
                    )
                }
                is Resource.Error -> _uiState.update {
                    // Revert to the pre-toggle snapshot.
                    it.copy(
                        restaurant = it.restaurant?.copy(
                            requestedByMe = current.requestedByMe,
                            requestCount = current.requestCount,
                        ),
                    )
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
