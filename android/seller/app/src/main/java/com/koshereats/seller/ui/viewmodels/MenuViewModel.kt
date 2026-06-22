package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.models.CreateMenuItemBody
import com.koshereats.seller.data.models.CreateModifierGroupRequest
import com.koshereats.seller.data.models.MenuImport
import com.koshereats.seller.data.models.MenuItem
import com.koshereats.seller.data.models.ModifierGroup
import com.koshereats.seller.data.models.PresignResponse
import com.koshereats.seller.data.models.SellerMenuCategory
import com.koshereats.seller.data.models.UpdateMenuItemRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val selectedCategory: SellerMenuCategory? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: String? = null,
    val itemSaveSuccess: Boolean = false,
    val deleteSuccess: Boolean = false,
    val pendingItemIds: Set<String> = emptySet(),
    val modifierGroupsLoading: Boolean = false,
    // Newest menu-import job for the active restaurant; drives the Menu-tab
    // import-status banner. Null when no import has ever been started.
    val latestImport: MenuImport? = null,
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var importPollJob: Job? = null

    init {
        loadMenuItems()
        pollMenuImports()
        viewModelScope.launch {
            NetworkModule.restaurantChanged.collect {
                // Cancel any in-flight menu fetch so its response (carrying items from the
                // previous restaurant) cannot land into the freshly-cleared state.
                loadJob?.cancel()
                loadJob = null
                importPollJob?.cancel()
                importPollJob = null
                _state.value = MenuState()
                loadMenuItems()
                pollMenuImports()
            }
        }
    }

    /**
     * Polls the import-jobs endpoint and surfaces the newest job as [MenuState.latestImport]
     * so the Menu tab can show an "importing…" banner. Mirrors iOS's listMenuImports polling.
     * Stops once the newest job is no longer in progress; reloads the menu when an import
     * transitions out of flight so freshly-imported items appear without a manual refresh.
     */
    fun pollMenuImports() {
        importPollJob?.cancel()
        importPollJob = viewModelScope.launch {
            var wasInProgress = false
            while (true) {
                val latest = try {
                    val response = apiService.listMenuImports()
                    if (response.isSuccessful) response.body()?.firstOrNull() else null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                _state.update { it.copy(latestImport = latest) }

                val inProgress = latest?.isInProgress == true
                if (wasInProgress && !inProgress) {
                    // Import just finished (done/failed) — pull in any new items.
                    loadMenuItems(_state.value.selectedCategory)
                }
                wasInProgress = inProgress
                if (!inProgress) break
                delay(IMPORT_POLL_INTERVAL_MS)
            }
        }
    }

    /** Dismiss a finished (done/failed) import banner without affecting an in-flight one. */
    fun dismissImportBanner() {
        _state.update {
            if (it.latestImport?.isInProgress == true) it else it.copy(latestImport = null)
        }
    }

    fun loadMenuItems(category: SellerMenuCategory? = null) {
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
                        serverCategories.firstOrNull { it.id == category.id }?.items ?: emptyList()
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

    /**
     * Switches the visible category using the already-loaded menu tree. No network
     * call and no full-screen spinner — chip taps must be instant and work offline,
     * since `state.categories` already holds every category and its items.
     * Use [loadMenuItems] only for explicit refreshes (pull-to-refresh, post-save).
     */
    fun selectCategory(category: SellerMenuCategory?) {
        _state.update { state ->
            val items = if (category == null) {
                state.categories.flatMap { it.items }
            } else {
                state.categories.firstOrNull { it.id == category.id }?.items
                    ?: category.items
            }
            state.copy(selectedCategory = category, items = items)
        }
    }

    fun loadMenuItem(itemId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, selectedItem = null) }
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val categories = response.body().orEmpty()
                    val allItems = categories.flatMap { it.items }
                    val selectedItem = allItems.firstOrNull { it.id == itemId }
                    _state.update { state ->
                        val selectedCat = state.selectedCategory
                        state.copy(
                            categories = categories,
                            items = if (selectedCat == null) {
                                allItems
                            } else {
                                categories.firstOrNull { it.id == selectedCat.id }?.items ?: allItems
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

    private fun refreshModifierGroups(itemId: String) {
        viewModelScope.launch {
            _state.update { it.copy(modifierGroupsLoading = true) }
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    val categories = response.body().orEmpty()
                    val allItems = categories.flatMap { it.items }
                    val updatedItem = allItems.firstOrNull { it.id == itemId }
                    _state.update { state ->
                        val selectedCat = state.selectedCategory
                        state.copy(
                            categories = categories,
                            items = if (selectedCat == null) allItems else categories.firstOrNull { it.id == selectedCat.id }?.items ?: allItems,
                            selectedItem = updatedItem ?: state.selectedItem,
                            modifierGroupsLoading = false,
                        )
                    }
                } else {
                    _state.update { it.copy(modifierGroupsLoading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(modifierGroupsLoading = false) }
            }
        }
    }

    fun createMenuItem(request: UpdateMenuItemRequest) {
        val categoryId = request.categoryId
        if (categoryId.isNullOrBlank()) {
            _state.update { it.copy(error = "Please select a category") }
            return
        }
        if (request.name.isNullOrBlank()) {
            _state.update { it.copy(error = "Menu item name is required") }
            return
        }
        if ((request.price ?: 0) <= 0) {
            _state.update { it.copy(error = "Price must be greater than zero") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, saveSuccess = null) }
            try {
                val body = CreateMenuItemBody(
                    categoryId = categoryId,
                    name = request.name ?: "",
                    description = request.description ?: "",
                    price = request.price ?: 0,
                    imageUrl = request.imageUrl ?: "",
                    isMeat = request.isMeat ?: false,
                    isDairy = request.isDairy ?: false,
                    isKosherPareve = request.isKosherPareve ?: false,
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
        if (_state.value.pendingItemIds.contains(itemId)) return
        _state.update { it.copy(pendingItemIds = it.pendingItemIds + itemId, error = null) }
        viewModelScope.launch {
            try {
                val response = apiService.deleteMenuItem(itemId)
                if (response.isSuccessful) {
                    _state.update { it.copy(
                        items = it.items.filter { item -> item.id != itemId },
                        categories = it.categories.map { cat ->
                            cat.copy(items = cat.items.filter { item -> item.id != itemId })
                        },
                        pendingItemIds = it.pendingItemIds - itemId,
                        deleteSuccess = true,
                    ) }
                } else {
                    _state.update { it.copy(
                        pendingItemIds = it.pendingItemIds - itemId,
                        error = "Failed to delete item",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    pendingItemIds = it.pendingItemIds - itemId,
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
                        items = it.items.map { existing -> if (existing.id == item.id) existing.copy(isAvailable = item.isAvailable) else existing },
                        categories = it.categories.map { cat ->
                            cat.copy(items = cat.items.map { catItem ->
                                if (catItem.id == item.id) catItem.copy(isAvailable = item.isAvailable) else catItem
                            })
                        },
                        pendingItemIds = it.pendingItemIds - item.id,
                        error = "Failed to update availability",
                    ) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(
                    items = it.items.map { existing -> if (existing.id == item.id) existing.copy(isAvailable = item.isAvailable) else existing },
                    categories = it.categories.map { cat ->
                        cat.copy(items = cat.items.map { catItem ->
                            if (catItem.id == item.id) catItem.copy(isAvailable = item.isAvailable) else catItem
                        })
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    fun createModifierGroup(itemId: String, request: CreateModifierGroupRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val response = apiService.createModifierGroup(itemId, request)
                if (response.isSuccessful) {
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group added") }
                    refreshModifierGroups(itemId)
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
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group updated") }
                    refreshModifierGroups(itemId)
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
                    _state.update { it.copy(isSaving = false, saveSuccess = "Modifier group deleted") }
                    refreshModifierGroups(itemId)
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

    companion object {
        private const val IMPORT_POLL_INTERVAL_MS = 5_000L
    }
}
