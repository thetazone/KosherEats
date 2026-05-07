package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.CreateDealRequest
import com.koshereats.seller.data.models.Deal
import com.koshereats.seller.data.models.DiscountType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DealsState(
    val deals: List<Deal> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
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
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load deals: ${e.localizedMessage}",
                )
            }
        }
    }

    fun createDeal(
        title: String,
        description: String,
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
                    _state.value = _state.value.copy(
                        isCreating = false,
                        error = "Failed to create deal (HTTP ${response.code()})",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isCreating = false,
                    error = "Failed to create deal: ${e.localizedMessage}",
                )
            }
        }
    }

    fun deactivateDeal(dealId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.deactivateDeal(dealId)
                if (response.isSuccessful) {
                    loadDeals()
                } else {
                    _state.value = _state.value.copy(
                        error = "Failed to deactivate deal",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Failed to deactivate deal: ${e.localizedMessage}",
                )
            }
        }
    }

    fun clearCreateSuccess() {
        _state.value = _state.value.copy(createSuccess = false)
    }
}
