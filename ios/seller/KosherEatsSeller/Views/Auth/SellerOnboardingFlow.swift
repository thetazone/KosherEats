import SwiftUI
import PhotosUI

// 5-step onboarding wizard that mirrors the Android seller flow
// (android/seller/.../onboarding/OnboardingScreen.kt). Replaces the older
// monolithic CreateRestaurantView + post-create OnboardingMenuBuilderView
// pair. Single ObservableObject holds all state; each step reads/writes
// directly. Final ReviewStep does one createRestaurant call followed by
// createCategory + createMenuItem per drafted item — same submit shape as
// Android's OnboardingViewModel.submit().

// MARK: - Step model

enum OnboardingStep: Int, CaseIterable {
    case basics, address, kosher, menu, review

    var title: String {
        switch self {
        case .basics: return "Restaurant Details"
        case .address: return "Address"
        case .kosher: return "Kosher Certification"
        case .menu: return "Menu Items"
        case .review: return "Review & Submit"
        }
    }
}

struct OnboardingMenuDraft: Identifiable {
    let id = UUID()
    var name: String
    var description: String
    var priceDollars: String
    var categoryName: String
    var isMeat: Bool
    var isDairy: Bool
    var isPareve: Bool
    var imageUrl: String
}

private let onboardingMenuCategories = [
    "Appetizers", "Soups", "Salads", "Mains",
    "Sides", "Desserts", "Drinks",
]

// MARK: - ViewModel

@MainActor
final class SellerOnboardingViewModel: ObservableObject {
    @Published var step: OnboardingStep = .basics

    // Basics
    @Published var name = ""
    @Published var restaurantDescription = ""
    @Published var pictureUrl = ""
    @Published var logoUrl = ""
    @Published var phone = ""
    @Published var email = ""

    // Address
    @Published var street = ""
    @Published var city = ""
    @Published var stateField = ""
    @Published var zipCode = ""

    // Kosher
    @Published var certification: KosherCertification = .OU
    @Published var certifyingAgency = ""
    @Published var isCholovYisroel = false
    @Published var isPasYisroel = false
    @Published var isGlattKosher = false
    @Published var kosherCertificateUrl = ""

    // Menu
    @Published var menuItems: [OnboardingMenuDraft] = []

    // UI
    @Published var isSubmitting = false
    @Published var errorMessage: String?
    // Set when the restaurant is created but one or more drafted menu items
    // failed to persist. We hold the seller on the review step showing the
    // warning instead of immediately calling onComplete — otherwise the flow
    // is torn down for the "You're all set!" screen before the warning ever
    // renders a frame, and the seller never learns items were dropped.
    @Published var createdWithFailures: Restaurant?

    func nextStep() {
        guard let next = OnboardingStep(rawValue: step.rawValue + 1) else { return }
        errorMessage = nil
        step = next
    }

    func previousStep() {
        guard let prev = OnboardingStep(rawValue: step.rawValue - 1) else { return }
        errorMessage = nil
        step = prev
    }

    func addMenuItem(_ item: OnboardingMenuDraft) {
        menuItems.append(item)
    }

    func removeMenuItem(_ id: UUID) {
        menuItems.removeAll { $0.id == id }
    }

    func submit(onComplete: @escaping (Restaurant) -> Void) async {
        guard !isSubmitting else { return }
        if pictureUrl.isEmpty {
            errorMessage = "Restaurant picture is required"
            step = .basics
            return
        }
        if kosherCertificateUrl.isEmpty {
            errorMessage = "Kosher certificate photo is required"
            step = .kosher
            return
        }
        isSubmitting = true
        errorMessage = nil

        let body = APIService.CreateRestaurantBody(
            name: name.trimmingCharacters(in: .whitespaces),
            description: restaurantDescription.trimmingCharacters(in: .whitespaces),
            imageUrl: pictureUrl,
            logoUrl: logoUrl,
            phone: phone.trimmingCharacters(in: .whitespaces),
            email: email.trimmingCharacters(in: .whitespaces),
            street: street.trimmingCharacters(in: .whitespaces),
            city: city.trimmingCharacters(in: .whitespaces),
            state: stateField.trimmingCharacters(in: .whitespaces),
            zipCode: zipCode.trimmingCharacters(in: .whitespaces),
            kosherCertification: certification.rawValue,
            certifyingAgency: certifyingAgency.trimmingCharacters(in: .whitespaces),
            kosherCertificateUrl: kosherCertificateUrl,
            cuisineType: [],
            isCholovYisroel: isCholovYisroel,
            isPasYisroel: isPasYisroel,
            isGlattKosher: isGlattKosher
        )

        do {
            let created = try await APIService.shared.createRestaurant(body)

            // Persist drafted menu items grouped by category, same shape as
            // Android's OnboardingViewModel.submit().
            var failedItemCount = 0
            let grouped = Dictionary(grouping: menuItems) { $0.categoryName }
            for (categoryName, drafts) in grouped {
                let category: MenuCategory
                do {
                    category = try await APIService.shared.createCategory(categoryName)
                } catch {
                    failedItemCount += drafts.count
                    continue
                }
                for draft in drafts {
                    let cents = Int(round((Double(draft.priceDollars) ?? 0) * 100))
                    guard cents > 0, !draft.name.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
                    let req = CreateMenuItemRequest(
                        categoryId: category.id,
                        name: draft.name.trimmingCharacters(in: .whitespaces),
                        description: draft.description.trimmingCharacters(in: .whitespaces),
                        price: cents,
                        imageUrl: draft.imageUrl,
                        isMeat: draft.isMeat,
                        isDairy: draft.isDairy,
                        isPareve: draft.isPareve,
                        isAvailable: true
                    )
                    do {
                        _ = try await APIService.shared.createMenuItem(req)
                    } catch {
                        failedItemCount += 1
                    }
                }
            }

            isSubmitting = false
            if failedItemCount > 0 {
                // Hold on the review step so the seller actually sees this; the
                // "Continue to Dashboard" button (shown while createdWithFailures
                // is set) calls onComplete once they've acknowledged it.
                errorMessage = "\(failedItemCount) menu item\(failedItemCount == 1 ? "" : "s") couldn't be saved. You can re-add them from the Menu tab."
                createdWithFailures = created
            } else {
                onComplete(created)
            }
        } catch {
            isSubmitting = false
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Main flow

struct SellerOnboardingFlow: View {
    @EnvironmentObject var authVM: AuthViewModel
    @StateObject private var vm = SellerOnboardingViewModel()
    @State private var showSignOutConfirm = false
    let onComplete: (Restaurant) -> Void

    var body: some View {
        VStack(spacing: 0) {
            topBar
            progressBar
            stepCounter

            Group {
                switch vm.step {
                case .basics: BasicsStepView(vm: vm)
                case .address: AddressStepView(vm: vm)
                case .kosher: KosherStepView(vm: vm)
                case .menu: MenuStepView(vm: vm)
                case .review: ReviewStepView(vm: vm, onComplete: onComplete)
                }
            }
            .transition(.asymmetric(
                insertion: .move(edge: .trailing).combined(with: .opacity),
                removal: .move(edge: .leading).combined(with: .opacity)
            ))
        }
        .background(Color.keBackground.ignoresSafeArea())
        .animation(.easeInOut(duration: 0.25), value: vm.step)
        .confirmationDialog(
            "Use a different account?",
            isPresented: $showSignOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) { authVM.logout() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll be signed out and any progress in this onboarding flow will be lost. You can then sign in with a different Google account, phone number, Apple ID, or email.")
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            if vm.step != .basics {
                Button {
                    vm.previousStep()
                } label: {
                    Image(systemName: "arrow.backward")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.keTextPrimary)
                        .frame(width: 40, height: 40)
                }
                .accessibilityLabel("Go back")
                .accessibilityHint("Returns to the previous step")
            } else {
                Spacer().frame(width: 40)
            }
            Text(vm.step.title)
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                showSignOutConfirm = true
            } label: {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.keTextSecondary)
                    .frame(width: 40, height: 40)
            }
            .accessibilityLabel("Use a different account")
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private var progressBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.keSurface)
                    .frame(height: 4)
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.kePrimary)
                    .frame(width: geo.size.width * progressFraction, height: 4)
                    .animation(.easeInOut(duration: 0.25), value: progressFraction)
            }
        }
        .frame(height: 4)
        .padding(.horizontal, 20)
        .accessibilityElement()
        .accessibilityLabel("Progress")
        .accessibilityValue("Step \(vm.step.rawValue + 1) of \(OnboardingStep.allCases.count)")
    }

    private var progressFraction: CGFloat {
        let total = CGFloat(OnboardingStep.allCases.count)
        let idx = CGFloat(vm.step.rawValue + 1)
        return idx / total
    }

    private var stepCounter: some View {
        HStack {
            Text("Step \(vm.step.rawValue + 1) of \(OnboardingStep.allCases.count)")
                .font(.caption)
                .foregroundColor(.keTextSecondary)
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }
}

// MARK: - Step 1: Basics

private struct BasicsStepView: View {
    @ObservedObject var vm: SellerOnboardingViewModel
    @EnvironmentObject var authVM: AuthViewModel

    @State private var pictureItem: PhotosPickerItem?
    @State private var pictureImage: UIImage?
    @State private var isUploadingPicture = false
    @State private var pictureError: String?

    @State private var logoItem: PhotosPickerItem?
    @State private var logoImage: UIImage?
    @State private var isUploadingLogo = false
    @State private var logoError: String?

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Restaurant Picture *")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                Text("Required — the photo customers see in the marketplace. Pick a wide, landscape-friendly photo.")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                PhotosPicker(selection: $pictureItem, matching: .images) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.keCard)
                            .aspectRatio(16/9, contentMode: .fit)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.keSurface, lineWidth: 1))
                        if let pictureImage {
                            Image(uiImage: pictureImage)
                                .resizable()
                                .scaledToFill()
                                .aspectRatio(16/9, contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else {
                            VStack(spacing: 6) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 32))
                                    .foregroundColor(.kePrimary)
                                Text("Tap to add restaurant picture")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                            }
                        }
                        if isUploadingPicture {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.black.opacity(0.4))
                                .aspectRatio(16/9, contentMode: .fit)
                            ProgressView().tint(.white)
                        }
                    }
                }
                .onChange(of: pictureItem) { _, newItem in
                    Task { await handleUpload(newItem, kind: .restaurantCover) { url, img in
                        vm.pictureUrl = url
                        pictureImage = img
                    } onError: { err in
                        pictureError = err
                    } setUploading: { v in isUploadingPicture = v } }
                }
                if let pictureError {
                    Text(pictureError).font(.caption).foregroundColor(.keError)
                }

                Spacer().frame(height: 4)

                Text("Restaurant Logo (optional)")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                Text("Small mark shown as a badge on your card. Skip if your picture already includes your logo.")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                HStack(spacing: 14) {
                    PhotosPicker(selection: $logoItem, matching: .images) {
                        ZStack {
                            Circle()
                                .fill(Color.keCard)
                                .frame(width: 80, height: 80)
                                .overlay(Circle().stroke(Color.keSurface, lineWidth: 1))
                            if let logoImage {
                                Image(uiImage: logoImage)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 80, height: 80)
                                    .clipShape(Circle())
                            } else {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 22))
                                    .foregroundColor(.keTextMuted)
                            }
                            if isUploadingLogo {
                                Circle().fill(Color.black.opacity(0.4)).frame(width: 80, height: 80)
                                ProgressView().tint(.white)
                            }
                        }
                    }
                    .onChange(of: logoItem) { _, newItem in
                        Task { await handleUpload(newItem, kind: .restaurantLogo) { url, img in
                            vm.logoUrl = url
                            logoImage = img
                        } onError: { err in
                            logoError = err
                        } setUploading: { v in isUploadingLogo = v } }
                    }
                    Text(vm.logoUrl.isEmpty ? "Skip if your picture already includes your logo." : "Logo added")
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                    Spacer()
                }
                if let logoError {
                    Text(logoError).font(.caption).foregroundColor(.keError)
                }

                onboardingField("Restaurant Name", text: $vm.name)
                onboardingField("Description (optional)", text: $vm.restaurantDescription, axis: .vertical)
                onboardingField("Phone", text: $vm.phone, keyboard: .phonePad)
                onboardingField("Email", text: $vm.email, keyboard: .emailAddress)

                if let err = vm.errorMessage {
                    Text(err).font(.caption).foregroundColor(.keError)
                }

                continueButton(disabled: isUploadingPicture || isUploadingLogo) {
                    if vm.name.trimmingCharacters(in: .whitespaces).isEmpty {
                        vm.errorMessage = "Restaurant name is required"
                        return
                    }
                    if vm.phone.trimmingCharacters(in: .whitespaces).isEmpty {
                        vm.errorMessage = "Phone is required"
                        return
                    }
                    if vm.email.trimmingCharacters(in: .whitespaces).isEmpty || !vm.email.contains("@") {
                        vm.errorMessage = "Valid email is required"
                        return
                    }
                    if vm.pictureUrl.isEmpty {
                        vm.errorMessage = "Restaurant picture is required"
                        return
                    }
                    if isUploadingPicture || isUploadingLogo {
                        vm.errorMessage = "Photo is still uploading…"
                        return
                    }
                    vm.nextStep()
                }

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .adaptiveContentWidth(560)
        }
    }

    private func handleUpload(
        _ item: PhotosPickerItem?,
        kind: UploadService.Kind,
        onSuccess: (String, UIImage) -> Void,
        onError: (String) -> Void,
        setUploading: (Bool) -> Void
    ) async {
        guard let item else { return }
        let data: Data
        do {
            guard let loaded = try await item.loadTransferable(type: Data.self) else {
                onError("Couldn't read the selected photo.")
                return
            }
            data = loaded
        } catch {
            onError("Photo load failed: \(error.localizedDescription)")
            return
        }
        guard let image = UIImage(data: data) else {
            onError("Couldn't decode the selected image.")
            return
        }
        setUploading(true)
        do {
            let url = try await UploadService.shared.uploadImage(image, kind: kind)
            onSuccess(url, image)
        } catch {
            onError("Upload failed. Tap to retry.")
        }
        setUploading(false)
    }
}

// MARK: - Step 2: Address

private struct AddressStepView: View {
    @ObservedObject var vm: SellerOnboardingViewModel

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 14) {
                onboardingField("Street Address", text: $vm.street)
                onboardingField("City", text: $vm.city)
                HStack(spacing: 12) {
                    onboardingField("State", text: $vm.stateField)
                        .onChange(of: vm.stateField) { _, v in
                            if v.count > 2 { vm.stateField = String(v.prefix(2)).uppercased() }
                            else { vm.stateField = v.uppercased() }
                        }
                    onboardingField("Zip Code", text: $vm.zipCode, keyboard: .numberPad)
                }

                if let err = vm.errorMessage {
                    Text(err).font(.caption).foregroundColor(.keError)
                }

                continueButton {
                    if vm.street.trimmingCharacters(in: .whitespaces).isEmpty ||
                       vm.city.trimmingCharacters(in: .whitespaces).isEmpty ||
                       vm.stateField.trimmingCharacters(in: .whitespaces).isEmpty ||
                       vm.zipCode.trimmingCharacters(in: .whitespaces).isEmpty {
                        vm.errorMessage = "All address fields are required"
                        return
                    }
                    let digits = vm.zipCode.filter { $0.isNumber }
                    if digits.count != 5 {
                        vm.errorMessage = "ZIP code must be 5 digits"
                        return
                    }
                    vm.nextStep()
                }

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .adaptiveContentWidth(560)
        }
    }
}

// MARK: - Step 3: Kosher

private struct KosherStepView: View {
    @ObservedObject var vm: SellerOnboardingViewModel

    @State private var certItem: PhotosPickerItem?
    @State private var certImage: UIImage?
    @State private var isUploadingCert = false
    @State private var certError: String?

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Certification")
                    .font(.caption.bold())
                    .foregroundColor(.keTextSecondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(KosherCertification.allCases) { cert in
                            Button { vm.certification = cert } label: {
                                Text(cert.displayName)
                                    .font(.caption.bold())
                                    .foregroundColor(vm.certification == cert ? .keTextOnAccent : .keTextSecondary)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(vm.certification == cert ? Color.kePrimary : Color.keCard)
                                    .cornerRadius(8)
                            }
                        }
                    }
                }

                onboardingField("Certifying Agency (optional)", text: $vm.certifyingAgency)

                Text("Kosher Certificate Photo *")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                    .padding(.top, 4)
                Text("Required — a clear photo of your current kosher certificate.")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                PhotosPicker(selection: $certItem, matching: .images) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.keCard)
                            .aspectRatio(4/3, contentMode: .fit)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.keSurface, lineWidth: 1))
                        if let certImage {
                            Image(uiImage: certImage)
                                .resizable()
                                .scaledToFill()
                                .aspectRatio(4/3, contentMode: .fit)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else {
                            VStack(spacing: 6) {
                                Image(systemName: "doc.badge.plus")
                                    .font(.system(size: 28))
                                    .foregroundColor(.kePrimary)
                                Text("Tap to add certificate")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                            }
                        }
                        if isUploadingCert {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.black.opacity(0.4))
                                .aspectRatio(4/3, contentMode: .fit)
                            ProgressView().tint(.white)
                        }
                    }
                }
                .onChange(of: certItem) { _, newItem in
                    Task { await handleCert(newItem) }
                }
                if let certError {
                    Text(certError).font(.caption).foregroundColor(.keError)
                }

                Text("Additional Certifications")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                    .padding(.top, 4)

                kosherToggle("Cholov Yisroel", isOn: $vm.isCholovYisroel)
                kosherToggle("Pas Yisroel", isOn: $vm.isPasYisroel)
                kosherToggle("Glatt Kosher", isOn: $vm.isGlattKosher)

                if let err = vm.errorMessage {
                    Text(err).font(.caption).foregroundColor(.keError)
                }

                continueButton(disabled: isUploadingCert) {
                    if vm.kosherCertificateUrl.isEmpty {
                        vm.errorMessage = "Kosher certificate photo is required — sellers must upload a clear photo of their current cert before continuing."
                        return
                    }
                    vm.nextStep()
                }

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .adaptiveContentWidth(560)
        }
    }

    private func kosherToggle(_ label: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.keTextPrimary)
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(.kePrimary)
        }
    }

    private func handleCert(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        let data: Data
        do {
            guard let loaded = try await item.loadTransferable(type: Data.self) else {
                certError = "Couldn't read the selected photo."
                return
            }
            data = loaded
        } catch {
            certError = "Photo load failed: \(error.localizedDescription)"
            return
        }
        guard let image = UIImage(data: data) else {
            certError = "Couldn't decode the selected image."
            return
        }
        certImage = image
        certError = nil
        isUploadingCert = true
        do {
            vm.kosherCertificateUrl = try await UploadService.shared.uploadImage(image, kind: .certificate)
        } catch {
            certError = "Upload failed. Tap to retry."
        }
        isUploadingCert = false
    }
}

// MARK: - Step 4: Menu

private struct MenuStepView: View {
    @ObservedObject var vm: SellerOnboardingViewModel
    @State private var showForm = false

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Add your menu items so they're ready when you launch.")
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)

                ForEach(vm.menuItems) { item in
                    menuItemCard(item)
                }

                if showForm {
                    AddMenuItemForm(
                        onAdd: { draft in
                            vm.addMenuItem(draft)
                            showForm = false
                        },
                        onCancel: { showForm = false }
                    )
                } else {
                    Button { showForm = true } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "plus.circle.fill")
                            Text("Add Menu Item")
                        }
                        .font(.subheadline.bold())
                        .foregroundColor(.kePrimary)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Color.kePrimary.opacity(0.1))
                        .cornerRadius(12)
                    }
                }

                continueButton(label: "Continue to Review") {
                    vm.nextStep()
                }

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .adaptiveContentWidth(560)
        }
    }

    private func menuItemCard(_ item: OnboardingMenuDraft) -> some View {
        HStack(spacing: 12) {
            if !item.imageUrl.isEmpty, let url = URL(string: item.imageUrl) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Color.keSurface
                    }
                }
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                HStack(spacing: 6) {
                    Text(formatPrice(item.priceDollars))
                        .foregroundColor(.kePrimary)
                    Text("·").foregroundColor(.keTextMuted)
                    Text(item.categoryName)
                        .foregroundColor(.keTextSecondary)
                }
                .font(.caption)
                let kosherLabel: String = item.isMeat ? "Meat" : item.isDairy ? "Dairy" : item.isPareve ? "Pareve" : ""
                if !kosherLabel.isEmpty {
                    Text(kosherLabel)
                        .font(.caption)
                        .foregroundColor(.kePrimary)
                }
            }
            Spacer()
            Button {
                vm.removeMenuItem(item.id)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.keError.opacity(0.7))
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }
}

private struct AddMenuItemForm: View {
    let onAdd: (OnboardingMenuDraft) -> Void
    let onCancel: () -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var priceText = ""
    @State private var selectedCategory = "Mains"
    @State private var isMeat = false
    @State private var isDairy = false
    @State private var isPareve = false
    @State private var error: String?

    @State private var imageItem: PhotosPickerItem?
    @State private var imagePreview: UIImage?
    @State private var imageUrl = ""
    @State private var isUploadingImage = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("New Menu Item")
                .font(.subheadline.bold())
                .foregroundColor(.keTextPrimary)

            PhotosPicker(selection: $imageItem, matching: .images) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.keSurface)
                        .frame(height: 140)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.keCard, lineWidth: 1))
                    if let imagePreview {
                        Image(uiImage: imagePreview)
                            .resizable()
                            .scaledToFill()
                            .frame(height: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        VStack(spacing: 4) {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 26))
                                .foregroundColor(.keTextMuted)
                            Text("Add photo")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)
                        }
                    }
                    if isUploadingImage {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.black.opacity(0.4))
                            .frame(height: 140)
                        ProgressView().tint(.white)
                    }
                }
            }
            .onChange(of: imageItem) { _, newItem in
                Task {
                    guard let newItem else { return }
                    let data: Data
                    do {
                        guard let loaded = try await newItem.loadTransferable(type: Data.self) else {
                            self.error = "Couldn't read the selected photo."
                            return
                        }
                        data = loaded
                    } catch {
                        self.error = "Photo load failed: \(error.localizedDescription)"
                        return
                    }
                    guard let image = UIImage(data: data) else {
                        self.error = "Couldn't decode the selected image."
                        return
                    }
                    imagePreview = image
                    isUploadingImage = true
                    do {
                        imageUrl = try await UploadService.shared.uploadImage(image, kind: .menuItem)
                    } catch {
                        self.error = "Image upload failed. You can still add the item."
                    }
                    isUploadingImage = false
                }
            }

            onboardingField("Item Name", text: $name)
            onboardingField("Description (optional)", text: $description, axis: .vertical)

            HStack(spacing: 12) {
                onboardingField("Price ($)", text: $priceText, keyboard: .decimalPad)
                categoryPicker
            }

            Text("Kosher Type")
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            HStack(spacing: 16) {
                kosherToggle("Meat", isOn: $isMeat) { isDairy = false; isPareve = false }
                kosherToggle("Dairy", isOn: $isDairy) { isMeat = false; isPareve = false }
                kosherToggle("Pareve", isOn: $isPareve) { isMeat = false; isDairy = false }
            }

            if let error {
                Text(error).font(.caption).foregroundColor(.keError)
            }

            HStack(spacing: 12) {
                Button("Cancel") { onCancel() }
                    .font(.subheadline)
                    .foregroundColor(.keTextMuted)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Color.keSurface)
                    .cornerRadius(10)
                Button {
                    if name.trimmingCharacters(in: .whitespaces).isEmpty {
                        error = "Name is required"; return
                    }
                    if (Double(priceText) ?? 0) <= 0 {
                        error = "Enter a valid price"; return
                    }
                    if !isMeat && !isDairy && !isPareve {
                        error = "Select a kosher type"; return
                    }
                    onAdd(OnboardingMenuDraft(
                        name: name, description: description,
                        priceDollars: priceText, categoryName: selectedCategory,
                        isMeat: isMeat, isDairy: isDairy, isPareve: isPareve,
                        imageUrl: imageUrl
                    ))
                } label: {
                    Text("Add")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.kePrimary)
                        .cornerRadius(10)
                }
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private var categoryPicker: some View {
        Menu {
            ForEach(onboardingMenuCategories, id: \.self) { cat in
                Button(cat) { selectedCategory = cat }
            }
        } label: {
            HStack {
                Text(selectedCategory)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .foregroundColor(.keTextMuted)
                    .font(.caption)
            }
            .padding()
            .background(Color.keSurface)
            .cornerRadius(10)
        }
    }

    private func kosherToggle(_ label: String, isOn: Binding<Bool>, clearOthers: @escaping () -> Void) -> some View {
        Button {
            if !isOn.wrappedValue { clearOthers() }
            isOn.wrappedValue.toggle()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: isOn.wrappedValue ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isOn.wrappedValue ? .kePrimary : .keTextMuted)
                Text(label)
                    .font(.caption)
                    .foregroundColor(.keTextPrimary)
            }
        }
    }
}

// MARK: - Step 5: Review

private struct ReviewStepView: View {
    @ObservedObject var vm: SellerOnboardingViewModel
    let onComplete: (Restaurant) -> Void

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 16) {
                reviewSection("Restaurant") {
                    reviewRow("Name", vm.name)
                    if !vm.restaurantDescription.isEmpty {
                        reviewRow("Description", vm.restaurantDescription)
                    }
                    reviewRow("Phone", vm.phone)
                    reviewRow("Email", vm.email)
                }

                reviewSection("Address") {
                    reviewRow("Street", vm.street)
                    reviewRow("City", vm.city)
                    reviewRow("State", vm.stateField)
                    reviewRow("Zip Code", vm.zipCode)
                }

                reviewSection("Kosher") {
                    reviewRow("Certification", vm.certification.displayName)
                    if !vm.certifyingAgency.isEmpty {
                        reviewRow("Agency", vm.certifyingAgency)
                    }
                    if vm.isCholovYisroel { reviewCheckmark("Cholov Yisroel") }
                    if vm.isPasYisroel { reviewCheckmark("Pas Yisroel") }
                    if vm.isGlattKosher { reviewCheckmark("Glatt Kosher") }
                }

                reviewSection("Menu (\(vm.menuItems.count) items)") {
                    if vm.menuItems.isEmpty {
                        Text("No menu items added. You can add them later from the Menu tab.")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                    }
                    ForEach(vm.menuItems) { item in
                        HStack {
                            Text(item.name)
                                .foregroundColor(.keTextPrimary)
                            Spacer()
                            Text(formatPrice(item.priceDollars))
                                .foregroundColor(.kePrimary)
                        }
                        .font(.subheadline)
                        .padding(.vertical, 2)
                    }
                }

                if let err = vm.errorMessage {
                    Text(err).font(.caption).foregroundColor(.keError)
                }

                if let created = vm.createdWithFailures {
                    // Restaurant is already created and submitted — only some
                    // menu items failed. Don't let them re-submit; let them
                    // acknowledge the warning above and move on.
                    Button {
                        onComplete(created)
                    } label: {
                        Text("Continue to Dashboard")
                            .font(.headline)
                            .foregroundColor(.keTextOnAccent)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(Color.kePrimary)
                            .cornerRadius(14)
                    }
                } else {
                    Button {
                        Task { await vm.submit(onComplete: onComplete) }
                    } label: {
                        Group {
                            if vm.isSubmitting {
                                ProgressView().tint(.white)
                            } else {
                                Text("Submit for Review").font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(vm.isSubmitting ? Color.kePrimary.opacity(0.4) : Color.kePrimary)
                        .cornerRadius(14)
                    }
                    .disabled(vm.isSubmitting)
                }

                Spacer().frame(height: 40)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .adaptiveContentWidth(560)
        }
    }

    private func reviewSection<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.bold())
                .foregroundColor(.kePrimary)
            content()
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private func reviewRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .foregroundColor(.keTextSecondary)
            Spacer()
            Text(value)
                .foregroundColor(.keTextPrimary)
                .multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
        .padding(.vertical, 2)
    }

    private func reviewCheckmark(_ value: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.kePrimary)
                .font(.caption)
            Text(value)
                .foregroundColor(.keTextPrimary)
        }
        .font(.subheadline)
        .padding(.vertical, 2)
    }
}

// MARK: - Shared helpers

private func onboardingField(
    _ placeholder: String,
    text: Binding<String>,
    keyboard: UIKeyboardType = .default,
    axis: Axis = .horizontal
) -> some View {
    TextField(placeholder, text: text, axis: axis)
        .keyboardType(keyboard)
        .textInputAutocapitalization(keyboard == .emailAddress ? .never : .sentences)
        .foregroundColor(.keTextPrimary)
        .padding()
        .background(Color.keSurface)
        .cornerRadius(10)
}

private func continueButton(
    label: String = "Continue",
    disabled: Bool = false,
    action: @escaping () -> Void
) -> some View {
    Button(action: action) {
        Text(label)
            .font(.headline)
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(disabled ? Color.kePrimary.opacity(0.4) : Color.kePrimary)
            .cornerRadius(14)
    }
    .disabled(disabled)
}

private func formatPrice(_ dollars: String) -> String {
    guard let d = Double(dollars) else { return "$0.00" }
    return String(format: "$%.2f", d)
}
