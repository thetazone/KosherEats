package com.koshereats.consumer.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.Address
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddressUiState(
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddressViewModel @Inject constructor(
    private val apiService: ApiService,
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddressUiState())
    val uiState: StateFlow<AddressUiState> = _uiState.asStateFlow()

    init {
        loadAddresses()
    }

    fun loadAddresses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = apiService.getAddresses()
                if (response.isSuccessful) {
                    val addresses = response.body() ?: emptyList()
                    val savedId = dataStore.data.first()[SELECTED_ADDRESS_ID]
                    val selected = addresses.firstOrNull { it.id == savedId }
                        ?: addresses.firstOrNull { it.isDefault }
                        ?: addresses.firstOrNull()
                    _uiState.update {
                        it.copy(
                            addresses = addresses,
                            selectedAddress = selected,
                            isLoading = false,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Couldn't load addresses") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun selectAddress(address: Address) {
        _uiState.update { it.copy(selectedAddress = address) }
        viewModelScope.launch {
            dataStore.edit { it[SELECTED_ADDRESS_ID] = address.id }
        }
    }

    fun addAddress(address: Address) {
        viewModelScope.launch {
            try {
                val response = apiService.addAddress(address)
                if (response.isSuccessful) {
                    val saved = response.body() ?: return@launch
                    _uiState.update {
                        it.copy(
                            addresses = it.addresses + saved,
                            selectedAddress = saved,
                        )
                    }
                    dataStore.edit { it[SELECTED_ADDRESS_ID] = saved.id }
                } else {
                    _uiState.update { it.copy(error = "Couldn't add address (${response.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun deleteAddress(addressId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.deleteAddress(addressId)
                if (response.isSuccessful) {
                    _uiState.update { state ->
                        val remaining = state.addresses.filter { it.id != addressId }
                        val newSelected = if (state.selectedAddress?.id == addressId) {
                            remaining.firstOrNull { it.isDefault } ?: remaining.firstOrNull()
                        } else {
                            state.selectedAddress
                        }
                        state.copy(addresses = remaining, selectedAddress = newSelected)
                    }
                } else {
                    _uiState.update { it.copy(error = "Couldn't delete address (${response.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun setDefault(addressId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.setDefaultAddress(addressId)
                if (response.isSuccessful) {
                    _uiState.update { state ->
                        val updated = state.addresses.map { it.copy(isDefault = it.id == addressId) }
                        state.copy(addresses = updated)
                    }
                } else {
                    _uiState.update { it.copy(error = "Couldn't set default address (${response.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun clearDefault(addressId: String) {
        viewModelScope.launch {
            try {
                val response = apiService.clearDefaultAddress(addressId)
                if (response.isSuccessful) {
                    _uiState.update { state ->
                        val updated = state.addresses.map {
                            if (it.id == addressId) it.copy(isDefault = false) else it
                        }
                        state.copy(addresses = updated)
                    }
                } else {
                    _uiState.update { it.copy(error = "Couldn't update address (${response.code()})") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    companion object {
        private val SELECTED_ADDRESS_ID = stringPreferencesKey("selected_address_id")
    }
}
