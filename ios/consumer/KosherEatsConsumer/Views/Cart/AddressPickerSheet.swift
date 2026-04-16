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
                AddressFormSheet { newAddress in
                    selected = newAddress
                    Task { await load() }
                }
            }
            .task { await load() }
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

