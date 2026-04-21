import SwiftUI

/// Manages the user's saved delivery addresses from the profile screen.
/// Separate from AddressPickerSheet (which is for checkout-time selection) —
/// this one is purely for list management: add, delete, change default.
struct SavedAddressesView: View {
    @State private var addresses: [Address] = []
    @State private var isLoading = true
    @State private var showAddForm = false
    @State private var errorMessage: String?
    @State private var pendingDefaultID: String?

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if isLoading && addresses.isEmpty {
                ProgressView().tint(.kePrimary)
            } else if addresses.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(addresses) { a in
                        addressRow(a)
                            .listRowBackground(Color.keCard)
                            .listRowSeparatorTint(Color.keDivider)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    Task { await delete(a) }
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                if !a.isDefault {
                                    Button {
                                        Task { await makeDefault(a) }
                                    } label: {
                                        Label("Default", systemImage: "star.fill")
                                    }
                                    .tint(.kePrimary)
                                }
                            }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }

            if let err = errorMessage {
                VStack {
                    Spacer()
                    Text(err)
                        .font(.caption)
                        .foregroundColor(.keTextOnAccent)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(Color.keError)
                        .cornerRadius(Theme.cornerRadiusMedium)
                        .padding(.bottom, 24)
                }
            }
        }
        .navigationTitle("Saved Addresses")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showAddForm = true } label: {
                    Image(systemName: "plus").foregroundColor(.kePrimary)
                }
            }
        }
        .sheet(isPresented: $showAddForm) {
            AddressFormSheet(
                onSaved: { _ in
                    Task { await load() }
                },
                onWarning: { errorMessage = $0 }
            )
        }
        .task { await load() }
    }

    private var emptyState: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
            Text("No saved addresses")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("Add a home or work address to speed up checkout.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            Button { showAddForm = true } label: {
                Text("Add Address")
            }
            .buttonStyle(KEPrimaryButtonStyle())
            .frame(maxWidth: 320)
        }
    }

    private func addressRow(_ a: Address) -> some View {
        HStack(spacing: 12) {
            Image(systemName: iconFor(label: a.label))
                .font(.system(size: 18))
                .foregroundColor(.kePrimary)
                .frame(width: 28)

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

            if pendingDefaultID == a.id {
                ProgressView().tint(.kePrimary)
            }
        }
        .padding(.vertical, 6)
    }

    private func iconFor(label: String) -> String {
        switch label.lowercased() {
        case "home": return "house.fill"
        case "work", "office": return "briefcase.fill"
        default: return "mappin.circle.fill"
        }
    }

    private func load() async {
        errorMessage = nil
        do {
            addresses = try await APIService.shared.listAddresses()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    private func delete(_ a: Address) async {
        errorMessage = nil
        do {
            try await APIService.shared.deleteAddress(id: a.id)
            addresses.removeAll { $0.id == a.id }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func makeDefault(_ a: Address) async {
        pendingDefaultID = a.id
        defer { pendingDefaultID = nil }
        errorMessage = nil
        do {
            try await APIService.shared.setDefaultAddress(id: a.id)
            addresses = addresses.map { addr in
                var copy = addr
                copy.isDefault = addr.id == a.id
                return copy
            }
            addresses.sort { $0.isDefault && !$1.isDefault }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}
