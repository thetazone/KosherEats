import SwiftUI
import PhotosUI

// Shown to authenticated sellers who don't yet own a restaurant — the
// post-Phase-2 self-service "create your first restaurant" flow. Replaces the
// older email-application path for new sellers; SellerOnboardingView is now
// reserved for the (rare) case where someone lands on the seller app without
// seller role at all.
//
// Single screen, all required fields, no multi-step wizard — keeps the path
// short for App Review and real first-time sellers. lat/lng default to NYC
// at the backend; the seller can correct that in Settings later.
//
// Required photos: restaurant picture (hero shown on consumer cards) and
// kosher certificate. Optional: logo badge (small mark distinct from the
// picture). The PhotosPicker built-in editor lets the seller crop before
// upload — we keep the picker rendered as fixed-aspect-ratio frames so the
// preview matches how consumers will see it.
struct CreateRestaurantView: View {
    @EnvironmentObject var authVM: AuthViewModel
    let onCreated: (Restaurant) -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var phone = ""
    @State private var email = ""
    @State private var street = ""
    @State private var city = ""
    @State private var stateField = ""
    @State private var zipCode = ""
    @State private var kosherCert: KosherCertification = .OU
    @State private var certifyingAgency = ""
    @State private var cuisineInput = ""
    @State private var isCholovYisroel = false
    @State private var isPasYisroel = false
    @State private var isGlattKosher = false

    // Required: hero picture shown to consumers on the marketplace card.
    @State private var pictureItem: PhotosPickerItem?
    @State private var pictureImage: UIImage?
    @State private var pictureUrl = ""
    @State private var isUploadingPicture = false
    @State private var pictureUploadError: String?

    // Optional: small logo badge shown on the consumer card.
    @State private var logoItem: PhotosPickerItem?
    @State private var logoImage: UIImage?
    @State private var logoUrl = ""
    @State private var isUploadingLogo = false
    @State private var logoUploadError: String?

    // Required: kosher certificate photo.
    @State private var certItem: PhotosPickerItem?
    @State private var certImage: UIImage?
    @State private var kosherCertificateUrl = ""
    @State private var isUploadingCert = false
    @State private var certUploadError: String?

    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private var canSubmit: Bool {
        !isSubmitting && !isUploadingPicture && !isUploadingLogo && !isUploadingCert &&
            !pictureUrl.isEmpty &&
            !kosherCertificateUrl.isEmpty &&
            !name.trimmingCharacters(in: .whitespaces).isEmpty &&
            !street.trimmingCharacters(in: .whitespaces).isEmpty &&
            !city.trimmingCharacters(in: .whitespaces).isEmpty &&
            !stateField.trimmingCharacters(in: .whitespaces).isEmpty &&
            !zipCode.trimmingCharacters(in: .whitespaces).isEmpty &&
            !phone.trimmingCharacters(in: .whitespaces).isEmpty &&
            !email.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 24) {
                    header

                    pictureSection
                    logoSection

                    section("Basics", icon: "storefront.fill") {
                        textField("Restaurant Name *", text: $name)
                        textField("Short Description", text: $description, axis: .vertical)
                    }

                    section("Contact", icon: "phone.fill") {
                        textField("Phone *", text: $phone, keyboard: .phonePad)
                        textField("Email *", text: $email, keyboard: .emailAddress)
                    }

                    section("Address", icon: "mappin.and.ellipse") {
                        textField("Street *", text: $street)
                        HStack(spacing: 12) {
                            textField("City *", text: $city)
                            textField("State *", text: $stateField).frame(width: 90)
                        }
                        textField("ZIP Code *", text: $zipCode, keyboard: .numberPad)
                    }

                    section("Kosher Certification", icon: "checkmark.seal.fill") {
                        Text("Certification *")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(Array(KosherCertification.allCases), id: \.rawValue) { cert in
                                    Button { kosherCert = cert } label: {
                                        Text(cert.displayName)
                                            .font(.caption.bold())
                                            .foregroundColor(kosherCert == cert ? .white : .keTextSecondary)
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 8)
                                            .background(kosherCert == cert ? Color.kePrimary : Color.keCard)
                                            .cornerRadius(8)
                                    }
                                }
                            }
                        }

                        textField("Certifying Agency", text: $certifyingAgency)

                        kosherToggleRow("Cholov Yisroel", isOn: $isCholovYisroel)
                        kosherToggleRow("Pas Yisroel", isOn: $isPasYisroel)
                        kosherToggleRow("Glatt Kosher", isOn: $isGlattKosher)

                        certificatePicker
                    }

                    section("Cuisine", icon: "fork.knife") {
                        textField("Comma-separated tags (e.g. Israeli, Grill)", text: $cuisineInput)
                            .autocorrectionDisabled()
                    }

                    if let error = errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.keError)
                    }

                    submitButton

                    Button("Sign out") { authVM.logout() }
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 8)

                    Spacer().frame(height: 40)
                }
                .padding(20)
                .adaptiveContentWidth(560)
            }
        }
    }

    // MARK: - Picture (Required, Rectangle)

    private var pictureSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "photo.fill").foregroundColor(.kePrimary)
                Text("Restaurant Picture *")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
            }
            Text("Required — the photo customers see in the marketplace. Tap to pick, then crop in the Photos editor for best fit.")
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
                Task { await handlePicture(newItem) }
            }

            if let pictureUploadError {
                Text(pictureUploadError).font(.caption).foregroundColor(.keError)
            }
        }
    }

    private func handlePicture(_ item: PhotosPickerItem?) async {
        guard let item,
              let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data) else { return }
        pictureImage = image
        pictureUploadError = nil
        isUploadingPicture = true
        do {
            pictureUrl = try await UploadService.shared.uploadImage(image, kind: .restaurantCover)
        } catch {
            pictureUploadError = "Upload failed. Tap to retry."
        }
        isUploadingPicture = false
    }

    // MARK: - Logo (Optional, Circle)

    private var logoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "rosette").foregroundColor(.kePrimary)
                Text("Restaurant Logo (optional)")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
            }
            Text("Small mark shown as a badge on your card. Skip if your picture already includes your logo.")
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            HStack(spacing: 14) {
                PhotosPicker(selection: $logoItem, matching: .images) {
                    ZStack {
                        Circle()
                            .fill(Color.keCard)
                            .frame(width: 88, height: 88)
                            .overlay(Circle().stroke(Color.keSurface, lineWidth: 1))

                        if let logoImage {
                            Image(uiImage: logoImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 88, height: 88)
                                .clipShape(Circle())
                        } else {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 22))
                                .foregroundColor(.kePrimary)
                        }

                        if isUploadingLogo {
                            Circle().fill(Color.black.opacity(0.4)).frame(width: 88, height: 88)
                            ProgressView().tint(.white)
                        }
                    }
                }
                .onChange(of: logoItem) { _, newItem in
                    Task { await handleLogo(newItem) }
                }

                Text(logoUrl.isEmpty ? "Optional — adds a tag to your listing." : "Logo added")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)
                Spacer()
            }

            if let logoUploadError {
                Text(logoUploadError).font(.caption).foregroundColor(.keError)
            }
        }
    }

    private func handleLogo(_ item: PhotosPickerItem?) async {
        guard let item,
              let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data) else { return }
        logoImage = image
        logoUploadError = nil
        isUploadingLogo = true
        do {
            logoUrl = try await UploadService.shared.uploadImage(image, kind: .restaurantLogo)
        } catch {
            logoUploadError = "Upload failed. Tap to retry."
        }
        isUploadingLogo = false
    }

    // MARK: - Certificate (Required, Rectangle)

    private var certificatePicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Kosher Certificate Photo *")
                .font(.caption.bold())
                .foregroundColor(.keTextSecondary)
            Text("Required — a clear photo of your current kosher certificate.")
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            PhotosPicker(selection: $certItem, matching: .images) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.keSurface)
                        .aspectRatio(4/3, contentMode: .fit)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.keCard, lineWidth: 1))

                    if let certImage {
                        Image(uiImage: certImage)
                            .resizable()
                            .scaledToFill()
                            .aspectRatio(4/3, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else {
                        VStack(spacing: 4) {
                            Image(systemName: "doc.badge.plus")
                                .font(.system(size: 26))
                                .foregroundColor(.kePrimary)
                            Text("Tap to add certificate")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)
                        }
                    }

                    if isUploadingCert {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color.black.opacity(0.4))
                            .aspectRatio(4/3, contentMode: .fit)
                        ProgressView().tint(.white)
                    }
                }
            }
            .onChange(of: certItem) { _, newItem in
                Task { await handleCert(newItem) }
            }

            if let certUploadError {
                Text(certUploadError).font(.caption).foregroundColor(.keError)
            }
        }
    }

    private func handleCert(_ item: PhotosPickerItem?) async {
        guard let item,
              let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data) else { return }
        certImage = image
        certUploadError = nil
        isUploadingCert = true
        do {
            kosherCertificateUrl = try await UploadService.shared.uploadImage(image, kind: .certificate)
        } catch {
            certUploadError = "Upload failed. Tap to retry."
        }
        isUploadingCert = false
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Create your restaurant")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.keTextPrimary)
            Text("This is what consumers will see. You can edit everything later in Settings.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
        }
    }

    private var submitButton: some View {
        Button {
            Task { await submit() }
        } label: {
            Group {
                if isSubmitting {
                    ProgressView().tint(.white)
                } else {
                    Text("Create restaurant").font(.headline)
                }
            }
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
            .cornerRadius(14)
        }
        .disabled(!canSubmit)
    }

    private func submit() async {
        guard canSubmit else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let cuisine = cuisineInput
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        let body = APIService.CreateRestaurantBody(
            name: name.trimmingCharacters(in: .whitespaces),
            description: description.trimmingCharacters(in: .whitespaces),
            imageUrl: pictureUrl,
            logoUrl: logoUrl,
            phone: phone.trimmingCharacters(in: .whitespaces),
            email: email.trimmingCharacters(in: .whitespaces),
            street: street.trimmingCharacters(in: .whitespaces),
            city: city.trimmingCharacters(in: .whitespaces),
            state: stateField.trimmingCharacters(in: .whitespaces),
            zipCode: zipCode.trimmingCharacters(in: .whitespaces),
            kosherCertification: kosherCert.rawValue,
            certifyingAgency: certifyingAgency.trimmingCharacters(in: .whitespaces),
            kosherCertificateUrl: kosherCertificateUrl,
            cuisineType: cuisine,
            isCholovYisroel: isCholovYisroel,
            isPasYisroel: isPasYisroel,
            isGlattKosher: isGlattKosher
        )

        do {
            let created = try await APIService.shared.createRestaurant(body)
            onCreated(created)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Helpers

    @ViewBuilder
    private func section<Content: View>(_ title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: icon).foregroundColor(.kePrimary)
                Text(title)
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
            }
            VStack(spacing: 12) { content() }
                .padding(14)
                .background(Color.keCard)
                .cornerRadius(12)
        }
    }

    private func textField(_ placeholder: String, text: Binding<String>, keyboard: UIKeyboardType = .default, axis: Axis = .horizontal) -> some View {
        TextField(placeholder, text: text, axis: axis)
            .keyboardType(keyboard)
            .textInputAutocapitalization(keyboard == .emailAddress ? .never : .sentences)
            .foregroundColor(.keTextPrimary)
            .padding()
            .background(Color.keSurface)
            .cornerRadius(10)
    }

    private func kosherToggleRow(_ label: String, isOn: Binding<Bool>) -> some View {
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
}
