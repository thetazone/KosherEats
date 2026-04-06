import SwiftUI
import PhotosUI

// OnboardingFlowView is the driver's "get approved" funnel. It resumes at the
// correct step based on the server-side onboarding_status, so if the courier
// quits the app mid-signup they land back where they left off — same UX as
// UberEats and DoorDash driver apps.
struct OnboardingFlowView: View {
    @EnvironmentObject var auth: AuthViewModel
    let profile: CourierProfile

    @StateObject private var vm = OnboardingViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ProgressHeader(status: profile.onboardingStatus, phoneVerified: profile.phoneVerified)
                    .padding(.horizontal, Theme.spacingLG)
                    .padding(.top, Theme.spacingMD)

                ScrollView {
                    Group {
                        switch currentStep {
                        case .phone:
                            PhoneVerifyStep(vm: vm) { Task { await auth.loadProfile() } }
                        case .vehicle:
                            VehicleStep(vm: vm) { Task { await auth.loadProfile() } }
                        case .documents:
                            DocumentsStep(vm: vm) { Task { await auth.loadProfile() } }
                        case .background:
                            BackgroundCheckStep { Task { await auth.loadProfile() } }
                        }
                    }
                    .padding(Theme.spacingLG)
                }
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Log out") { auth.logout() }
                        .foregroundColor(.keTextTertiary)
                }
            }
        }
    }

    private enum Step { case phone, vehicle, documents, background }

    private var currentStep: Step {
        if !profile.phoneVerified { return .phone }
        switch profile.onboardingStatus {
        case .pendingInfo: return .vehicle
        case .pendingDocuments: return .documents
        case .pendingBackground: return .background
        default: return .background
        }
    }
}

// MARK: - Progress header

private struct ProgressHeader: View {
    let status: OnboardingStatus
    let phoneVerified: Bool

    var body: some View {
        HStack(spacing: Theme.spacingSM) {
            step("Phone", done: phoneVerified, active: !phoneVerified)
            step("Vehicle", done: phoneVerified && status != .pendingInfo,
                 active: phoneVerified && status == .pendingInfo)
            step("Documents", done: status == .pendingBackground || status == .approved,
                 active: status == .pendingDocuments)
            step("Review", done: status == .approved, active: status == .pendingBackground)
        }
    }

    private func step(_ title: String, done: Bool, active: Bool) -> some View {
        VStack(spacing: 4) {
            Circle()
                .fill(done ? Color.keSuccess : (active ? Color.kePrimary : Color.keCard))
                .frame(width: 10, height: 10)
            Text(title)
                .font(.caption2)
                .foregroundColor(done || active ? .keTextPrimary : .keTextMuted)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Phone step

private struct PhoneVerifyStep: View {
    @ObservedObject var vm: OnboardingViewModel
    var onDone: () -> Void

    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text("Verify your phone")
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)
            Text("We'll text you a 6-digit code. (Dev stub: any code works.)")
                .foregroundColor(.keTextSecondary)

            TextField("123456", text: $code)
                .keTextField()
                .keyboardType(.numberPad)

            Button("Verify") {
                Task {
                    if await vm.verifyPhone() { onDone() }
                }
            }
            .buttonStyle(KEPrimaryButtonStyle(isEnabled: !code.isEmpty))
            .disabled(code.isEmpty)

            if let err = vm.errorMessage {
                Text(err).font(.footnote).foregroundColor(.keError)
            }
        }
    }
}

// MARK: - Vehicle step

private struct VehicleStep: View {
    @ObservedObject var vm: OnboardingViewModel
    var onDone: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text("What are you driving?")
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)

            // Vehicle type picker (visual tile grid, like DoorDash)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: Theme.spacingSM) {
                ForEach(VehicleType.allCases) { type in
                    VehicleTypeTile(type: type, isSelected: vm.vehicleType == type) {
                        vm.vehicleType = type
                    }
                }
            }

            if vm.requiresVehicleDetails {
                // Car + motorcycle: full details, all required.
                Group {
                    TextField("Make (e.g. Toyota)", text: $vm.vehicleMake).keTextField()
                    TextField("Model (e.g. Camry)", text: $vm.vehicleModel).keTextField()
                    TextField("Year", text: $vm.vehicleYear).keTextField().keyboardType(.numberPad)
                    TextField("Color", text: $vm.vehicleColor).keTextField()
                    TextField("License plate", text: $vm.licensePlate).keTextField().autocapitalization(.allCharacters)
                }
            } else if vm.vehicleType == .bike || vm.vehicleType == .scooter {
                // Bike / scooter: just color for identification. No plate, no make/model required.
                TextField("Color (optional)", text: $vm.vehicleColor).keTextField()
            }
            // Walk: nothing extra to ask.

            Button("Continue") {
                Task {
                    if await vm.submitVehicle() != nil { onDone() }
                }
            }
            .buttonStyle(KEPrimaryButtonStyle(isEnabled: vm.vehicleFormValid && !vm.isSubmitting))
            .disabled(!vm.vehicleFormValid || vm.isSubmitting)

            if let err = vm.errorMessage {
                Text(err).font(.footnote).foregroundColor(.keError)
            }
        }
    }
}

private struct VehicleTypeTile: View {
    let type: VehicleType
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: Theme.spacingSM) {
                Image(systemName: type.sfSymbol)
                    .font(.system(size: 28))
                    .foregroundColor(isSelected ? .kePrimary : .keTextSecondary)
                Text(type.displayName)
                    .font(.subheadline)
                    .foregroundColor(isSelected ? .keTextPrimary : .keTextSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Theme.spacingMD)
            .background(Color.keCard)
            .overlay(
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .stroke(isSelected ? Color.kePrimary : Color.clear, lineWidth: 2)
            )
            .cornerRadius(Theme.cornerRadiusMedium)
        }
    }
}

// MARK: - Documents step

private struct DocumentsStep: View {
    @ObservedObject var vm: OnboardingViewModel
    var onDone: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text("Upload your documents")
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)
            Text("We review these as part of your background check.")
                .foregroundColor(.keTextSecondary)

            TextField("Drivers license number", text: $vm.driversLicenseNumber)
                .keTextField()
                .autocapitalization(.allCharacters)

            DocumentUploadRow(title: "Drivers license photo", kind: .license, urlBinding: $vm.driversLicenseURL)
            DocumentUploadRow(title: "Insurance card", kind: .insurance, urlBinding: $vm.insuranceURL)
            DocumentUploadRow(title: "Vehicle registration", kind: .registration, urlBinding: $vm.registrationURL)
            DocumentUploadRow(title: "Profile photo (selfie)", kind: .profile, urlBinding: $vm.profilePhotoURL)

            Button("Submit for review") {
                Task {
                    if await vm.submitDocuments() != nil { onDone() }
                }
            }
            .buttonStyle(KEPrimaryButtonStyle(isEnabled: vm.documentsFormValid && !vm.isSubmitting))
            .disabled(!vm.documentsFormValid || vm.isSubmitting)

            if let err = vm.errorMessage {
                Text(err).font(.footnote).foregroundColor(.keError)
            }
        }
    }
}

// DocumentUploadRow opens a photos picker, uploads the selected image via
// UploadService, and writes the resulting public URL into the bound string.
// An empty urlBinding means "not uploaded yet".
private struct DocumentUploadRow: View {
    let title: String
    let kind: UploadService.UploadKind
    @Binding var urlBinding: String

    @State private var pickerItem: PhotosPickerItem?
    @State private var isUploading = false
    @State private var errorText: String?

    private var uploaded: Bool { !urlBinding.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                HStack {
                    Image(systemName: iconName)
                        .foregroundColor(uploaded ? .keSuccess : .kePrimary)
                    Text(title)
                        .foregroundColor(.keTextPrimary)
                    Spacer()
                    if isUploading {
                        ProgressView().tint(.kePrimary)
                    } else {
                        Text(uploaded ? "Uploaded" : "Select photo")
                            .font(.subheadline)
                            .foregroundColor(.keTextTertiary)
                    }
                }
                .padding()
                .background(Color.keCard)
                .cornerRadius(Theme.cornerRadiusMedium)
            }
            .onChange(of: pickerItem) { _, newItem in
                Task { await handlePick(newItem) }
            }

            if let err = errorText {
                Text(err).font(.caption2).foregroundColor(.keError)
            }
        }
    }

    private var iconName: String {
        if isUploading { return "arrow.up.circle" }
        return uploaded ? "checkmark.circle.fill" : "camera.fill"
    }

    private func handlePick(_ item: PhotosPickerItem?) async {
        guard let item = item else { return }
        isUploading = true
        errorText = nil
        defer { isUploading = false }

        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                throw NSError(domain: "upload", code: 0, userInfo: [NSLocalizedDescriptionKey: "Could not load image"])
            }
            let publicURL = try await UploadService.shared.uploadImage(image, kind: kind)
            urlBinding = publicURL
        } catch {
            errorText = error.localizedDescription
        }
    }
}

// MARK: - Background check step

private struct BackgroundCheckStep: View {
    var onDone: () -> Void

    var body: some View {
        VStack(spacing: Theme.spacingLG) {
            Image(systemName: "shield.checkered")
                .font(.system(size: 64))
                .foregroundColor(.kePrimary)

            Text("Running background check")
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)

            Text("This usually takes a few minutes. We'll notify you as soon as you're approved to drive.")
                .font(.body)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)

            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))

            Button("Refresh status") { onDone() }
                .buttonStyle(KESecondaryButtonStyle())
                .padding(.top, Theme.spacingMD)
        }
        .padding(Theme.spacingLG)
        .frame(maxWidth: .infinity)
    }
}
