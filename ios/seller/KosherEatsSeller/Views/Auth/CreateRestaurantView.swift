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

    @State private var logoItem: PhotosPickerItem?
    @State private var logoImage: UIImage?
    @State private var logoUrl = ""
    @State private var isUploadingLogo = false
    @State private var logoUploadError: String?

    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private var canSubmit: Bool {
        !isSubmitting && !isUploadingLogo &&
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

                    logoPickerSection

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

    private var logoPickerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "photo.circle.fill").foregroundColor(.kePrimary)
                Text("Restaurant Logo")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
            }
            Text("Shown to customers in the marketplace and on your restaurant page.")
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            HStack {
                Spacer()
                PhotosPicker(selection: $logoItem, matching: .images) {
                    ZStack {
                        Circle()
                            .fill(Color.keCard)
                            .frame(width: 120, height: 120)
                            .overlay(Circle().stroke(Color.keSurface, lineWidth: 1))

                        if let logoImage {
                            Image(uiImage: logoImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 120, height: 120)
                                .clipShape(Circle())
                        } else {
                            VStack(spacing: 4) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 24))
                                    .foregroundColor(.kePrimary)
                                Text("Add logo")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                            }
                        }

                        if isUploadingLogo {
                            Circle().fill(Color.black.opacity(0.4)).frame(width: 120, height: 120)
                            ProgressView().tint(.white)
                        }
                    }
                }
                .onChange(of: logoItem) { _, newItem in
                    Task {
                        guard let newItem,
                              let data = try? await newItem.loadTransferable(type: Data.self),
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
                }
                Spacer()
            }

            if let logoUploadError {
                Text(logoUploadError)
                    .font(.caption)
                    .foregroundColor(.keError)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
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
            imageUrl: logoUrl,
            phone: phone.trimmingCharacters(in: .whitespaces),
            email: email.trimmingCharacters(in: .whitespaces),
            street: street.trimmingCharacters(in: .whitespaces),
            city: city.trimmingCharacters(in: .whitespaces),
            state: stateField.trimmingCharacters(in: .whitespaces),
            zipCode: zipCode.trimmingCharacters(in: .whitespaces),
            kosherCertification: kosherCert.rawValue,
            certifyingAgency: certifyingAgency.trimmingCharacters(in: .whitespaces),
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
