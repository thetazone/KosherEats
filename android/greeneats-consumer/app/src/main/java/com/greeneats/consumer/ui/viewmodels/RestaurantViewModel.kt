package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.models.Deal
import com.greeneats.consumer.data.models.MenuCategory
import com.greeneats.consumer.data.models.Restaurant
import com.greeneats.consumer.data.repository.Resource
import com.greeneats.consumer.data.repository.RestaurantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RestaurantUiState(
    val restaurant: Restaurant? = null,
    val menuCategories: List<MenuCategory> = emptyList(),
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
                    is Resource.Loading -> {}
                    is Resource.Success -> {
                        _uiState.update { it.copy(menuCategories = result.data) }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(error = result.message) }
                    }
                }
            }
        }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
