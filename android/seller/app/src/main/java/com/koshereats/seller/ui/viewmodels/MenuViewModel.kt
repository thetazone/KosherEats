package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.data.models.MenuItem
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MenuState(
    val items: List<MenuItem> = emptyList(),
    val selectedItem: MenuItem? = null,
    val selectedCategory: MenuCategory? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: String? = null,
    val deleteSuccess: Boolean = false,
    val pendingItemIds: Set<String> = emptySet(),
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    init {
        loadMenuItems()
    }

    fun loadMenuItems(category: MenuCategory? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                error = null,
                selectedCategory = category,
            )
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val items = response.body().orEmpty().let { categories ->
                        if (category == null) {
                            categories.flatMap { it.items }
                        } else {
                            val categoryName = category.name.lowercase()
                            categories
                                .filter { it.name.equals(categoryName, ignoreCase = true) }
                                .flatMap { it.items }
                        }
                    }
                    _state.value = _state.value.copy(
                        items = items,
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load menu items",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun loadMenuItem(itemId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                var selectedItem = _state.value.items.firstOrNull { it.id == itemId }
                if (selectedItem == null) {
                    val response = apiService.getSellerMenu()
                    if (response.isSuccessful) {
                        selectedItem = response.body().orEmpty()
                            .flatMap { it.items }
                            .firstOrNull { it.id == itemId }
                        _state.value = _state.value.copy(
                            items = response.body().orEmpty().let { categories ->
                                val selectedCategory = _state.value.selectedCategory
                                if (selectedCategory == null) {
                                    categories.flatMap { it.items }
                                } else {
                                    val categoryName = selectedCategory.name.lowercase()
                                    categories
                                        .filter { it.name.equals(categoryName, ignoreCase = true) }
                                        .flatMap { it.items }
                                }
                            },
                            selectedItem = selectedItem,
                            isLoading = false,
                            error = if (selectedItem == null) "Failed to load item" else null,
                        )
                        return@launch
                    }
                }
                _state.value = _state.value.copy(
                    selectedItem = selectedItem,
                    isLoading = false,
                    error = if (selectedItem == null) "Failed to load item" else null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load item",
                )
            }
        }
    }

    fun createMenuItem(request: UpdateMenuItemRequest) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null, saveSuccess = null)
            try {
                val response = apiService.createMenuItem(request)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        saveSuccess = "Menu item created successfully",
                    )
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = "Failed to create menu item",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun updateMenuItem(itemId: String, request: UpdateMenuItemRequest) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null, saveSuccess = null)
            try {
                val response = apiService.updateMenuItem(itemId, request)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        saveSuccess = "Menu item updated successfully",
                    )
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        error = "Failed to update menu item",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }

    fun deleteMenuItem(itemId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.deleteMenuItem(itemId)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        items = _state.value.items.filter { it.id != itemId },
                        isLoading = false,
                        deleteSuccess = true,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to delete item",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to delete item",
                )
            }
        }
    }

    fun toggleAvailability(item: MenuItem) {
        if (_state.value.pendingItemIds.contains(item.id)) return
        val newAvailability = !item.isAvailable
        _state.value = _state.value.copy(
            items = _state.value.items.map {
                if (it.id == item.id) it.copy(isAvailable = newAvailability) else it
            },
            pendingItemIds = _state.value.pendingItemIds + item.id,
        )
        viewModelScope.launch {
            try {
                val response = apiService.toggleMenuItemAvailability(
                    item.id,
                    mapOf("is_available" to newAvailability),
                )
                if (response.isSuccessful) {
                    val updatedItem = response.body()
                    _state.value = _state.value.copy(
                        items = if (updatedItem != null) _state.value.items.map {
                            if (it.id == item.id) updatedItem else it
                        } else _state.value.items,
                        pendingItemIds = _state.value.pendingItemIds - item.id,
                    )
                } else {
                    _state.value = _state.value.copy(
                        items = _state.value.items.map {
                            if (it.id == item.id && it.isAvailable == newAvailability) it.copy(isAvailable = item.isAvailable) else it
                        },
                        pendingItemIds = _state.value.pendingItemIds - item.id,
                        error = "Failed to update availability",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    items = _state.value.items.map {
                        if (it.id == item.id && it.isAvailable == newAvailability) it.copy(isAvailable = item.isAvailable) else it
                    },
                    pendingItemIds = _state.value.pendingItemIds - item.id,
                    error = if (e is kotlinx.coroutines.CancellationException) null else "Failed to update availability",
                )
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun setSelectedItem(item: MenuItem?) {
        _state.value = _state.value.copy(selectedItem = item)
    }

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, saveSuccess = null, deleteSuccess = false)
    }
}
