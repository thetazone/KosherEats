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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val allRestaurants: List<Restaurant> = emptyList(),
    val suggestedRestaurants: List<Restaurant> = emptyList(),
    val isSuggestedLoading: Boolean = false,
    val searchResults: List<Restaurant> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
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

    private companion object {
        const val PAGE_SIZE = 20
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var currentJob: Job? = null

    init {
        loadRestaurants()
        loadSuggested()
    }

    private fun loadSuggested() {
        viewModelScope.launch {
            repository.getSuggestedRestaurants(limit = 10).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isSuggestedLoading = true) }
                    }
                    is Resource.Success -> {
                        _uiState.update { it.copy(suggestedRestaurants = result.data, isSuggestedLoading = false) }
                    }
                    is Resource.Error -> {
                        // Non-fatal — the home screen still works without suggestions.
                        _uiState.update { it.copy(isSuggestedLoading = false) }
                    }
                }
            }
        }
    }

    fun loadRestaurants(page: Int = 1) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val state = _uiState.value
            repository.getRestaurants(
                page = page,
                cuisine = state.selectedCuisine?.name?.lowercase(),
                isGlattKosher = if (state.filterGlattOnly) true else null,
                isCholovYisroel = if (state.filterCholovYisroelOnly) true else null,
                isPasYisroel = if (state.filterPasYisroelOnly) true else null,
                kosherCertification = state.filterCertifications.firstOrNull()?.toApiString(),
            ).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    is Resource.Success -> {
                        _uiState.update { it ->
                            val newItems = if (page == 1) result.data else it.allRestaurants + result.data
                            it.copy(
                                allRestaurants = newItems,
                                isLoading = false,
                                isRefreshing = false,
                                currentPage = page,
                                hasMore = result.data.size >= PAGE_SIZE,
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.message, hasMore = true) }
                    }
                }
            }
        }
    }

    private fun KosherCertification.toApiString(): String = when (this) {
        KosherCertification.STAR_K -> "Star-K"
        KosherCertification.KOF_K -> "Kof-K"
        KosherCertification.BADATZ -> "Badatz"
        KosherCertification.CRC -> "CRC"
        else -> this.name
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        val nextPage = state.currentPage + 1
        _uiState.update { it.copy(isLoading = true) }
        loadRestaurants(nextPage)
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
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

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        searchJob?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refresh() {
        _uiState.update { it.copy(currentPage = 1, hasMore = true, isRefreshing = true, error = null) }
        loadRestaurants(page = 1)
        loadSuggested()
    }
}
