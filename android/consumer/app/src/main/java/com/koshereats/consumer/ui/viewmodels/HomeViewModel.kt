package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiPaging
import com.koshereats.consumer.data.models.CuisineType
import com.koshereats.consumer.data.models.KosherCertification
import com.koshereats.consumer.data.models.Restaurant
import com.koshereats.consumer.data.repository.Resource
import com.koshereats.consumer.data.repository.RestaurantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    // Emitting a new value here cancels any in-flight getRestaurants call via flatMapLatest.
    private data class LoadTrigger(
        val page: Int = 1,
        val cuisine: CuisineType? = null,
        val glattOnly: Boolean = false,
        val cholovYisroelOnly: Boolean = false,
        val pasYisroelOnly: Boolean = false,
        val certifications: Set<KosherCertification> = emptySet(),
    )

    private val loadTrigger = MutableStateFlow(LoadTrigger())

    init {
        viewModelScope.launch {
            loadTrigger
                .flatMapLatest { trigger ->
                    repository.getRestaurants(
                        page = trigger.page,
                        cuisine = trigger.cuisine?.name?.lowercase(),
                        isGlattKosher = if (trigger.glattOnly) true else null,
                        isCholovYisroel = if (trigger.cholovYisroelOnly) true else null,
                        isPasYisroel = if (trigger.pasYisroelOnly) true else null,
                        kosherCertification = trigger.certifications.firstOrNull()?.toApiString(),
                    ).map { trigger.page to it }
                }
                .collect { (page, result) ->
                    when (result) {
                        is Resource.Loading -> {
                            _uiState.update { it.copy(isLoading = true, error = null) }
                        }
                        is Resource.Success -> {
                            _uiState.update { state ->
                                val newItems = if (page == 1) result.data else state.allRestaurants + result.data
                                state.copy(
                                    allRestaurants = newItems,
                                    isLoading = false,
                                    isRefreshing = false,
                                    currentPage = page,
                                    hasMore = result.data.size >= ApiPaging.RESTAURANTS_PAGE_SIZE,
                                )
                            }
                        }
                        is Resource.Error -> {
                            _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.message, hasMore = true) }
                        }
                    }
                }
        }
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
        // Defensive cap: even if the server keeps reporting hasMore, stop after 100 pages
        // (≈2000 restaurants) to avoid runaway pagination on a buggy/recursive response.
        if (state.currentPage >= 100) return
        val nextPage = state.currentPage + 1
        _uiState.update { it.copy(isLoading = true) }
        loadTrigger.value = loadTrigger.value.copy(page = nextPage)
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
        loadTrigger.value = loadTrigger.value.copy(cuisine = cuisine, page = 1)
        loadSuggested()
    }

    fun toggleGlattFilter() {
        val newValue = !_uiState.value.filterGlattOnly
        _uiState.update { it.copy(filterGlattOnly = newValue) }
        loadTrigger.value = loadTrigger.value.copy(glattOnly = newValue, page = 1)
        loadSuggested()
    }

    fun toggleCholovYisroelFilter() {
        val newValue = !_uiState.value.filterCholovYisroelOnly
        _uiState.update { it.copy(filterCholovYisroelOnly = newValue) }
        loadTrigger.value = loadTrigger.value.copy(cholovYisroelOnly = newValue, page = 1)
        loadSuggested()
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
        loadTrigger.value = loadTrigger.value.copy(
            glattOnly = glattOnly,
            cholovYisroelOnly = cholovYisroelOnly,
            pasYisroelOnly = pasYisroelOnly,
            certifications = certifications,
            page = 1,
        )
        loadSuggested()
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refresh() {
        _uiState.update { it.copy(currentPage = 1, hasMore = true, isRefreshing = true, error = null) }
        loadTrigger.value = loadTrigger.value.copy(page = 1)
        loadSuggested()
    }
}
