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
                val categoryStr = category?.name?.lowercase()
                val response = apiService.getMenuItems(category = categoryStr)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        items = response.body() ?: emptyList(),
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
                val response = apiService.getMenuItem(itemId)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        selectedItem = response.body(),
                        isLoading = false,
                    )
                }
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
                        saveSuccess = "Menu item deleted",
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
        viewModelScope.launch {
            try {
                val response = apiService.toggleMenuItemAvailability(
                    item.id,
                    mapOf("is_available" to !item.isAvailable),
                )
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        items = _state.value.items.map {
                            if (it.id == item.id) it.copy(isAvailable = !it.isAvailable) else it
                        },
                    )
                }
            } catch (_: Exception) { }
        }
    }

    fun setSelectedItem(item: MenuItem?) {
        _state.value = _state.value.copy(selectedItem = item)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, saveSuccess = null)
    }
}
