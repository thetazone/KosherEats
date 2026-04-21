import SwiftUI

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

    @State private var isSaving = false
    @State private var showSaved = false
    @State private var showLogoutConfirm = false
    @State private var showDeleteConfirm = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
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
                                            deliveryFee = val.filter { $0.isNumber || $0 == "." }
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
                                            minOrder = val.filter { $0.isNumber || $0 == "." }
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

                    Spacer().frame(height: 20)
                }
                .padding()
                .adaptiveContentWidth(700)
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
            await dashVM.load()
            populateFields()
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

            Toggle("", isOn: isOn)
                .tint(.kePrimary)
                .labelsHidden()
        }
        .padding(.horizontal, 4)
    }

    private func populateFields() {
        guard let r = dashVM.restaurant else { return }
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
    }

    private func save() async {
        guard var restaurant = dashVM.restaurant else { return }
        isSaving = true
        errorMessage = nil

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
        restaurant.deliveryFee = Int(((Double(deliveryFee) ?? 0) * 100).rounded())
        restaurant.minOrder = Int(((Double(minOrder) ?? 0) * 100).rounded())
        restaurant.estDeliveryMin = Int(estDeliveryMin) ?? 20
        restaurant.estDeliveryMax = Int(estDeliveryMax) ?? 45
        restaurant.kosherCertification = kosherCert
        restaurant.certifyingAgency = certifyingAgency
        restaurant.isCholovYisroel = isCholovYisroel
        restaurant.isPasYisroel = isPasYisroel
        restaurant.isGlattKosher = isGlattKosher

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
