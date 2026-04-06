import SwiftUI

/// AddressPickerSheet lets the user pick an existing saved address or add a
/// new one. Presented from CheckoutView when the user taps "Change" — or
/// automatically when they have zero saved addresses at checkout time.
///
/// Kept intentionally simple: label/street/apt/city/state/zip fields,
/// hardcoded lat/lng of 0,0 in dev (we'd geocode with MKLocalSearch or the
/// Google Places API in prod).
struct AddressPickerSheet: View {
    @Binding var selected: Address?
    @Environment(\.dismiss) var dismiss

    @State private var addresses: [Address] = []
    @State private var isLoading = false
    @State private var showAddForm = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if isLoading {
                    ProgressView().tint(.kePrimary)
                } else if addresses.isEmpty {
                    emptyState
                } else {
                    List {
                        ForEach(addresses) { a in
                            Button {
                                selected = a
                                dismiss()
                            } label: {
                                addressRow(a)
                            }
                            .listRowBackground(Color.keCard)
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Delivery Address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showAddForm = true
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(.kePrimary)
                    }
                }
            }
            .sheet(isPresented: $showAddForm) {
                AddAddressForm { newAddress in
                    selected = newAddress
                    Task { await load() }
                }
            }
            .task { await load() }
            .onAppear {
                // If no saved addresses, jump straight to the add form.
                if addresses.isEmpty && !isLoading {
                    showAddForm = true
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "house")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
            Text("No saved addresses")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("Add a delivery address to place your order.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)

            Button {
                showAddForm = true
            } label: {
                Text("Add Address")
            }
            .buttonStyle(KEPrimaryButtonStyle())
            .frame(width: 220)
        }
    }

    private func addressRow(_ a: Address) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(a.label)
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    if a.isDefault {
                        Text("Default")
                            .font(.caption2.bold())
                            .foregroundColor(.kePrimary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.kePrimary.opacity(0.15))
                            .cornerRadius(4)
                    }
                }
                Text(a.formatted)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            }
            Spacer()
            if selected?.id == a.id {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.kePrimary)
            }
        }
        .padding(.vertical, 6)
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            addresses = try await APIService.shared.listAddresses()
            if selected == nil {
                selected = addresses.first(where: { $0.isDefault }) ?? addresses.first
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Add address form

private struct AddAddressForm: View {
    let onSaved: (Address) -> Void
    @Environment(\.dismiss) var dismiss

    @State private var label = "Home"
    @State private var street = ""
    @State private var apt = ""
    @State private var city = ""
    @State private var state = ""
    @State private var zip = ""
    @State private var isDefault = true
    @State private var isSaving = false
    @State private var errorMessage: String?

    private var formValid: Bool {
        !street.isEmpty && !city.isEmpty && !state.isEmpty && !zip.isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: Theme.spacingMD) {
                        field("Label (e.g. Home, Work)", text: $label)
                        field("Street address", text: $street)
                        field("Apt / Suite (optional)", text: $apt)
                        field("City", text: $city)
                        HStack(spacing: 12) {
                            field("State", text: $state)
                                .frame(maxWidth: 100)
                            field("Zip", text: $zip)
                        }

                        Toggle("Make this my default", isOn: $isDefault)
                            .tint(.kePrimary)
                            .foregroundColor(.keTextPrimary)

                        if let err = errorMessage {
                            Text(err)
                                .font(.caption)
                                .foregroundColor(.keError)
                        }

                        Button { Task { await save() } } label: {
                            if isSaving { ProgressView().tint(.white) } else { Text("Save address") }
                        }
                        .buttonStyle(KEPrimaryButtonStyle(isEnabled: formValid && !isSaving))
                        .disabled(!formValid || isSaving)
                        .padding(.top, 8)
                    }
                    .padding()
                }
            }
            .navigationTitle("Add address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
        }
    }

    private func field(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .keTextField()
    }

    private func save() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        // Lat/lng are stubbed 0,0 in dev — prod would geocode via MKLocalSearch
        // or Google Places before submission so delivery radius math works.
        let draft = Address(
            id: "", userID: "", label: label, street: street,
            apt: apt.isEmpty ? nil : apt, city: city, state: state, zipCode: zip,
            lat: 0, lng: 0, isDefault: isDefault,
        )
        do {
            let saved = try await APIService.shared.addAddress(draft)
            onSaved(saved)
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}
