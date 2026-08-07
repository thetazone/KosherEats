package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiPaging
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
    // The list the feed actually renders: the raw server list with the active
    // KOSHER filters applied client-side (the backend ignores those params, so
    // filtering must happen here for the feed to be truthful; mirrors iOS
    // RestaurantStore.filteredRestaurants). Cuisine is NOT filtered here — the
    // backend filters ?cuisine= server-side, and the server's ordering
    // (orderable restaurants first, previews after) must be preserved as-is.
    // HomeScreen renders this field directly, so it MUST hold the post-filter
    // list — not the raw set.
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
    // Server-side cuisine tag (case-insensitive match on the backend), or null
    // for "All". These are free-form tags, not CuisineType enum values.
    val selectedCuisine: String? = null,
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
        // Selected delivery-address coordinates. When non-null the backend orders
        // restaurants by proximity (ORDER BY point(lng,lat) <-> point); when null it
        // falls back to rating. Null (not 0.0) for "no/ungeocoded address" so we
        // never send a meaningless 0,0 proximity sort.
        val latitude: Double? = null,
        val longitude: Double? = null,
        // Server-side cuisine tag, sent verbatim as ?cuisine= (backend matches
        // case-insensitively).
        val cuisine: String? = null,
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
                        latitude = trigger.latitude,
                        longitude = trigger.longitude,
                        cuisine = trigger.cuisine,
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

    // Client-side KOSHER filtering. The backend ignores the kosher filter query
    // params and always returns the same set, so the UI must filter the fetched
    // list itself to be truthful. AND-combined, mirroring iOS
    // RestaurantStore.filteredRestaurants. Cuisine is deliberately NOT filtered
    // here: ?cuisine= is honored server-side (the fetched list already reflects
    // it), and re-filtering locally would break for tags that don't map onto
    // the CuisineType enum (Bagels, Heimish, …). Ordering is also preserved
    // as-is — the server puts orderable restaurants first and preview listings
    // after, and the client must never re-sort previews above orderable rows.
    private fun applyFilters(source: List<Restaurant>, state: HomeUiState): List<Restaurant> =
        source.filter { r ->
            val glattOk = !state.filterGlattOnly || r.isGlattKosher
            val cholovOk = !state.filterCholovYisroelOnly || r.isCholovYisroel
            val pasOk = !state.filterPasYisroelOnly || r.isPasYisroel
            val certOk = state.filterCertifications.isEmpty() ||
                r.kosherCertification in state.filterCertifications
            glattOk && cholovOk && pasOk && certOk
        }

    private fun KosherCertification.toApiString(): String = when (this) {
        KosherCertification.STAR_K -> "Star-K"
        KosherCertification.KOF_K -> "Kof-K"
        KosherCertification.BADATZ -> "Badatz"
        KosherCertification.CRC -> "cRc"
        KosherCertification.CHOF_K -> "Chof-K"
        KosherCertification.OTHER -> "other"
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
        val trigger = loadTrigger.value
        searchJob = viewModelScope.launch {
            delay(300)
            repository.searchRestaurants(
                query,
                latitude = trigger.latitude,
                longitude = trigger.longitude,
            ).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        // Clear any stale error (from a prior failed feed load or a
                        // previous failed search) so a SUCCESSFUL zero-result search
                        // renders "No restaurants found" — not "Search failed". The
                        // `error` field is shared with the feed load path, so search
                        // must reset it on each new attempt to keep error attribution
                        // precise in HomeScreen's search empty-state branch.
                        _uiState.update { it.copy(isSearching = true, error = null) }
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

    /**
     * Update the delivery-location used for proximity ordering and refetch page 1.
     * Pass null lat/lng when no address is selected or it isn't geocoded so the
     * backend falls back to rating order instead of sorting around 0,0. No-op when
     * the coordinates are unchanged, so it's safe to call on every address emission.
     */
    fun setLocation(latitude: Double?, longitude: Double?) {
        val current = loadTrigger.value
        if (current.latitude == latitude && current.longitude == longitude) return
        loadTrigger.value = current.copy(
            latitude = latitude,
            longitude = longitude,
            page = 1,
            generation = current.generation + 1,
        )
    }

    /**
     * Select a server-side cuisine tag (one of [CUISINE_TAGS]) or null for
     * "All". The backend filters ?cuisine= itself, so this just refetches
     * page 1 with the tag — no client-side re-filtering.
     */
    fun selectCuisine(cuisine: String?) {
        _uiState.update { it.copy(selectedCuisine = cuisine) }
        loadTrigger.value = loadTrigger.value.copy(
            cuisine = cuisine,
            page = 1,
            generation = loadTrigger.value.generation + 1,
        )
    }

    /**
     * Toggle the signed-in user's "Request restaurant" state on a preview
     * listing (feed + search rows). Optimistic flip, reconciled with the
     * server's authoritative {requested, request_count} response; reverted on
     * failure. Callers must auth-gate: guests should be routed to sign-in
     * instead (the endpoint requires a token).
     */
    fun toggleRequest(restaurantId: String) {
        val current = (_uiState.value.rawRestaurants + _uiState.value.searchResults)
            .find { it.id == restaurantId } ?: return
        if (current.orderable) return // Request is only meaningful for previews.
        val optimisticRequested = !current.requestedByMe
        val optimisticCount =
            (current.requestCount + if (optimisticRequested) 1 else -1).coerceAtLeast(0)
        patchRequestState(restaurantId, optimisticRequested, optimisticCount)
        viewModelScope.launch {
            when (val result = repository.toggleRestaurantRequest(restaurantId)) {
                is Resource.Success ->
                    patchRequestState(restaurantId, result.data.requested, result.data.requestCount)
                is Resource.Error ->
                    // Roll back to the pre-toggle snapshot for this restaurant only.
                    patchRequestState(restaurantId, current.requestedByMe, current.requestCount)
                is Resource.Loading -> {}
            }
        }
    }

    private fun patchRequestState(restaurantId: String, requested: Boolean, count: Int) {
        fun List<Restaurant>.patched() = map { r ->
            if (r.id == restaurantId) r.copy(requestedByMe = requested, requestCount = count) else r
        }
        _uiState.update {
            it.copy(
                rawRestaurants = it.rawRestaurants.patched(),
                allRestaurants = it.allRestaurants.patched(),
                searchResults = it.searchResults.patched(),
            )
        }
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

    companion object {
        /**
         * Cuisine tags the backend filters on server-side (case-insensitive
         * match against the restaurant's tag list). Rendered as the chip row on
         * the home feed; keep in sync with the backend's known tags.
         */
        val CUISINE_TAGS = listOf(
            "Israeli", "Grill", "Pizza", "Sushi", "Asian", "Cafe", "Deli",
            "Bagels", "BBQ", "Burgers", "Steakhouse", "Meat", "Dairy",
            "Pareve", "Takeout", "Heimish",
        )
    }
}
