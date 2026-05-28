package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.CustomerBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

data class PaymentMethodsUiState(
    val bundle: CustomerBundle? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PaymentMethodsViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentMethodsUiState())
    val uiState: StateFlow<PaymentMethodsUiState> = _uiState.asStateFlow()

    init { loadBundle() }

    fun loadBundle() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val resp = api.getPaymentCustomer()
                if (resp.isSuccessful) {
                    _uiState.update { it.copy(bundle = resp.body(), isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load Stripe customer (${resp.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    suspend fun fetchSetupIntentClientSecret(): String? = try {
        val resp = api.createSetupIntent()
        if (resp.isSuccessful) resp.body()?.clientSecret else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}
