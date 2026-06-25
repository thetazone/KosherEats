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

    /**
     * Cursor pagination matching the backend (ListOrders keys on the created_at
     * of the last order already held). [cursor] null loads the first page and
     * replaces the list; a non-null cursor appends the next page.
     */
    fun loadOrders(cursor: String? = null, cancelExisting: Boolean = true) {
        if (cancelExisting) loadJob?.cancel()
        val isFirstPage = cursor == null
        loadJob = viewModelScope.launch {
            repository.getOrders(cursor = cursor).collect { result ->
                when (result) {
                    is Resource.Loading -> {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    is Resource.Success -> {
                        _uiState.update { state ->
                            // Dedupe defensively (key = { it.id } in the LazyColumn).
                            val newItems = if (isFirstPage) {
                                result.data
                            } else {
                                (state.orders + result.data).distinctBy { it.id }
                            }
                            val addedNew = newItems.size > state.orders.size
                            // A short page means the server has no more rows. A page
                            // that added no new ids also means we've reached the end.
                            val hasMore = result.data.size >= ApiPaging.ORDERS_PAGE_SIZE &&
                                (isFirstPage || addedNew)
                            state.copy(
                                orders = newItems,
                                isLoading = false,
                                isRefreshing = false,
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
        loadOrders(cursor = null)
    }

    fun loadMore() {
        if (loadJob?.isActive == true) return
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        // Cursor = created_at of the oldest order we currently hold.
        val cursor = state.orders.lastOrNull()?.createdAt?.takeIf { it.isNotBlank() } ?: return
        loadOrders(cursor = cursor, cancelExisting = false)
    }
}
