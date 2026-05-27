import SwiftUI

/// AddressPickerSheet lets the user pick an existing saved address or add a
/// new one. Presented from CheckoutView when the user taps "Change" — or
/// automatically when they have zero saved addresses at checkout time.
///
/// TODO: geocode addresses with MKLocalSearch instead of submitting lat/lng 0,0.
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
                } else if let error = errorMessage, addresses.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "wifi.slash")
                            .font(.system(size: 40))
                            .foregroundColor(.keError)
                        Text(error)
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.center)
                        Button("Retry") {
                            errorMessage = nil
                            Task { await load() }
                        }
                        .buttonStyle(KEPrimaryButtonStyle())
                    }
                    .padding()
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
                            .accessibilityLabel("\(a.label), \(a.formatted)\(a.isDefault ? ", default address" : "")")
                            .accessibilityAddTraits(selected?.id == a.id ? .isSelected : [])
                            .accessibilityHint("Double tap to select this address")
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Delivery Address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    // "Done" not "Cancel" — `selected` is bound from the
                    // caller and gets set on row-tap or on first load. There's
                    // no way to discard the selection from this sheet, so
                    // labeling the button "Cancel" was misleading: users left
                    // it alone, expecting to confirm the address with a
                    // separate button that doesn't exist.
                    Button("Done") { dismiss() }
                        .foregroundColor(.kePrimary)
                        .fontWeight(.semibold)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showAddForm = true
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(.kePrimary)
                    }
                    .accessibilityLabel("Add new address")
                }
            }
            .sheet(isPresented: $showAddForm) {
                AddressFormSheet(
                    onSaved: { newAddress in
                        selected = newAddress
                        Task { await load() }
                    },
                    onWarning: { errorMessage = $0 }
                )
            }
            .task { await load() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "house")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
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
            .frame(maxWidth: 320)
            .accessibilityHint("Opens a form to add a new delivery address")
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
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 6)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
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
