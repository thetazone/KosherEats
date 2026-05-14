import Foundation

@MainActor
final class OnboardingViewModel: ObservableObject {
    @Published var vehicleType: VehicleType = .car
    @Published var vehicleMake: String = ""
    @Published var vehicleModel: String = ""
    @Published var vehicleYear: String = ""
    @Published var vehicleColor: String = ""
    @Published var licensePlate: String = ""

    @Published var driversLicenseNumber: String = ""
    // Post-upload URLs returned by UploadService (empty until uploaded).
    @Published var driversLicenseURL: String = ""
    @Published var insuranceURL: String = ""
    @Published var registrationURL: String = ""
    @Published var profilePhotoURL: String = ""

    @Published var isSubmitting: Bool = false
    @Published var errorMessage: String?

    private let api = APIService.shared

    /// Only motorized 4-wheel + motorcycle need make/model/plate. Scooters,
    /// bicycles, and on-foot couriers skip that whole block entirely.
    var requiresVehicleDetails: Bool {
        vehicleType == .car || vehicleType == .motorcycle
    }

    var vehicleFormValid: Bool {
        switch vehicleType {
        case .walk:
            return true
        case .bike, .scooter:
            // Color is optional but nice to have for rider identification.
            return true
        case .car, .motorcycle:
            return !vehicleMake.isEmpty && !vehicleModel.isEmpty && !licensePlate.isEmpty
        }
    }

    /// What documents are required depends on the vehicle type — bike/walk
    /// couriers don't need insurance or vehicle registration, but cars and
    /// motorcycles do (matches DoorDash/UberEats requirements). ID photo +
    /// profile photo are universal; the license *number* is only collected
    /// for car/motorcycle couriers (bike/scooter/walk show a government-ID
    /// upload instead, no number field).
    var documentsFormValid: Bool {
        guard !driversLicenseURL.isEmpty, !profilePhotoURL.isEmpty else { return false }
        switch vehicleType {
        case .car, .motorcycle:
            return !driversLicenseNumber.isEmpty && !insuranceURL.isEmpty && !registrationURL.isEmpty
        case .bike, .scooter, .walk:
            return true
        }
    }

    /// Drives the per-row "required vs. optional" label in DocumentsStep so
    /// bike/walk couriers don't waste time hunting for an insurance card.
    func documentRequired(_ kind: UploadService.UploadKind) -> Bool {
        switch kind {
        case .license, .profile:
            return true
        case .insurance, .registration:
            return vehicleType == .car || vehicleType == .motorcycle
        case .deliveryProof:
            return false
        }
    }

    func submitVehicle() async -> CourierProfile? {
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let body = APIService.VehicleBody(
            vehicleType: vehicleType.rawValue,
            vehicleMake: vehicleMake,
            vehicleModel: vehicleModel,
            vehicleYear: Int(vehicleYear) ?? 0,
            vehicleColor: vehicleColor,
            licensePlate: licensePlate
        )
        do {
            return try await api.updateVehicle(body)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return nil
        }
    }

    func submitDocuments() async -> CourierProfile? {
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        // Real URLs — populated via UploadService after the user picks/captures images.
        let body = APIService.DocumentsBody(
            driversLicenseUrl: driversLicenseURL,
            driversLicenseNumber: driversLicenseNumber,
            insuranceUrl: insuranceURL,
            vehicleRegistrationUrl: registrationURL,
            profilePhotoUrl: profilePhotoURL
        )
        do {
            return try await api.updateDocuments(body)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return nil
        }
    }

    func verifyPhone(code: String) async -> Bool {
        do {
            try await api.verifyPhone(code: code)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return false
        }
    }
}
