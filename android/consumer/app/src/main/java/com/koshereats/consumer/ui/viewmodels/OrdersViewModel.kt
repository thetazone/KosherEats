package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiPaging
import com.koshereats.consumer.data.models.Order
import com.koshereats.consumer.data.repository.Resource
import com.koshereats.consumer.data.repository.RestaurantRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrdersUiState(
    val orders: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadOrders()
    }

    fun loadOrders(page: Int = 1, cancelExisting: Boolean = true) {
        if (cancelExisting) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.getOrders(page = page).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    is Resource.Success -> {
                        _uiState.update { state ->
                            // Defensive dedupe: the backend paginates by cursor/limit and
                            // currently ignores the page/per_page params the client sends, so a
                            // "next page" can return rows we already hold. Appending blind would
                            // produce duplicate ids and crash the LazyColumn (key = { it.id }).
                            val newItems = if (page == 1) {
                                result.data
                            } else {
                                (state.orders + result.data).distinctBy { it.id }
                            }
                            // If a follow-up page added no genuinely new rows, there is nothing
                            // more to load — stop, so we don't loop re-fetching the same batch.
                            val addedNew = newItems.size > state.orders.size
                            val hasMore = if (page == 1) {
                                result.data.size >= ApiPaging.ORDERS_PAGE_SIZE
                            } else {
                                addedNew && result.data.size >= ApiPaging.ORDERS_PAGE_SIZE
                            }
                            state.copy(
                                orders = newItems,
                                isLoading = false,
                                isRefreshing = false,
                                currentPage = page,
                                hasMore = hasMore,
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.message, hasMore = false) }
                    }
                }
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadOrders(page = 1)
    }

    fun loadMore() {
        if (loadJob?.isActive == true) return
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        if (state.currentPage >= 100) return
        loadOrders(page = state.currentPage + 1, cancelExisting = false)
    }
}
