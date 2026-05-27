package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.Restaurant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val isLoading: Boolean = false,
    val restaurants: List<Restaurant> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val error: String? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val listResp = api.getFavorites()
                val idsResp = api.getFavoriteIds()
                if (listResp.isSuccessful && idsResp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            restaurants = listResp.body().orEmpty(),
                            favoriteIds = idsResp.body().orEmpty().toSet(),
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load favorites") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun toggleFavorite(restaurantId: String) {
        val current = _uiState.value.favoriteIds
        val isFavorite = restaurantId in current
        // Optimistic update
        _uiState.update {
            it.copy(favoriteIds = if (isFavorite) current - restaurantId else current + restaurantId)
        }
        viewModelScope.launch {
            var succeeded = false
            try {
                val resp = if (isFavorite) api.removeFavorite(restaurantId)
                else api.addFavorite(restaurantId)
                succeeded = resp.isSuccessful
                if (!resp.isSuccessful) {
                    // Roll back favoriteIds only — list mutations are gated on success.
                    _uiState.update { it.copy(favoriteIds = current) }
                }
            } catch (e: Exception) {
                android.util.Log.w("FavoritesViewModel", "toggleFavorite($restaurantId) failed", e)
                _uiState.update { it.copy(favoriteIds = current) }
            }
            // Only mutate the visible list when the server agreed with the toggle.
            if (succeeded) {
                if (!isFavorite) {
                    load()
                } else {
                    _uiState.update { state ->
                        state.copy(restaurants = state.restaurants.filterNot { r -> r.id == restaurantId })
                    }
                }
            }
        }
    }
}
