package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.Restaurant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
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

    companion object {
        const val ERROR_LOAD_FAVORITES = "Couldn't load favorites"
    }

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
                    _uiState.update { it.copy(isLoading = false, error = ERROR_LOAD_FAVORITES) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                if (!succeeded) {
                    // Roll back
                    _uiState.update { it.copy(favoriteIds = current) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(favoriteIds = current) }
                return@launch
            }
            if (!isFavorite && succeeded) {
                // We just added — refresh the list view
                load()
            } else if (isFavorite && succeeded) {
                _uiState.update { state ->
                    state.copy(restaurants = state.restaurants.filterNot { r -> r.id == restaurantId })
                }
            }
        }
    }
}
