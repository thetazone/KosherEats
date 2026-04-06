package com.koshereats.courier.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.koshereats.courier.data.models.CourierProfile
import com.koshereats.courier.data.models.UpdateDocumentsRequest
import com.koshereats.courier.data.models.UpdateVehicleRequest
import com.koshereats.courier.data.models.VehicleType
import com.koshereats.courier.data.repository.CourierRepository
import com.koshereats.courier.services.UploadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OnboardingViewModel holds the in-progress form state for the 4-step
 * courier onboarding funnel: phone verify -> vehicle -> documents -> background.
 *
 * The document URL fields stay empty until UploadService returns a real
 * public URL (or a "stub://" dev placeholder) — that's how the submit
 * button knows whether the user actually captured photos.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val courierRepository: CourierRepository,
    private val uploadService: UploadService,
) : ViewModel() {

    data class State(
        // Vehicle
        val vehicleType: VehicleType = VehicleType.CAR,
        val vehicleMake: String = "",
        val vehicleModel: String = "",
        val vehicleYear: String = "",
        val vehicleColor: String = "",
        val licensePlate: String = "",
        // Documents
        val driversLicenseNumber: String = "",
        val driversLicenseUrl: String = "",
        val insuranceUrl: String = "",
        val registrationUrl: String = "",
        val profilePhotoUrl: String = "",

        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setVehicleType(t: VehicleType) = _state.update { it.copy(vehicleType = t) }
    fun setVehicleMake(v: String) = _state.update { it.copy(vehicleMake = v) }
    fun setVehicleModel(v: String) = _state.update { it.copy(vehicleModel = v) }
    fun setVehicleYear(v: String) = _state.update { it.copy(vehicleYear = v) }
    fun setVehicleColor(v: String) = _state.update { it.copy(vehicleColor = v) }
    fun setLicensePlate(v: String) = _state.update { it.copy(licensePlate = v) }
    fun setDriversLicenseNumber(v: String) = _state.update { it.copy(driversLicenseNumber = v) }

    fun setDocumentUrl(kind: DocumentKind, url: String) = _state.update {
        when (kind) {
            DocumentKind.LICENSE -> it.copy(driversLicenseUrl = url)
            DocumentKind.INSURANCE -> it.copy(insuranceUrl = url)
            DocumentKind.REGISTRATION -> it.copy(registrationUrl = url)
            DocumentKind.PROFILE -> it.copy(profilePhotoUrl = url)
        }
    }

    /**
     * Uploads a picked image via UploadService and writes the resulting
     * public URL into the matching form field. Called from the compose
     * document picker. Errors are surfaced via errorMessage.
     */
    fun uploadDocument(uri: Uri, vmKind: DocumentKind, uploadKind: UploadService.Kind) = viewModelScope.launch {
        _state.update { it.copy(errorMessage = null) }
        uploadService.uploadImage(uri, uploadKind)
            .onSuccess { url -> setDocumentUrl(vmKind, url) }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message ?: "Upload failed") } }
    }

    /** Only car + motorcycle need make/model/license plate. The rest skip that section. */
    val requiresVehicleDetails: Boolean
        get() = _state.value.vehicleType == VehicleType.CAR || _state.value.vehicleType == VehicleType.MOTORCYCLE

    val vehicleFormValid: Boolean
        get() {
            val s = _state.value
            return when (s.vehicleType) {
                VehicleType.WALK -> true
                VehicleType.BIKE, VehicleType.SCOOTER -> true // color is optional, no plate needed
                VehicleType.CAR, VehicleType.MOTORCYCLE ->
                    s.vehicleMake.isNotBlank() && s.vehicleModel.isNotBlank() && s.licensePlate.isNotBlank()
            }
        }

    val documentsFormValid: Boolean
        get() {
            val s = _state.value
            return s.driversLicenseNumber.isNotBlank() && s.driversLicenseUrl.isNotBlank()
        }

    fun verifyPhone(onDone: () -> Unit) = viewModelScope.launch {
        courierRepository.verifyPhone()
            .onSuccess { onDone() }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
    }

    fun submitVehicle(onSuccess: (CourierProfile) -> Unit) = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        val req = UpdateVehicleRequest(
            vehicleType = s.vehicleType.name.lowercase(),
            vehicleMake = s.vehicleMake,
            vehicleModel = s.vehicleModel,
            vehicleYear = s.vehicleYear.toIntOrNull() ?: 0,
            vehicleColor = s.vehicleColor,
            licensePlate = s.licensePlate,
        )
        courierRepository.updateVehicle(req)
            .onSuccess { onSuccess(it) }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        _state.update { it.copy(isSubmitting = false) }
    }

    fun submitDocuments(onSuccess: (CourierProfile) -> Unit) = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        val req = UpdateDocumentsRequest(
            driversLicenseUrl = s.driversLicenseUrl,
            driversLicenseNumber = s.driversLicenseNumber,
            insuranceUrl = s.insuranceUrl,
            vehicleRegistrationUrl = s.registrationUrl,
            profilePhotoUrl = s.profilePhotoUrl,
        )
        courierRepository.updateDocuments(req)
            .onSuccess { onSuccess(it) }
            .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
        _state.update { it.copy(isSubmitting = false) }
    }

    enum class DocumentKind { LICENSE, INSURANCE, REGISTRATION, PROFILE }
}
