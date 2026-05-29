package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.RateOrderRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RatingUiState(
    val stars: Int = 5,
    val comment: String = "",
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RatingViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RatingUiState())
    val uiState: StateFlow<RatingUiState> = _uiState.asStateFlow()

    fun setStars(value: Int) = _uiState.update { it.copy(stars = value.coerceIn(1, 5)) }
    fun setComment(value: String) = _uiState.update { it.copy(comment = value.take(500)) }

    fun submit(orderId: String) {
        if (orderId.isBlank()) {
            _uiState.update { it.copy(error = "Order ID is required.") }
            return
        }
        val state = _uiState.value
        if (state.isSubmitting || state.submitted) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = api.rateOrder(
                    orderId,
                    RateOrderRequest(stars = state.stars, comment = state.comment),
                )
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(isSubmitting = false, submitted = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = "Couldn't submit (${resp.code()})",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isSubmitting = false, error = "Something went wrong. Please try again.") }
            }
        }
    }
}
