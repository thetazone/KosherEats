package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.CreateMenuItemBody
import com.koshereats.seller.data.models.CreateModifierGroupRequest
import com.koshereats.seller.data.models.MenuCategory
import com.koshereats.seller.data.models.MenuItem
import com.koshereats.seller.data.models.ModifierGroup
import com.koshereats.seller.data.models.PresignResponse
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            _state.update { it.copy(
                isLoading = true,
                error = null,
                selectedCategory = category,
            ) }
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val items = response.body().orEmpty().let { categories ->
                        if (category == null) {
                            categories.flatMap { it.items }
                        } else {
                            categories.flatMap { it.items }.filter { it.category == category }
                        }
                    }
                    _state.update { it.copy(
                        items = items,
                        isLoading = false,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        error = "Failed to load menu items",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun loadMenuItem(itemId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                var selectedItem = _state.value.items.firstOrNull { it.id == itemId }
                if (selectedItem == null) {
                    val response = apiService.getSellerMenu()
                    if (response.isSuccessful) {
                        selectedItem = response.body().orEmpty()
                            .flatMap { it.items }
                            .firstOrNull { it.id == itemId }
                        _state.update { it.copy(
                            items = response.body().orEmpty().let { categories ->
                                val selectedCategory = it.selectedCategory
                                if (selectedCategory == null) {
                                    categories.flatMap { it.items }
                                } else {
                                    categories.flatMap { it.items }.filter { it.category == selectedCategory }
                                }
                            },
                            selectedItem = selectedItem,
                            isLoading = false,
                            error = if (selectedItem == null) "Failed to load item" else null,
                        ) }
                        return@launch
                    }
                }
                _state.update { it.copy(
                    selectedItem = selectedItem,
                    isLoading = false,
                    error = if (selectedItem == null) "Failed to load item" else null,
                ) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "Failed to load item",
                ) }
            }
        }
    }

    fun createMenuItem(request: UpdateMenuItemRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, saveSuccess = null) }
            try {
                val targetCategory = request.category
                    ?.uppercase()
                    ?.let { runCatching { MenuCategory.valueOf(it) }.getOrNull() }
                    ?: MenuCategory.MAINS

                val menuResponse = apiService.getSellerMenu()
                val existingCategory = menuResponse.body()
                    ?.firstOrNull { serverCat -> serverCat.items.any { it.category == targetCategory } }

                val categoryId = if (existingCategory != null) {
                    existingCategory.id
                } else {
                    val displayName = targetCategory.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                    val catResp = apiService.createCategory(mapOf("name" to displayName))
                    catResp.body()?.id
                }

                if (categoryId == null) {
                    _state.update { it.copy(isSaving = false, error = "Failed to resolve category") }
                    return@launch
                }

                val body = CreateMenuItemBody(
                    categoryId = categoryId,
                    name = request.name ?: "",
                    description = request.description ?: "",
                    price = request.price ?: 0,
                    imageUrl = request.imageUrl ?: "",
                    isMeat = request.isMeat ?: false,
                    isDairy = request.isDairy ?: false,
                    isPareve = request.isKosherPareve ?: false,
                )
                val response = apiService.createMenuItemWithCategory(body)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        isSaving = false,
                        saveSuccess = "Menu item created successfully",
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.update { it.copy(
                        isSaving = false,
                        error = "Failed to create menu item",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isSaving = false,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun updateMenuItem(itemId: String, request: UpdateMenuItemRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, saveSuccess = null) }
            try {
                val response = apiService.updateMenuItem(itemId, request)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        isSaving = false,
                        saveSuccess = "Menu item updated successfully",
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.update { it.copy(
                        isSaving = false,
                        error = "Failed to update menu item",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isSaving = false,
                    error = "Connection error: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun deleteMenuItem(itemId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.deleteMenuItem(itemId)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        items = it.items.filter { it.id != itemId },
                        isLoading = false,
                        deleteSuccess = true,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        error = "Failed to delete item",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "Failed to delete item",
                ) }
            }
        }
    }

    fun toggleAvailability(item: MenuItem) {
        if (_state.value.pendingItemIds.contains(item.id)) return
        val newAvailability = !item.isAvailable
        _state.update { it.copy(
            items = it.items.map {
                if (it.id == item.id) it.copy(isAvailable = newAvailability) else it
            },
            pendingItemIds = it.pendingItemIds + item.id,
        ) }
        viewModelScope.launch {
            try {
                val response = apiService.toggleMenuItemAvailability(
                    item.id,
                    mapOf("is_available" to newAvailability),
                )
                if (response.isSuccessful) {
                    val updatedItem = response.body()
                    _state.update { it.copy(
                        items = if (updatedItem != null) it.items.map {
                            if (it.id == item.id) updatedItem else it
                        } else it.items,
                        pendingItemIds = it.pendingItemIds - item.id,
                    ) }
                } else {
                    _state.update { it.copy(
                        items = it.items.map {
                            if (it.id == item.id && it.isAvailable == newAvailability) it.copy(isAvailable = item.isAvailable) else it
                        },
                        pendingItemIds = it.pendingItemIds - item.id,
                        error = "Failed to update availability",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    items = it.items.map {
                        if (it.id == item.id && it.isAvailable == newAvailability) it.copy(isAvailable = item.isAvailable) else it
                    },
                    pendingItemIds = it.pendingItemIds - item.id,
                    error = "Failed to update availability",
                ) }
            }
        }
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        return try {
            val response = apiService.presignUpload(mapOf("kind" to kind, "content_type" to contentType))
            if (response.isSuccessful) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    fun createModifierGroup(itemId: String, request: CreateModifierGroupRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.createModifierGroup(itemId, request)
                if (response.isSuccessful) {
                    loadMenuItem(itemId)
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group added") }
                } else {
                    _state.update { it.copy(isSaving = false, error = "Failed to add modifier group") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "Connection error") }
            }
        }
    }

    fun updateModifierGroup(groupId: String, itemId: String, request: CreateModifierGroupRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.updateModifierGroup(groupId, request)
                if (response.isSuccessful) {
                    loadMenuItem(itemId)
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group updated") }
                } else {
                    _state.update { it.copy(isSaving = false, error = "Failed to update modifier group") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "Connection error") }
            }
        }
    }

    fun deleteModifierGroup(groupId: String, itemId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.deleteModifierGroup(groupId)
                if (response.isSuccessful) {
                    loadMenuItem(itemId)
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group deleted") }
                } else {
                    _state.update { it.copy(isSaving = false, error = "Failed to delete modifier group") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "Connection error") }
            }
        }
    }

    fun setSelectedItem(item: MenuItem?) {
        _state.update { it.copy(selectedItem = item) }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, saveSuccess = null, deleteSuccess = false) }
    }
}
