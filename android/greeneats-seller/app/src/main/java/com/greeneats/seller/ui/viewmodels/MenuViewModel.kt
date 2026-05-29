package com.greeneats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.api.NetworkModule
import com.greeneats.seller.data.models.CreateMenuItemBody
import com.greeneats.seller.data.models.CreateModifierGroupRequest
import com.greeneats.seller.data.models.MenuCategory
import com.greeneats.seller.data.models.MenuItem
import com.greeneats.seller.data.models.ModifierGroup
import com.greeneats.seller.data.models.PresignResponse
import com.greeneats.seller.data.models.SellerMenuCategory
import com.greeneats.seller.data.models.UpdateMenuItemRequest
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
    val deleteSuccess: Boolean = false,
    val pendingItemIds: Set<String> = emptySet(),
    val pendingDeleteIds: Set<String> = emptySet(),
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    companion object {
        private const val ERR_LOAD_MENU = "Failed to load menu items"
        private const val ERR_LOAD_ITEM = "Failed to load item"
        private const val ERR_RESOLVE_CATEGORY = "Failed to resolve category"
        private const val ERR_CREATE_ITEM = "Failed to create menu item"
        private const val ERR_UPDATE_ITEM = "Failed to update menu item"
        private const val ERR_DELETE_ITEM = "Failed to delete item"
        private const val ERR_TOGGLE_AVAILABILITY = "Failed to update availability"
        private const val ERR_ADD_MODIFIER = "Failed to add modifier group"
        private const val ERR_UPDATE_MODIFIER = "Failed to update modifier group"
        private const val ERR_DELETE_MODIFIER = "Failed to delete modifier group"
        private const val ERR_CONNECTION = "Connection error"
        private const val MSG_ITEM_CREATED = "Menu item created successfully"
        private const val MSG_ITEM_UPDATED = "Menu item updated successfully"
        private const val MSG_MODIFIER_ADDED = "Modifier group added"
        private const val MSG_MODIFIER_UPDATED = "Modifier group updated"
        private const val MSG_MODIFIER_DELETED = "Modifier group deleted"
    }

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadMenuItems()
        viewModelScope.launch {
            NetworkModule.restaurantChanged.collect {
                // Cancel any in-flight menu fetch so its response (carrying items from the
                // previous restaurant) cannot land into the freshly-cleared state.
                loadJob?.cancel()
                loadJob = null
                _state.value = MenuState()
                loadMenuItems()
            }
        }
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
                    val items = serverCategories.let { categories ->
                        if (category == null) {
                            categories.flatMap { it.items }
                        } else {
                            val categoryName = category.name.lowercase()
                            categories
                                .filter { it.name.equals(categoryName, ignoreCase = true) }
                                .flatMap { it.items }
                        }
                    }
                    _state.update { it.copy(
                        items = items,
                        categories = serverCategories,
                        isLoading = false,
                    ) }
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        error = ERR_LOAD_MENU,
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = "$ERR_CONNECTION: ${e.localizedMessage}",
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
                                    val categoryName = selectedCategory.name.lowercase()
                                    categories
                                        .filter { it.name.equals(categoryName, ignoreCase = true) }
                                        .flatMap { it.items }
                                }
                            },
                            selectedItem = selectedItem,
                            isLoading = false,
                            error = if (selectedItem == null) ERR_LOAD_ITEM else null,
                        ) }
                        return@launch
                    }
                }
                _state.update { it.copy(
                    selectedItem = selectedItem,
                    isLoading = false,
                    error = if (selectedItem == null) ERR_LOAD_ITEM else null,
                ) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isLoading = false,
                    error = ERR_LOAD_ITEM,
                ) }
            }
        }
    }

    fun createMenuItem(request: UpdateMenuItemRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, saveSuccess = null) }
            try {
                val categoryName = request.category
                    ?.replace('_', ' ')
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Mains"

                val menuResponse = apiService.getSellerMenu()
                val existingCategory = menuResponse.body()
                    ?.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }

                val categoryId = if (existingCategory != null) {
                    existingCategory.id
                } else {
                    val catResp = apiService.createCategory(mapOf("name" to categoryName))
                    catResp.body()?.id
                }

                if (categoryId == null) {
                    _state.update { it.copy(isSaving = false, error = ERR_RESOLVE_CATEGORY) }
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
                        saveSuccess = MSG_ITEM_CREATED,
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.update { it.copy(
                        isSaving = false,
                        error = ERR_CREATE_ITEM,
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isSaving = false,
                    error = "$ERR_CONNECTION: ${e.localizedMessage}",
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
                        saveSuccess = MSG_ITEM_UPDATED,
                    ) }
                    loadMenuItems(_state.value.selectedCategory)
                } else {
                    _state.update { it.copy(
                        isSaving = false,
                        error = ERR_UPDATE_ITEM,
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    isSaving = false,
                    error = "$ERR_CONNECTION: ${e.localizedMessage}",
                ) }
            }
        }
    }

    fun deleteMenuItem(itemId: String) {
        if (_state.value.pendingDeleteIds.contains(itemId)) return
        _state.update { it.copy(pendingDeleteIds = it.pendingDeleteIds + itemId, error = null) }
        viewModelScope.launch {
            try {
                val response = apiService.deleteMenuItem(itemId)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        items = it.items.filter { it.id != itemId },
                        pendingDeleteIds = it.pendingDeleteIds - itemId,
                        deleteSuccess = true,
                    ) }
                } else {
                    _state.update { it.copy(
                        pendingDeleteIds = it.pendingDeleteIds - itemId,
                        error = ERR_DELETE_ITEM,
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingDeleteIds = it.pendingDeleteIds - itemId,
                    error = ERR_DELETE_ITEM,
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
            categories = it.categories.map { cat ->
                cat.copy(items = cat.items.map { catItem ->
                    if (catItem.id == item.id) catItem.copy(isAvailable = newAvailability) else catItem
                })
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
                        categories = if (updatedItem != null) it.categories.map { cat ->
                            cat.copy(items = cat.items.map { catItem ->
                                if (catItem.id == item.id) updatedItem else catItem
                            })
                        } else it.categories,
                        pendingItemIds = it.pendingItemIds - item.id,
                    ) }
                } else {
                    _state.update { it.copy(
                        items = it.items.map { existing ->
                            if (existing.id == item.id) existing.copy(isAvailable = item.isAvailable) else existing
                        },
                        categories = it.categories.map { cat ->
                            cat.copy(items = cat.items.map { catItem ->
                                if (catItem.id == item.id) catItem.copy(isAvailable = item.isAvailable) else catItem
                            })
                        },
                        pendingItemIds = it.pendingItemIds - item.id,
                        error = ERR_TOGGLE_AVAILABILITY,
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    items = it.items.map { existing ->
                        if (existing.id == item.id) existing.copy(isAvailable = item.isAvailable) else existing
                    },
                    categories = it.categories.map { cat ->
                        cat.copy(items = cat.items.map { catItem ->
                            if (catItem.id == item.id) catItem.copy(isAvailable = item.isAvailable) else catItem
                        })
                    },
                    pendingItemIds = it.pendingItemIds - item.id,
                    error = ERR_TOGGLE_AVAILABILITY,
                ) }
            }
        }
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        return try {
            val response = apiService.presignUpload(mapOf("kind" to kind, "content_type" to contentType))
            if (response.isSuccessful) {
                response.body()
            } else {
                _state.update { it.copy(error = "$ERR_CONNECTION: presign failed (${response.code()})") }
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(error = "$ERR_CONNECTION: ${e.localizedMessage}") }
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
                    _state.update { it.copy(isSaving = false, saveSuccess = MSG_MODIFIER_ADDED) }
                } else {
                    _state.update { it.copy(isSaving = false, error = ERR_ADD_MODIFIER) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "$ERR_CONNECTION: ${e.localizedMessage}") }
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
                    _state.update { it.copy(isSaving = false, saveSuccess = MSG_MODIFIER_UPDATED) }
                } else {
                    _state.update { it.copy(isSaving = false, error = ERR_UPDATE_MODIFIER) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "$ERR_CONNECTION: ${e.localizedMessage}") }
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
                    _state.update { it.copy(isSaving = false, saveSuccess = MSG_MODIFIER_DELETED) }
                } else {
                    _state.update { it.copy(isSaving = false, error = ERR_DELETE_MODIFIER) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(isSaving = false, error = "$ERR_CONNECTION: ${e.localizedMessage}") }
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
