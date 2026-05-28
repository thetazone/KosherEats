package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.CreateRestaurantRequest
import com.koshereats.seller.data.models.KosherCertification
import com.koshereats.seller.data.models.Restaurant
import com.koshereats.seller.data.models.PresignResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateRestaurantState(
    // Basics
    val name: String = "",
    val description: String = "",

    // Contact
    val phone: String = "",
    val email: String = "",

    // Address
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",

    // Kosher Certification
    val kosherCertification: KosherCertification = KosherCertification.OU,
    val certifyingAgency: String = "",
    val isCholovYisroel: Boolean = false,
    val isPasYisroel: Boolean = false,
    val isGlattKosher: Boolean = false,

    // Certificate upload
    val certificateUrl: String = "",
    val isUploadingCertificate: Boolean = false,
    val certificateError: String? = null,

    // Cuisine
    val cuisineTags: String = "",

    // Submission
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val createdRestaurant: Restaurant? = null,
) {
    val isFormValid: Boolean
        get() = name.isNotBlank() &&
            phone.isNotBlank() &&
            email.isNotBlank() &&
            email.contains("@") &&
            street.isNotBlank() &&
            city.isNotBlank() &&
            state.isNotBlank() &&
            zipCode.isNotBlank()

    /** Returns a user-facing validation error, or null if the form is valid. */
    val validationError: String?
        get() = when {
            name.isBlank() -> "Restaurant name is required"
            phone.isBlank() -> "Phone number is required"
            email.isBlank() -> "Email is required"
            !email.contains("@") -> "Enter a valid email address"
            street.isBlank() -> "Street address is required"
            city.isBlank() -> "City is required"
            state.isBlank() -> "State is required"
            zipCode.isBlank() -> "ZIP code is required"
            else -> null
        }
}

@HiltViewModel
class CreateRestaurantViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateRestaurantState())
    val state: StateFlow<CreateRestaurantState> = _state.asStateFlow()

    // --- Field updaters ---

    fun updateName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun updateDescription(value: String) {
        if (value.length <= 2000) {
            _state.value = _state.value.copy(description = value)
        }
    }

    fun updatePhone(value: String) {
        _state.value = _state.value.copy(phone = value)
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value)
    }

    fun updateStreet(value: String) {
        _state.value = _state.value.copy(street = value)
    }

    fun updateCity(value: String) {
        _state.value = _state.value.copy(city = value)
    }

    fun updateState(value: String) {
        _state.value = _state.value.copy(state = value)
    }

    fun updateZipCode(value: String) {
        _state.value = _state.value.copy(zipCode = value.filter { it.isDigit() })
    }

    fun updateKosherCertification(value: KosherCertification) {
        _state.value = _state.value.copy(kosherCertification = value)
    }

    fun updateCertifyingAgency(value: String) {
        _state.value = _state.value.copy(certifyingAgency = value)
    }

    fun toggleCholovYisroel() {
        _state.value = _state.value.copy(isCholovYisroel = !_state.value.isCholovYisroel)
    }

    fun togglePasYisroel() {
        _state.value = _state.value.copy(isPasYisroel = !_state.value.isPasYisroel)
    }

    fun toggleGlattKosher() {
        _state.value = _state.value.copy(isGlattKosher = !_state.value.isGlattKosher)
    }

    fun updateCuisineTags(value: String) {
        _state.value = _state.value.copy(cuisineTags = value)
    }

    fun updateCertificateUrl(url: String) {
        _state.value = _state.value.copy(certificateUrl = url, certificateError = null)
    }

    fun setUploadingCertificate(uploading: Boolean) {
        _state.value = _state.value.copy(isUploadingCertificate = uploading)
    }

    fun setCertificateError(error: String?) {
        _state.value = _state.value.copy(certificateError = error)
    }

    suspend fun presignUpload(kind: String, contentType: String): PresignResponse? {
        return try {
            val response = apiService.presignUpload(
                mapOf("kind" to kind, "content_type" to contentType),
            )
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // --- Submission ---

    fun submit() {
        val s = _state.value
        if (s.isSubmitting) return
        s.validationError?.let { error ->
            _state.value = s.copy(error = error)
            return
        }

        val cuisineList = s.cuisineTags
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val request = CreateRestaurantRequest(
            name = s.name.trim(),
            description = s.description.trim(),
            phone = s.phone.trim(),
            email = s.email.trim(),
            street = s.street.trim(),
            city = s.city.trim(),
            state = s.state.trim(),
            zipCode = s.zipCode.trim(),
            kosherCertification = s.kosherCertification,
            certifyingAgency = s.certifyingAgency.trim(),
            cuisineType = cuisineList,
            isCholovYisroel = s.isCholovYisroel,
            isPasYisroel = s.isPasYisroel,
            isGlattKosher = s.isGlattKosher,
            kosherCertificateUrl = s.certificateUrl,
        )

        viewModelScope.launch {
            _state.value = s.copy(isSubmitting = true, error = null)
            try {
                val response = apiService.createRestaurant(request)
                if (response.isSuccessful) {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        createdRestaurant = response.body(),
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        error = errorBody?.take(200)
                            ?: "Failed to create restaurant (HTTP ${response.code()})",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isSubmitting = false,
                    error = "Connection error: ${e.localizedMessage}",
                )
            }
        }
    }
}
