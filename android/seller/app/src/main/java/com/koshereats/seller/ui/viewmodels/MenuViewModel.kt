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
import com.koshereats.seller.data.models.SellerMenuCategory
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MenuState(
    val items: List<MenuItem> = emptyList(),
    val categories: List<SellerMenuCategory> = emptyList(),
    val selectedItem: MenuItem? = null,
    val selectedCategory: MenuCategory? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: String? = null,
    val itemSaveSuccess: Boolean = false,
    val deleteSuccess: Boolean = false,
    val pendingItemIds: Set<String> = emptySet(),
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadMenuItems()
    }

    fun loadMenuItems(category: MenuCategory? = null) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(
                isLoading = true,
                error = null,
                selectedCategory = category,
            ) }
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val serverCategories = response.body().orEmpty()
                    val items = if (category == null) {
                        serverCategories.flatMap { it.items }
                    } else {
                        serverCategories.flatMap { it.items }.filter { it.category == category }
                    }
                    _state.update { it.copy(
                        categories = serverCategories,
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
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val categories = response.body().orEmpty()
                    val allItems = categories.flatMap { it.items }
                    val selectedItem = allItems.firstOrNull { it.id == itemId }
                    _state.update { state ->
                        state.copy(
                            items = if (state.selectedCategory == null) {
                                allItems
                            } else {
                                allItems.filter { it.category == state.selectedCategory }
                            },
                            selectedItem = selectedItem,
                            isLoading = false,
                            error = if (selectedItem == null) "Failed to load item" else null,
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false, error = "Failed to load item") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isLoading = false, error = "Failed to load item") }
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

                val categorySlug = targetCategory.name.lowercase()

                val cachedCategories = _state.value.categories.ifEmpty {
                    val menuResponse = apiService.getSellerMenu()
                    if (!menuResponse.isSuccessful) {
                        _state.update { it.copy(isSaving = false, error = "Failed to load menu (${menuResponse.code()})") }
                        return@launch
                    }
                    val cats = menuResponse.body().orEmpty()
                    _state.update { it.copy(categories = cats) }
                    cats
                }
                val existingCategory = cachedCategories
                    .firstOrNull { serverCat ->
                        serverCat.name.lowercase().replace(' ', '_') == categorySlug ||
                        serverCat.name.equals(categorySlug, ignoreCase = true)
                    }

                val categoryId: String?
                val createdCategoryId: String?
                if (existingCategory != null) {
                    categoryId = existingCategory.id
                    createdCategoryId = null
                } else {
                    val categoryDisplayName = targetCategory.name.lowercase()
                        .split("_")
                        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                    val catResp = apiService.createCategory(mapOf("name" to categoryDisplayName))
                    if (!catResp.isSuccessful) {
                        _state.update { it.copy(isSaving = false, error = "Failed to create category (${catResp.code()})") }
                        return@launch
                    }
                    categoryId = catResp.body()?.id
                    createdCategoryId = categoryId
                    catResp.body()?.let { newCat ->
                        _state.update { it.copy(categories = it.categories + newCat) }
                    }
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
                    isAvailable = request.isAvailable ?: true,
                    spiceLevel = request.spiceLevel,
                    preparationTime = request.preparationTime,
                    allergens = request.allergens,
                    calories = request.calories,
                )
                val response = apiService.createMenuItemWithCategory(body)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        isSaving = false,
                        saveSuccess = "Menu item created successfully",
                        itemSaveSuccess = true,
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    if (createdCategoryId != null) {
                        runCatching { apiService.deleteCategory(createdCategoryId) }
                    }
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
                        itemSaveSuccess = true,
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
                        pendingItemIds = it.pendingItemIds - item.id,
                        error = "Failed to update availability",
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingItemIds = it.pendingItemIds - item.id,
                    error = "Failed to update availability",
                ) }
                loadMenuItems(_state.value.selectedCategory)
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
        _state.update { it.copy(error = null, saveSuccess = null, itemSaveSuccess = false, deleteSuccess = false) }
    }
}
