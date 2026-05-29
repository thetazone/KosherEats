package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.CustomerBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
                    _uiState.update { it.copy(isLoading = false, error = "$ERR_LOAD_STRIPE_CUSTOMER (${resp.code()})") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: ERR_NETWORK) }
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

    companion object {
        const val ERR_LOAD_STRIPE_CUSTOMER = "Couldn't load Stripe customer"
        const val ERR_NETWORK = "Network error"
    }
}
