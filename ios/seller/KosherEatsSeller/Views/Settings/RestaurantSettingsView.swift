import SwiftUI

struct RestaurantSettingsView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @StateObject private var dashVM = DashboardViewModel()

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
                                        ForEach(KosherCertification.allCases) { cert in
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
                            .foregroundColor(.white)
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

                        Spacer().frame(height: 20)
                    }
                    .padding()
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
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
            .overlay {
                if showSaved {
                    VStack {
                        Spacer()
                        Text("Settings saved")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
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
        }
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
        deliveryFee = String(format: "%.2f", r.deliveryFee / 100.0)
        minOrder = String(format: "%.2f", r.minOrder)
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
        restaurant.deliveryFee = ((Double(deliveryFee) ?? 0) * 100).rounded()
        restaurant.minOrder = Double(minOrder) ?? 0
        restaurant.estDeliveryMin = Int(estDeliveryMin) ?? 20
        restaurant.estDeliveryMax = Int(estDeliveryMax) ?? 45
        restaurant.kosherCertification = kosherCert
        restaurant.certifyingAgency = certifyingAgency
        restaurant.isCholovYisroel = isCholovYisroel
        restaurant.isPasYisroel = isPasYisroel
        restaurant.isGlattKosher = isGlattKosher

        do {
            let _ = try await APIService.shared.updateRestaurant(restaurant)
            withAnimation { showSaved = true }
        } catch {
            errorMessage = error.localizedDescription
        }

        isSaving = false
    }
}
