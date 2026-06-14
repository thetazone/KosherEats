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
    // The list the feed actually renders. This is the raw server list with the
    // active cuisine/kosher filters applied client-side: the backend ignores all
    // filter query params and always returns the same set, so filtering must
    // happen here for the feed to be truthful (mirrors iOS
    // RestaurantStore.filteredRestaurants). HomeScreen renders this field
    // directly, so it MUST hold the post-filter list — not the raw set.
    val allRestaurants: List<Restaurant> = emptyList(),
    // Raw, unfiltered list as returned by the server. Backs client-side filtering
    // and pagination dedup, and is the correct source for KosherFilterSheet's live
    // preview counts (which must count over the full population, not the already
    // filtered feed).
    val rawRestaurants: List<Restaurant> = emptyList(),
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
    private var loadMoreJob: Job? = null

    // Emitting a new value here cancels any in-flight getRestaurants call via flatMapLatest.
    // `generation` is a monotonic nonce bumped on every (re)load so structurally
    // identical triggers (e.g. pull-to-refresh on the default page-1 feed) still
    // emit — MutableStateFlow conflates equal values, which would otherwise leave
    // the refresh spinner stuck and skip the refetch.
    private data class LoadTrigger(
        val page: Int = 1,
        val cuisine: CuisineType? = null,
        val glattOnly: Boolean = false,
        val cholovYisroelOnly: Boolean = false,
        val pasYisroelOnly: Boolean = false,
        val certifications: Set<KosherCertification> = emptySet(),
        val generation: Long = 0,
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
                                // Dedupe by id against the RAW list: the backend currently ignores
                                // page/per_page and returns the same set on every page, so a naive
                                // append would add duplicate restaurants and crash the LazyColumn
                                // (which keys by id).
                                val merged = if (page == 1) {
                                    result.data.distinctBy { it.id }
                                } else {
                                    (state.rawRestaurants + result.data).distinctBy { it.id }
                                }
                                // Only consider there to be more pages if this page actually grew
                                // the list AND came back full. If a page brought no new ids
                                // (server didn't paginate), stop — otherwise loadMore() would loop.
                                val grew = merged.size > state.rawRestaurants.size || page == 1
                                val hasMore = grew &&
                                    result.data.size >= ApiPaging.RESTAURANTS_PAGE_SIZE
                                state.copy(
                                    rawRestaurants = merged,
                                    // The feed renders allRestaurants, so it must be the filtered view.
                                    allRestaurants = applyFilters(merged, state),
                                    isLoading = false,
                                    isRefreshing = false,
                                    currentPage = page,
                                    hasMore = hasMore,
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

    // Client-side cuisine + kosher filtering. The backend ignores all filter
    // query params and always returns the same set, so the UI must filter the
    // fetched list itself to be truthful. AND-combined, mirroring iOS
    // RestaurantStore.filteredRestaurants.
    private fun applyFilters(source: List<Restaurant>, state: HomeUiState): List<Restaurant> =
        source.filter { r ->
            val cuisineOk = state.selectedCuisine == null ||
                r.cuisineTypes.contains(state.selectedCuisine)
            val glattOk = !state.filterGlattOnly || r.isGlattKosher
            val cholovOk = !state.filterCholovYisroelOnly || r.isCholovYisroel
            val pasOk = !state.filterPasYisroelOnly || r.isPasYisroel
            val certOk = state.filterCertifications.isEmpty() ||
                r.kosherCertification in state.filterCertifications
            cuisineOk && glattOk && cholovOk && pasOk && certOk
        }

    private fun KosherCertification.toApiString(): String = when (this) {
        KosherCertification.STAR_K -> "Star-K"
        KosherCertification.KOF_K -> "Kof-K"
        KosherCertification.BADATZ -> "Badatz"
        KosherCertification.CRC -> "CRC"
        else -> this.name
    }

    fun loadMore() {
        if (loadMoreJob?.isActive == true) return
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        // Defensive cap: even if the server keeps reporting hasMore, stop after 100 pages
        // (≈2000 restaurants) to avoid runaway pagination on a buggy/recursive response.
        if (state.currentPage >= 100) return
        val nextPage = state.currentPage + 1
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadTrigger.value = loadTrigger.value.copy(
                page = nextPage,
                generation = loadTrigger.value.generation + 1,
            )
        }
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
        // Re-filter the already-fetched list immediately so the feed updates
        // even before the (no-op, server-ignores-filters) refetch resolves.
        _uiState.update {
            val next = it.copy(selectedCuisine = cuisine)
            next.copy(allRestaurants = applyFilters(next.rawRestaurants, next))
        }
        loadTrigger.value = loadTrigger.value.copy(
            cuisine = cuisine,
            page = 1,
            generation = loadTrigger.value.generation + 1,
        )
        loadSuggested()
    }

    fun toggleGlattFilter() {
        val newValue = !_uiState.value.filterGlattOnly
        _uiState.update {
            val next = it.copy(filterGlattOnly = newValue)
            next.copy(allRestaurants = applyFilters(next.rawRestaurants, next))
        }
        loadTrigger.value = loadTrigger.value.copy(
            glattOnly = newValue,
            page = 1,
            generation = loadTrigger.value.generation + 1,
        )
        loadSuggested()
    }

    fun toggleCholovYisroelFilter() {
        val newValue = !_uiState.value.filterCholovYisroelOnly
        _uiState.update {
            val next = it.copy(filterCholovYisroelOnly = newValue)
            next.copy(allRestaurants = applyFilters(next.rawRestaurants, next))
        }
        loadTrigger.value = loadTrigger.value.copy(
            cholovYisroelOnly = newValue,
            page = 1,
            generation = loadTrigger.value.generation + 1,
        )
        loadSuggested()
    }

    fun applyKosherFilters(
        glattOnly: Boolean,
        cholovYisroelOnly: Boolean,
        pasYisroelOnly: Boolean,
        certifications: Set<KosherCertification>,
    ) {
        _uiState.update {
            val next = it.copy(
                filterGlattOnly = glattOnly,
                filterCholovYisroelOnly = cholovYisroelOnly,
                filterPasYisroelOnly = pasYisroelOnly,
                filterCertifications = certifications,
            )
            next.copy(allRestaurants = applyFilters(next.rawRestaurants, next))
        }
        loadTrigger.value = loadTrigger.value.copy(
            glattOnly = glattOnly,
            cholovYisroelOnly = cholovYisroelOnly,
            pasYisroelOnly = pasYisroelOnly,
            certifications = certifications,
            page = 1,
            generation = loadTrigger.value.generation + 1,
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
        // Bump generation so the trigger always re-emits — without it, refreshing
        // the default page-1 feed assigns a structurally identical value, which
        // MutableStateFlow conflates, so nothing refetches and isRefreshing never
        // clears (spinner spins forever).
        loadTrigger.value = loadTrigger.value.copy(
            page = 1,
            generation = loadTrigger.value.generation + 1,
        )
        loadSuggested()
    }
}
