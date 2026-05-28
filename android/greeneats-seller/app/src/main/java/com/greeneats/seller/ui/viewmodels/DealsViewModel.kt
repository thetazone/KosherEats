package com.greeneats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.seller.data.api.ApiService
import com.greeneats.seller.data.models.CreateDealRequest
import com.greeneats.seller.data.models.Deal
import com.greeneats.seller.data.models.DiscountType
import com.greeneats.seller.data.models.MenuItem
import com.greeneats.seller.data.models.PresignResponse
import com.greeneats.seller.data.models.SellerMenuCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DealsState(
    val deals: List<Deal> = emptyList(),
    val menuItems: List<MenuItem> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val deactivatingDealId: String? = null,
    val error: String? = null,
    val createSuccess: Boolean = false,
)

@HiltViewModel
class DealsViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(DealsState())
    val state: StateFlow<DealsState> = _state.asStateFlow()

    init {
        loadDeals()
    }

    fun loadDeals() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.getDeals()
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        deals = response.body().orEmpty(),
                        isLoading = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to load deals (HTTP ${response.code()})",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load deals: ${e.localizedMessage}",
                )
            }
        }
    }

    fun loadMenuItems() {
        viewModelScope.launch {
            try {
                val response = apiService.getSellerMenu()
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        menuItems = response.body().orEmpty().flatMap { it.items },
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun createDeal(
        title: String,
        description: String,
        imageUrl: String,
        menuItemId: String?,
        discountType: DiscountType,
        discountValue: Int,
        minOrderAmount: Int?,
        startsAt: String?,
        expiresAt: String,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCreating = true, error = null, createSuccess = false)
            try {
                val request = CreateDealRequest(
                    title = title,
                    description = description,
                    imageUrl = imageUrl,
                    menuItemId = menuItemId?.takeIf { it.isNotBlank() },
                    discountType = discountType,
                    discountValue = discountValue,
                    minOrderAmount = minOrderAmount,
                    startsAt = startsAt,
                    expiresAt = expiresAt,
                )
                val response = apiService.createDeal(request)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        isCreating = false,
                        createSuccess = true,
                    )
                    loadDeals()
                } else {
                    val serverMsg = try {
                        response.errorBody()?.string()?.let { errorBody ->
                            com.squareup.moshi.Moshi.Builder().build()
                                .adapter(Map::class.java)
                                .fromJson(errorBody)?.get("error") as? String
                        }
                    } catch (_: Exception) { null }
                    _state.value = _state.value.copy(
                        isCreating = false,
                        error = serverMsg ?: "Failed to create deal (HTTP ${response.code()})",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isCreating = false,
                    error = "Failed to create deal: ${e.localizedMessage}",
                )
            }
        }
    }

    fun deactivateDeal(dealId: String) {
        if (_state.value.deactivatingDealId != null) return
        _state.value = _state.value.copy(deactivatingDealId = dealId)
        viewModelScope.launch {
            try {
                val response = apiService.deactivateDeal(dealId)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(deactivatingDealId = null)
                    loadDeals()
                } else {
                    _state.value = _state.value.copy(
                        deactivatingDealId = null,
                        error = "Failed to deactivate deal",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    deactivatingDealId = null,
                    error = "Failed to deactivate deal: ${e.localizedMessage}",
                )
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

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message)
    }

    fun clearCreateSuccess() {
        _state.value = _state.value.copy(createSuccess = false)
    }
}
