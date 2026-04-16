package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.models.CuisineType
import com.koshereats.consumer.data.models.KosherCertification
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

data class HomeUiState(
    val featuredRestaurants: List<Restaurant> = emptyList(),
    val allRestaurants: List<Restaurant> = emptyList(),
    val searchResults: List<Restaurant> = emptyList(),
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val selectedCuisine: CuisineType? = null,
    val filterGlattOnly: Boolean = false,
    val filterCholovYisroelOnly: Boolean = false,
    val filterPasYisroelOnly: Boolean = false,
    val filterCertifications: Set<KosherCertification> = emptySet(),
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadFeaturedRestaurants()
        loadRestaurants()
    }

    fun loadFeaturedRestaurants() {
        viewModelScope.launch {
            repository.getFeaturedRestaurants().collect { result ->
                when (result) {
                    is Resource.Loading -> {}
                    is Resource.Success -> {
                        _uiState.update { it.copy(featuredRestaurants = result.data) }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(error = result.message) }
                    }
                }
            }
        }
    }

    fun loadRestaurants(page: Int = 1) {
        viewModelScope.launch {
            repository.getRestaurants(
                page = page,
                cuisine = _uiState.value.selectedCuisine?.name?.lowercase(),
                isGlattKosher = if (_uiState.value.filterGlattOnly) true else null,
                isCholovYisroel = if (_uiState.value.filterCholovYisroelOnly) true else null,
                isPasYisroel = if (_uiState.value.filterPasYisroelOnly) true else null,
            ).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    is Resource.Success -> {
                        _uiState.update { state ->
                            val filtered = if (state.filterCertifications.isEmpty()) {
                                result.data
                            } else {
                                result.data.filter { it.kosherCertification in state.filterCertifications }
                            }
                            val newItems = if (page == 1) {
                                filtered
                            } else {
                                state.allRestaurants + filtered
                            }
                            // Backend caps at 50 per call and doesn't expose a cursor;
                            // treat a short page as "that was the last one".
                            state.copy(
                                allRestaurants = newItems,
                                isLoading = false,
                                currentPage = page,
                                hasMore = result.data.size >= 50,
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.isLoading && state.hasMore) {
            loadRestaurants(state.currentPage + 1)
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        viewModelScope.launch {
            repository.searchRestaurants(query).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isSearching = true) }
                    }
                    is Resource.Success -> {
                        _uiState.update { it.copy(searchResults = result.data, isSearching = false) }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(isSearching = false, error = result.message) }
                    }
                }
            }
        }
    }

    fun selectCuisine(cuisine: CuisineType?) {
        _uiState.update { it.copy(selectedCuisine = cuisine) }
        loadRestaurants(page = 1)
    }

    fun toggleGlattFilter() {
        _uiState.update { it.copy(filterGlattOnly = !it.filterGlattOnly) }
        loadRestaurants(page = 1)
    }

    fun toggleCholovYisroelFilter() {
        _uiState.update { it.copy(filterCholovYisroelOnly = !it.filterCholovYisroelOnly) }
        loadRestaurants(page = 1)
    }

    fun applyKosherFilters(
        glattOnly: Boolean,
        cholovYisroelOnly: Boolean,
        pasYisroelOnly: Boolean,
        certifications: Set<KosherCertification>,
    ) {
        _uiState.update {
            it.copy(
                filterGlattOnly = glattOnly,
                filterCholovYisroelOnly = cholovYisroelOnly,
                filterPasYisroelOnly = pasYisroelOnly,
                filterCertifications = certifications,
            )
        }
        loadRestaurants(page = 1)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refresh() {
        _uiState.update { it.copy(currentPage = 1, hasMore = true) }
        loadFeaturedRestaurants()
        loadRestaurants(page = 1)
    }
}
