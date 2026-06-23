import SwiftUI
import PhotosUI

struct RestaurantSettingsView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @StateObject private var dashVM = DashboardViewModel()
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.openURL) private var openURL

    @State private var name = ""
    @State private var description = ""
    @State private var phone = ""
    @State private var email = ""
    @State private var street = ""
    @State private var city = ""
    @State private var state = ""
    @State private var zipCode = ""
    @State private var deliveryFee = ""
    @State private var minOrder = ""
    @State private var estDeliveryMin = ""
    @State private var estDeliveryMax = ""
    @State private var kosherCert: KosherCertification = .OU
    @State private var certifyingAgency = ""
    @State private var isCholovYisroel = false
    @State private var isPasYisroel = false
    @State private var isGlattKosher = false

    @State private var kosherCertificateUrl: String?
    @State private var certPickerItem: PhotosPickerItem?
    @State private var certImage: UIImage?
    @State private var isUploadingCert = false

    @State private var isSaving = false
    @State private var showSaved = false
    @State private var showLogoutConfirm = false
    @State private var showDeleteConfirm = false
    @State private var errorMessage: String?

    @State private var isLoading = false
    /// The restaurant id the form was last populated from. We only repopulate
    /// when the loaded restaurant is different (first load, or the seller
    /// switched restaurants) — never on a plain tab re-appearance — so a seller
    /// mid-edit who flips to Dashboard and back doesn't lose unsaved changes.
    @State private var populatedRestaurantId: String?

    var body: some View {
        NavigationStack {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
              if dashVM.restaurant == nil && (isLoading || dashVM.isLoading) {
                ProgressView("Loading settings...")
                    .tint(.kePrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 80)
              } else if dashVM.restaurant == nil {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 32))
                        .foregroundColor(.keError)
                        .accessibilityHidden(true)
                    Text(errorMessage ?? dashVM.errorMessage ?? "Couldn't load your restaurant settings.")
                        .font(.subheadline)
                        .foregroundColor(.keError)
                        .multilineTextAlignment(.center)
                    Button("Retry") {
                        Task { await reload() }
                    }
                    .font(.subheadline.bold())
                    .foregroundColor(.kePrimary)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.top, 80)
                .accessibilityElement(children: .combine)
              } else {
                VStack(spacing: 24) {
                    // Restaurant Info
                    settingsSection("Restaurant Info", icon: "storefront.fill") {
                        settingsField("Name", text: $name)
                        settingsField("Description", text: $description)
                        settingsField("Phone", text: $phone, keyboard: .phonePad)
                        settingsField("Email", text: $email, keyboard: .emailAddress)
                    }

                    // Address
                    settingsSection("Address", icon: "mappin.and.ellipse") {
                        settingsField("Street", text: $street)
                        HStack(spacing: 12) {
                            settingsField("City", text: $city)
                            settingsField("State", text: $state)
                                .frame(width: 80)
                        }
                        settingsField("ZIP Code", text: $zipCode, keyboard: .numberPad)
                    }

                    // Delivery Settings
                    settingsSection("Delivery", icon: "car.fill") {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Delivery Fee")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                                HStack {
                                    Text("$")
                                        .foregroundColor(.keTextMuted)
                                    TextField("0.00", text: $deliveryFee)
                                        .keyboardType(.decimalPad)
                                        .foregroundColor(.keTextPrimary)
                                        .onChange(of: deliveryFee) { _, val in
                                            var filtered = val.replacingOccurrences(of: ",", with: ".").filter { $0.isNumber || $0 == "." }
                                            // Keep only the first decimal point
                                            if let first = filtered.firstIndex(of: ".") {
                                                let afterDot = filtered.index(after: first)
                                                if afterDot < filtered.endIndex {
                                                    let tail = filtered[afterDot...].filter { $0 != "." }
                                                    filtered = String(filtered[...first]) + tail
                                                }
                                            }
                                            deliveryFee = filtered
                                        }
                                }
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(10)
                            }

                            VStack(alignment: .leading, spacing: 8) {
                                Text("Min Order")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                                HStack {
                                    Text("$")
                                        .foregroundColor(.keTextMuted)
                                    TextField("0.00", text: $minOrder)
                                        .keyboardType(.decimalPad)
                                        .foregroundColor(.keTextPrimary)
                                        .onChange(of: minOrder) { _, val in
                                            var filtered = val.replacingOccurrences(of: ",", with: ".").filter { $0.isNumber || $0 == "." }
                                            // Keep only the first decimal point
                                            if let first = filtered.firstIndex(of: ".") {
                                                let afterDot = filtered.index(after: first)
                                                if afterDot < filtered.endIndex {
                                                    let tail = filtered[afterDot...].filter { $0 != "." }
                                                    filtered = String(filtered[...first]) + tail
                                                }
                                            }
                                            minOrder = filtered
                                        }
                                }
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(10)
                            }
                        }

                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Est. Min (min)")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                                TextField("20", text: $estDeliveryMin)
                                    .keyboardType(.numberPad)
                                    .foregroundColor(.keTextPrimary)
                                    .padding()
                                    .background(Color.keCard)
                                    .cornerRadius(10)
                            }

                            VStack(alignment: .leading, spacing: 8) {
                                Text("Est. Max (min)")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                                TextField("45", text: $estDeliveryMax)
                                    .keyboardType(.numberPad)
                                    .foregroundColor(.keTextPrimary)
                                    .padding()
                                    .background(Color.keCard)
                                    .cornerRadius(10)
                            }
                        }
                    }

                    // Kosher Certification
                    settingsSection("Kosher Certification", icon: "checkmark.seal.fill") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Certification")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(Array(KosherCertification.allCases), id: \.rawValue) { cert in
                                        Button {
                                            kosherCert = cert
                                        } label: {
                                            Text(cert.displayName)
                                                .font(.caption.bold())
                                                .foregroundColor(
                                                    kosherCert == cert ? .white : .keTextSecondary
                                                )
                                                .padding(.horizontal, 14)
                                                .padding(.vertical, 8)
                                                .background(
                                                    kosherCert == cert ? Color.kePrimary : Color.keCard
                                                )
                                                .cornerRadius(8)
                                        }
                                    }
                                }
                            }
                        }

                        settingsField("Certifying Agency", text: $certifyingAgency)

                        kosherToggleRow("Cholov Yisroel", isOn: $isCholovYisroel)
                        kosherToggleRow("Pas Yisroel", isOn: $isPasYisroel)
                        kosherToggleRow("Glatt Kosher", isOn: $isGlattKosher)

                        VStack(alignment: .leading, spacing: 8) {
                            Text("Certificate Document")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)

                            if let certImage {
                                Image(uiImage: certImage)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxHeight: 180)
                                    .cornerRadius(10)
                            } else if let url = kosherCertificateUrl, !url.isEmpty, let imageURL = URL(string: url) {
                                AsyncImage(url: imageURL) { phase in
                                    switch phase {
                                    case .success(let image):
                                        image.resizable().scaledToFit().frame(maxHeight: 180).cornerRadius(10)
                                    default:
                                        Text("Certificate uploaded")
                                            .font(.caption)
                                            .foregroundColor(.keSuccess)
                                    }
                                }
                            }

                            PhotosPicker(selection: $certPickerItem, matching: .images) {
                                HStack {
                                    if isUploadingCert {
                                        ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                                    } else {
                                        Image(systemName: "doc.badge.arrow.up")
                                        Text(kosherCertificateUrl != nil ? "Replace Certificate" : "Upload Certificate")
                                    }
                                }
                                .font(.subheadline.bold())
                                .foregroundColor(.kePrimary)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .background(Color.kePrimary.opacity(0.12))
                                .cornerRadius(10)
                            }
                            .disabled(isUploadingCert)
                            .onChange(of: certPickerItem) { _, newItem in
                                Task {
                                    guard newItem != nil else { return }
                                    guard let data = try? await newItem?.loadTransferable(type: Data.self),
                                          let image = UIImage(data: data) else {
                                        errorMessage = "Couldn't read the selected image. Try a different photo."
                                        return
                                    }
                                    // Remember the current state so a failed upload doesn't
                                    // leave the just-picked (unsaved) image showing as if it's
                                    // the live certificate — Save would then persist the OLD url.
                                    let previousImage = certImage
                                    let previousUrl = kosherCertificateUrl
                                    certImage = image
                                    isUploadingCert = true
                                    errorMessage = nil
                                    do {
                                        kosherCertificateUrl = try await UploadService.shared.uploadImage(image, kind: .certificate)
                                    } catch {
                                        certImage = previousImage
                                        kosherCertificateUrl = previousUrl
                                        errorMessage = "Certificate upload failed. Please try again."
                                    }
                                    isUploadingCert = false
                                }
                            }
                        }
                    }

                    // Save Button
                    Button {
                        Task { await save() }
                    } label: {
                        Group {
                            if isSaving {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Text("Save Changes")
                                    .font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color.kePrimary)
                        .cornerRadius(14)
                    }
                    .disabled(isSaving)

                    if let error = errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.keError)
                    }

                    // Connected Accounts
                    NavigationLink {
                        ConnectedAccountsView()
                            .environmentObject(authVM)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "person.crop.circle.badge.plus")
                                .font(.system(size: 16))
                                .foregroundColor(.kePrimary)
                                .frame(width: 24)
                            Text("Connected Accounts")
                                .font(.system(size: 15))
                                .foregroundColor(.keTextPrimary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13))
                                .foregroundColor(.keTextMuted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .background(Color.keCard)
                        .cornerRadius(12)
                    }

                    // POS Integrations (Clover today; Square/Toast soon)
                    NavigationLink {
                        IntegrationsView()
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "printer.fill")
                                .font(.system(size: 16))
                                .foregroundColor(.kePrimary)
                                .frame(width: 24)
                            Text("Integrations")
                                .font(.system(size: 15))
                                .foregroundColor(.keTextPrimary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13))
                                .foregroundColor(.keTextMuted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .background(Color.keCard)
                        .cornerRadius(12)
                    }

                    // Legal — required in-app by App Store Review
                    // (guideline 5.1.1). External URLs open in Safari
                    // so we don't have to host an in-app webview.
                    VStack(spacing: 0) {
                        legalLinkRow("Privacy Policy", icon: "shield.fill") {
                            openURL(LegalURLs.privacyPolicy)
                        }
                        Divider().background(Color.keBorder)
                        legalLinkRow("Terms of Service", icon: "doc.text.fill") {
                            openURL(LegalURLs.termsOfService)
                        }
                        Divider().background(Color.keBorder)
                        legalLinkRow("Help & Support", icon: "questionmark.circle.fill") {
                            openURL(LegalURLs.supportEmail)
                        }
                    }
                    .background(Color.keCard)
                    .cornerRadius(12)

                    // Logout
                    Button {
                        showLogoutConfirm = true
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                            Text("Log Out")
                        }
                        .font(.subheadline.bold())
                        .foregroundColor(.keError)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Color.keError.opacity(0.1))
                        .cornerRadius(12)
                    }

                    // Delete Account
                    Button {
                        showDeleteConfirm = true
                    } label: {
                        HStack {
                            Image(systemName: "trash")
                            Text("Delete Account")
                        }
                        .font(.subheadline.bold())
                        .foregroundColor(.keError)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Color.keError.opacity(0.1))
                        .cornerRadius(12)
                    }
                    .accessibilityLabel("Delete account")
                    .accessibilityHint("Permanently deletes your account and all data")

                    Spacer().frame(height: 20)
                }
                .padding()
                .adaptiveContentWidth(700)
              } // else (restaurant loaded)
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil, from: nil, for: nil
                        )
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack {
                Text("Settings")
                    .font(.largeTitle.bold())
                    .foregroundColor(.keTextPrimary)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.top, 8)
            .padding(.bottom, 12)
            .background(Color.keBackground)
        }
        .task {
            await reload()
        }
        .alert("Log Out", isPresented: $showLogoutConfirm) {
            Button("Cancel", role: .cancel) { }
            Button("Log Out", role: .destructive) {
                authVM.logout()
            }
        } message: {
            Text("Are you sure you want to log out?")
        }
        .alert("Delete Account", isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                Task { await authVM.deleteAccount() }
            }
        } message: {
            Text("This will permanently delete your account and all associated data. This action cannot be undone.")
        }
        .overlay {
            if showSaved {
                VStack {
                    Spacer()
                    Text("Settings saved")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextOnAccent)
                        .padding()
                        .background(Color.keSuccess)
                        .cornerRadius(12)
                        .padding(.bottom, 20)
                }
                .transition(.move(edge: .bottom))
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        withAnimation { showSaved = false }
                    }
                }
            }
        }
        } // NavigationStack
    }

    // MARK: - Helpers

    private func settingsSection<Content: View>(
        _ title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .foregroundColor(.kePrimary)
                Text(title)
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
            }

            VStack(spacing: 12) {
                content()
            }
            .padding()
            .background(Color.keSurface)
            .cornerRadius(14)
        }
    }

    private func settingsField(
        _ label: String,
        text: Binding<String>,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            TextField("", text: text)
                .keyboardType(keyboard)
                .foregroundColor(.keTextPrimary)
                .padding()
                .background(Color.keCard)
                .cornerRadius(10)
                .accessibilityLabel(label)
        }
    }

    private func legalLinkRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundColor(.keTextSecondary)
                    .frame(width: 24)
                Text(title)
                    .font(.system(size: 15))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Image(systemName: "arrow.up.right.square")
                    .font(.system(size: 13))
                    .foregroundColor(.keTextMuted)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
    }

    private func kosherToggleRow(_ label: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.keTextPrimary)

            Spacer()

            Toggle(label, isOn: isOn)
                .tint(.kePrimary)
                .labelsHidden()
        }
        .padding(.horizontal, 4)
    }

    /// Loads the restaurant, then populates the form only when it's a *new*
    /// restaurant (first load or the seller switched restaurants). This keeps
    /// `.task` (which re-fires every time the Settings tab re-appears in the
    /// TabView) from wiping unsaved edits when the seller hops to another tab
    /// and back.
    private func reload() async {
        isLoading = true
        await dashVM.load()
        isLoading = false
        guard let r = dashVM.restaurant else { return }
        if populatedRestaurantId != r.id {
            populateFields()
        }
    }

    private func populateFields() {
        guard let r = dashVM.restaurant else { return }
        populatedRestaurantId = r.id
        name = r.name
        description = r.description
        phone = r.phone
        email = r.email
        street = r.street
        city = r.city
        state = r.state
        zipCode = r.zipCode
        deliveryFee = String(format: "%.2f", Double(r.deliveryFee) / 100)
        minOrder = String(format: "%.2f", Double(r.minOrder) / 100)
        estDeliveryMin = "\(r.estDeliveryMin)"
        estDeliveryMax = "\(r.estDeliveryMax)"
        kosherCert = r.kosherCertification
        certifyingAgency = r.certifyingAgency
        isCholovYisroel = r.isCholovYisroel
        isPasYisroel = r.isPasYisroel
        isGlattKosher = r.isGlattKosher
        kosherCertificateUrl = r.kosherCertificateUrl
    }

    private func save() async {
        guard var restaurant = dashVM.restaurant else {
            // The restaurant never loaded (cold start / network blip). Don't
            // let Save look dead — surface a retry path instead of returning
            // silently.
            errorMessage = "Couldn't load your restaurant — pull down or tap Retry, then save again."
            return
        }

        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedState = state.trimmingCharacters(in: .whitespaces)

        // Validate required fields before sending to the server.
        if trimmedName.isEmpty {
            errorMessage = "Restaurant name is required."
            return
        }
        if trimmedName.count > 200 {
            errorMessage = "Restaurant name must be 200 characters or fewer."
            return
        }
        if phone.trimmingCharacters(in: .whitespaces).isEmpty {
            errorMessage = "Phone number is required."
            return
        }
        if trimmedEmail.isEmpty || !trimmedEmail.contains("@") || !trimmedEmail.contains(".") {
            errorMessage = "A valid email address is required."
            return
        }
        if !trimmedState.isEmpty && trimmedState.count != 2 {
            errorMessage = "State abbreviation must be exactly 2 characters (e.g. NY)."
            return
        }
        if zipCode.trimmingCharacters(in: .whitespaces).isEmpty {
            errorMessage = "ZIP code is required."
            return
        }
        if let min = Int(estDeliveryMin), let max = Int(estDeliveryMax), min > max {
            errorMessage = "Estimated minimum delivery time can't exceed maximum."
            return
        }

        isSaving = true
        errorMessage = nil

        // Re-fetch the current server state right before saving so we don't PUT
        // a stale `is_open` (or any other field this form doesn't edit). This
        // Settings screen owns its own DashboardViewModel, so the dashboard's
        // open/closed toggle — which writes through a *different* VM — won't be
        // reflected in our snapshot. updateRestaurant() PUTs the whole object,
        // and the backend COALESCE-writes is_open from it, so a stale value here
        // would silently flip the restaurant open/closed on every Save.
        // NOTE: the cleaner fix is a partial-update payload that omits is_open
        // entirely (tracked in companionEditsNeeded for APIService).
        do {
            restaurant = try await APIService.shared.getRestaurant()
        } catch {
            errorMessage = "Couldn't refresh your restaurant — check your connection and try again."
            isSaving = false
            return
        }

        restaurant.name = name
        restaurant.description = description
        restaurant.phone = phone
        restaurant.email = email
        restaurant.street = street
        restaurant.city = city
        restaurant.state = state
        restaurant.zipCode = zipCode
        // Dollars in the text fields → cents on the wire, matching the
        // backend contract (delivery_fee / min_order are INTEGER cents).
        restaurant.deliveryFee = CurrencyFormat.parseCents(deliveryFee) ?? 0
        restaurant.minOrder = CurrencyFormat.parseCents(minOrder) ?? 0
        guard let parsedMin = Int(estDeliveryMin), parsedMin > 0 else {
            errorMessage = "Estimated minimum delivery time must be a number."
            isSaving = false
            return
        }
        guard let parsedMax = Int(estDeliveryMax), parsedMax > 0 else {
            errorMessage = "Estimated maximum delivery time must be a number."
            isSaving = false
            return
        }
        restaurant.estDeliveryMin = parsedMin
        restaurant.estDeliveryMax = parsedMax
        restaurant.kosherCertification = kosherCert
        restaurant.certifyingAgency = certifyingAgency
        restaurant.isCholovYisroel = isCholovYisroel
        restaurant.isPasYisroel = isPasYisroel
        restaurant.isGlattKosher = isGlattKosher
        restaurant.kosherCertificateUrl = kosherCertificateUrl

        do {
            let updated = try await APIService.shared.updateRestaurant(restaurant)
            dashVM.restaurant = updated
            errorMessage = nil
            withAnimation { showSaved = true }
        } catch {
            errorMessage = error.localizedDescription
        }

        isSaving = false
    }
}
